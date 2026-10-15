package io.github.natanmacielc.dice.app.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.natanmacielc.dice.app.beacon.MutableClock;
import io.github.natanmacielc.dice.core.Quicknet;
import io.github.natanmacielc.dice.core.chain.ChainEntry;
import io.github.natanmacielc.dice.core.chain.ChainVerification;
import io.github.natanmacielc.dice.core.chain.ChainVerifier;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Invariante 2: escritas concorrentes na mesma mesa produzem uma cadeia linear, sem buracos nem forks.
 * Chama o RollService em várias threads: é o equivalente a várias instâncias do Lambda gravando no
 * mesmo DynamoDB (via HTTP, o servidor de teste do Lambda processa um evento por vez).
 */
@QuarkusTest
@TestProfile(LedgerProfile.class)
@WithTestResource(LocalAwsResource.class)
class LedgerConcurrencyTest {

    static final long LATEST = 2_000_000;
    static final Instant T0 = Quicknet.timeOf(LATEST).plusSeconds(1);

    @Inject
    MutableClock clock;

    @Inject
    FakeBeaconClient beacon;

    @Inject
    RollService rolls;

    @BeforeEach
    void reset() {
        clock.set(T0);
        beacon.reset();
    }

    @Test
    void fiftyParallelRequestsOnOneSessionStayLinear() throws Exception {
        String sessionId = rolls.createSession(null).sessionId();
        parallel(50, i -> rolls.requestRoll(sessionId, "p" + i, "1d20"));

        List<Map<String, Object>> entries = log(sessionId);
        assertEquals(50, entries.size());
        assertEquals(LongStream.rangeClosed(1, 50).boxed().toList(),
                entries.stream().map(e -> ((Number) e.get("seq")).longValue()).toList());
        assertChainValid(entries);
        Set<Long> nonces = new HashSet<>();
        entries.forEach(e -> nonces.add(((Number) payload(e).get("nonce")).longValue()));
        assertEquals(new HashSet<>(LongStream.rangeClosed(1, 50).boxed().toList()), nonces);
        assertEquals(50, rolls.log(sessionId, 0, 1).session().headSeq());
    }

    @Test
    void twentyParallelReadsResolveExactlyOnce() throws Exception {
        String sessionId = rolls.createSession(null).sessionId();
        String rollId = rolls.requestRoll(sessionId, "uma-vez", "4d6").roll().rollId();
        clock.set(Quicknet.timeOf(LATEST + 2));

        List<Object> dice = parallel(20, i -> rolls.roll(rollId).dice());
        assertEquals(1, new HashSet<>(dice).size(), "todos veem o mesmo resultado");

        List<Map<String, Object>> entries = log(sessionId);
        assertEquals(List.of("REQUEST", "ROUND", "RESULT"), entries.stream().map(e -> e.get("type")).toList());
        assertChainValid(entries);
    }

    @Test
    void mixedRequestsAndResolutionsKeepTheChainLinear() throws Exception {
        String sessionId = rolls.createSession(null).sessionId();
        List<String> pending = parallel(25, i -> rolls.requestRoll(sessionId, "a" + i, "2d10").roll().rollId());
        clock.set(Quicknet.timeOf(LATEST + 2));

        parallel(50, i -> i % 2 == 0
                ? rolls.roll(pending.get(i / 2)).status()
                : rolls.requestRoll(sessionId, "b" + i, "1d100").roll().status());

        // 25 REQUEST iniciais + 25 pares ROUND/RESULT + 25 REQUEST novos.
        List<Map<String, Object>> entries = log(sessionId);
        assertEquals(100, entries.size());
        assertChainValid(entries);
        for (String rollId : pending) {
            long rounds = entries.stream().filter(e -> e.get("type").equals("ROUND") && rollId.equals(payload(e).get("rollId"))).count();
            long results = entries.stream().filter(e -> e.get("type").equals("RESULT") && rollId.equals(payload(e).get("rollId"))).count();
            assertEquals(1, rounds, rollId);
            assertEquals(1, results, rollId);
        }
    }

    private static void assertChainValid(List<Map<String, Object>> entries) {
        ChainVerification v = ChainVerifier.verifySegmentJson(entries, ChainEntry.GENESIS_PREV_HASH);
        assertTrue(v.valid(), v.code() + ": " + v.message());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> entry) {
        return (Map<String, Object>) entry.get("payload");
    }

    /** Lê o log inteiro, página por página, no formato JSON da API. */
    private List<Map<String, Object>> log(String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        long after = 0;
        while (true) {
            RollService.LogPage page = rolls.log(sessionId, after, 100);
            page.entries().forEach(e -> out.add(e.toJson()));
            if (page.next() == null) {
                return out;
            }
            after = page.next();
        }
    }

    interface Task<T> {
        T run(int i) throws Exception;
    }

    /** Dispara n tarefas ao mesmo tempo (atrás de um portão) e devolve os resultados na ordem. */
    private static <T> List<T> parallel(int n, Task<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                Callable<T> c = () -> {
                    gate.await();
                    return task.run(index);
                };
                futures.add(pool.submit(c));
            }
            gate.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
