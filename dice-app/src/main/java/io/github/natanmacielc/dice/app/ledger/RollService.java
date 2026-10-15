package io.github.natanmacielc.dice.app.ledger;

import io.github.natanmacielc.dice.app.beacon.BeaconClient;
import io.github.natanmacielc.dice.core.ClientSeed;
import io.github.natanmacielc.dice.core.DiceExpression;
import io.github.natanmacielc.dice.core.Quicknet;
import io.github.natanmacielc.dice.core.chain.ChainEntry;
import io.github.natanmacielc.dice.core.chain.ChainFormat;
import io.github.natanmacielc.dice.core.chain.Player;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

@ApplicationScoped
public class RollService {

    /** Pendente há mais que isso depois do round alvo é exibido como EXPIRED (só exibição). */
    static final Duration EXPIRED_AFTER = Duration.ofSeconds(60);
    static final int MAX_LOG_PAGE = 100;
    static final int MAX_RESOLUTIONS_PER_LOG_READ = 10;
    static final int MAX_NAME_BYTES = 64;

    private final LedgerRepository ledger;
    private final ResolutionService resolution;
    private final BeaconClient beacon;
    private final Clock clock;

    public RollService(LedgerRepository ledger, ResolutionService resolution, BeaconClient beacon, Clock clock) {
        this.ledger = ledger;
        this.resolution = resolution;
        this.beacon = beacon;
        this.clock = clock;
    }

    public SessionRecord createSession(String name) {
        if (name != null) {
            int bytes = name.getBytes(StandardCharsets.UTF_8).length;
            if (bytes == 0 || bytes > MAX_NAME_BYTES || !name.chars().allMatch(c -> c >= 0x20 && c != 0x7f)
                    || !name.equals(new String(name.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))) {
                throw new InvalidRequestException("name deve ter de 1 a 64 bytes UTF-8, sem caracteres de controle");
            }
        }
        return ledger.createSession(name);
    }

    /**
     * Valida tudo antes de consultar o drand: entradas, assinatura do jogador sobre a expressão canônica
     * e mesa chain-v2. Depois fixa targetRound = latest + 2 e grava o REQUEST.
     */
    public LedgerRepository.Requested requestRoll(String sessionId, String clientSeed, String expression,
                                                  Player player) {
        if (clientSeed == null || expression == null) {
            throw new InvalidRequestException("clientSeed e expression são obrigatórios");
        }
        ClientSeed seed = ClientSeed.of(clientSeed);
        String canonical = DiceExpression.parse(expression).canonical();
        if (player == null) {
            throw new InvalidPlayerException("PLAYER_INVALID", "player é obrigatório");
        }
        String problem = player.formatProblem();
        if (problem != null) {
            throw new InvalidPlayerException("PLAYER_INVALID", "player: " + problem);
        }
        if (!player.linksValid()) {
            throw new InvalidPlayerException("PLAYER_SIGNATURE_INVALID",
                    "os vínculos não ligam a identidade à chave deste aparelho");
        }
        if (!player.signatureValid(sessionId, seed.value(), canonical)) {
            throw new InvalidPlayerException("PLAYER_SIGNATURE_INVALID",
                    "a assinatura não confere com mesa, seed, expressão canônica, identidade, apelido e requestId");
        }
        if (session(sessionId).chainFormat() != ChainFormat.CHAIN_V2) {
            throw new TableReadOnlyException("mesa " + sessionId + " é chain-v1 e não aceita rolls novos");
        }
        long targetRound = beacon.latest().round() + 2;
        return ledger.appendRequest(sessionId, seed.value(), canonical, targetRound, player);
    }

    public RollRecord roll(String rollId) {
        return resolution.resolveIfReady(findRoll(rollId));
    }

    public Proof proof(String rollId) {
        RollRecord roll = roll(rollId);
        if (!roll.resolved()) {
            throw new RollPendingException("roll " + rollId + " ainda pendente (round alvo " + roll.targetRound() + ")");
        }
        List<ChainEntry> entries = ledger.entries(roll.sessionId(), roll.requestSeq(), roll.resultSeq(), Integer.MAX_VALUE);
        return new Proof(roll, entries, session(roll.sessionId()).chainFormat());
    }

    public LogPage log(String sessionId, long after, int limit) {
        if (after < 0 || limit < 1 || limit > MAX_LOG_PAGE) {
            throw new InvalidRequestException("after >= 0 e 1 <= limit <= " + MAX_LOG_PAGE);
        }
        session(sessionId);
        for (String rollId : ledger.pendingRollIds(sessionId, MAX_RESOLUTIONS_PER_LOG_READ)) {
            ledger.findRoll(rollId).ifPresent(resolution::resolveIfReady);
        }
        SessionRecord session = session(sessionId);
        List<ChainEntry> entries = ledger.entries(sessionId, after + 1, session.headSeq(), limit);
        Long next = entries.isEmpty() || entries.get(entries.size() - 1).seq() >= session.headSeq()
                ? null : entries.get(entries.size() - 1).seq();
        return new LogPage(session, entries, next);
    }

    /** PENDING, RESOLVED ou EXPIRED (pendente há mais de 60 s depois do round alvo; só exibição). */
    public String displayStatus(RollRecord roll) {
        if (roll.resolved()) {
            return RollRecord.RESOLVED;
        }
        boolean late = clock.instant().isAfter(Quicknet.timeOf(roll.targetRound()).plus(EXPIRED_AFTER));
        return late ? "EXPIRED" : RollRecord.PENDING;
    }

    private SessionRecord session(String sessionId) {
        if (!Ids.isValid(sessionId)) {
            throw new NotFoundException("mesa " + sessionId);
        }
        return ledger.findSession(sessionId).orElseThrow(() -> new NotFoundException("mesa " + sessionId));
    }

    private RollRecord findRoll(String rollId) {
        if (!Ids.isValid(rollId)) {
            throw new NotFoundException("roll " + rollId);
        }
        return ledger.findRoll(rollId).orElseThrow(() -> new NotFoundException("roll " + rollId));
    }

    public record Proof(RollRecord roll, List<ChainEntry> entries, ChainFormat chainFormat) {
    }

    public record LogPage(SessionRecord session, List<ChainEntry> entries, Long next) {
    }
}
