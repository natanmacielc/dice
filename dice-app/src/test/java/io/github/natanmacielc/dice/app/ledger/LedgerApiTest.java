package io.github.natanmacielc.dice.app.ledger;

import static io.github.natanmacielc.dice.app.ledger.LedgerTestSupport.json;
import static io.github.natanmacielc.dice.app.ledger.LedgerTestSupport.newSession;
import static io.github.natanmacielc.dice.app.ledger.LedgerTestSupport.requestRoll;
import static io.restassured.RestAssured.get;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.natanmacielc.dice.app.beacon.Beacon;
import io.github.natanmacielc.dice.app.beacon.MutableClock;
import io.github.natanmacielc.dice.core.DiceRoller;
import io.github.natanmacielc.dice.core.Quicknet;
import io.github.natanmacielc.dice.core.RollResult;
import io.github.natanmacielc.dice.core.chain.ChainEntry;
import io.github.natanmacielc.dice.core.chain.ChainFormat;
import io.github.natanmacielc.dice.core.chain.ChainVerification;
import io.github.natanmacielc.dice.core.chain.ChainVerifier;
import io.github.natanmacielc.dice.core.chain.ProofVerifier;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(LedgerProfile.class)
@WithTestResource(LocalAwsResource.class)
class LedgerApiTest {

    static final long LATEST = 1_000_000;
    static final Instant T0 = Quicknet.timeOf(LATEST).plusSeconds(1);

    @Inject
    MutableClock clock;

    @Inject
    FakeBeaconClient beacon;

    @BeforeEach
    void reset() {
        clock.set(T0);
        beacon.reset();
    }

    @Test
    void fullFlowFromRequestToVerifiedProof() throws Exception {
        String sessionId = newSession();
        assertTrue(Ids.isValid(sessionId));

        Response created = requestRoll(sessionId, "mesa-do-dragão", " 2d6 + 3 ");
        created.then().statusCode(201)
                .header("Location", notNullValue())
                .body("status", equalTo("PENDING"))
                .body("expression", equalTo("2d6+3"))
                .body("nonce", equalTo(1))
                .body("targetRound", equalTo((int) LATEST + 2))
                .body("requestedAt", equalTo("2023-09-27T08:29:25.000Z"))
                .body("expectedAt", equalTo("2023-09-27T08:29:30.000Z"))
                .body("entry.seq", equalTo(1))
                .body("links.page", notNullValue());
        String rollId = created.path("rollId");

        get("/rolls/" + rollId).then().statusCode(200).body("status", equalTo("PENDING")).body("result", equalTo(null));
        get("/rolls/" + rollId + "/proof").then().statusCode(409).body("error", equalTo("ROLL_PENDING"));
        assertEquals(List.of(), beacon.roundCalls, "antes do instante do round alvo, nem consulta o drand");

        clock.set(Quicknet.timeOf(LATEST + 2));
        Beacon target = FakeBeaconClient.beacon(LATEST + 2);
        RollResult expected = DiceRoller.roll(target.randomness(), "mesa-do-dragão", 1, "2d6+3");
        get("/rolls/" + rollId).then().statusCode(200)
                .body("status", equalTo("RESOLVED"))
                .body("result.dice", equalTo(expected.dice()))
                .body("result.total", equalTo(expected.total()))
                .body("result.round", equalTo((int) LATEST + 2))
                .body("result.randomness", equalTo(target.randomness()));

        Response proof = get("/rolls/" + rollId + "/proof");
        proof.then().statusCode(200);
        ChainVerification verification = ProofVerifier.verify(json(proof));
        assertTrue(verification.valid(), verification.message());
        Path out = Path.of("target", "e2e-proof.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, proof.asString(), StandardCharsets.UTF_8);

        Map<String, Object> log = json(get("/sessions/" + sessionId + "/log"));
        List<?> entries = (List<?>) log.get("entries");
        assertEquals(3, entries.size());
        assertTrue(ChainVerifier.verifySegmentJson(entries, ChainEntry.GENESIS_PREV_HASH, ChainFormat.CHAIN_V2).valid());
        assertEquals(1, beacon.latestCalls.get(), "latest só no pedido");
    }

    @Test
    void resolutionUsesTargetRoundEvenWhenLatestIsFarAhead() {
        String sessionId = newSession();
        String rollId = requestRoll(sessionId, "alvo", "1d20").then().statusCode(201).extract().path("rollId");
        clock.set(Quicknet.timeOf(LATEST + 500));
        get("/rolls/" + rollId).then().statusCode(200)
                .body("result.round", equalTo((int) LATEST + 2))
                .body("result.randomness", equalTo(FakeBeaconClient.beacon(LATEST + 2).randomness()));
        assertEquals(List.of(LATEST + 2), beacon.roundCalls);
        assertEquals(1, beacon.latestCalls.get());
    }

    @Test
    void expiredWhileBeaconIsDownThenResolves() {
        String sessionId = newSession();
        String rollId = requestRoll(sessionId, "queda", "3d6").then().statusCode(201).extract().path("rollId");
        beacon.down = true;
        clock.set(Quicknet.timeOf(LATEST + 2).plusSeconds(30));
        get("/rolls/" + rollId).then().statusCode(200).body("status", equalTo("PENDING"));
        clock.set(Quicknet.timeOf(LATEST + 2).plusSeconds(61));
        get("/rolls/" + rollId).then().statusCode(200).body("status", equalTo("EXPIRED"));
        beacon.down = false;
        get("/rolls/" + rollId).then().statusCode(200).body("status", equalTo("RESOLVED"))
                .body("result.round", equalTo((int) LATEST + 2));
    }

    @Test
    void rejectsInvalidInputWithSpecErrorCodes() {
        String sessionId = newSession();
        requestRoll(sessionId, "", "1d20").then().statusCode(400).body("error", equalTo("SEED_EMPTY"));
        requestRoll(sessionId, "x".repeat(65), "1d20").then().statusCode(400).body("error", equalTo("SEED_TOO_LONG"));
        requestRoll(sessionId, "a\nb", "1d20").then().statusCode(400).body("error", equalTo("SEED_CONTROL_CHAR"));
        requestRoll(sessionId, "s", "1d7").then().statusCode(400).body("error", equalTo("UNSUPPORTED_DIE"));
        requestRoll(sessionId, "s", "21d6").then().statusCode(400).body("error", equalTo("DICE_COUNT_OUT_OF_RANGE"));
        requestRoll(sessionId, "s", "2d6+101").then().statusCode(400).body("error", equalTo("MODIFIER_OUT_OF_RANGE"));
        requestRoll(sessionId, "s", "abc").then().statusCode(400).body("error", equalTo("INVALID_EXPRESSION"));
        given().contentType(ContentType.JSON).body("{\"clientSeed\":\"s\"}").post("/sessions/" + sessionId + "/rolls")
                .then().statusCode(400).body("error", equalTo("INVALID_REQUEST"));
        given().contentType(ContentType.JSON).body(Map.of("name", "n".repeat(65))).post("/sessions")
                .then().statusCode(400).body("error", equalTo("INVALID_REQUEST"));
        get("/sessions/" + sessionId + "/log?limit=101").then().statusCode(400);
        assertEquals(0, beacon.latestCalls.get(), "entrada inválida nem chega a consultar o drand");
    }

    @Test
    void notFoundForUnknownOrMalformedIds() {
        requestRoll("0000000000000000", "s", "1d20").then().statusCode(404).body("error", equalTo("NOT_FOUND"));
        requestRoll("nao-e-um-id", "s", "1d20").then().statusCode(404);
        get("/rolls/0000000000000000").then().statusCode(404);
        get("/rolls/0000000000000000/proof").then().statusCode(404);
        get("/sessions/0000000000000000/log").then().statusCode(404);
        assertEquals(0, beacon.latestCalls.get());
    }

    @Test
    void beaconDownOnRequestIs503WithRetryAfter() {
        String sessionId = newSession();
        beacon.down = true;
        requestRoll(sessionId, "s", "1d20").then().statusCode(503)
                .header("Retry-After", "3").body("error", equalTo("BEACON_UNAVAILABLE"));
        Map<String, Object> log = json(get("/sessions/" + sessionId + "/log"));
        assertEquals(List.of(), log.get("entries"), "nada gravado sem round alvo");
    }

    @Test
    void largeLogIsSentWithContentLength() {
        // Regressão: acima de 8 KB a resposta saía em blocos com transfer-encoding vazio e nunca terminava.
        String sessionId = newSession();
        for (int i = 0; i < 12; i++) {
            requestRoll(sessionId, "grande-" + i, "4d6+1").then().statusCode(201);
        }
        clock.set(Quicknet.timeOf(LATEST + 2));
        get("/sessions/" + sessionId + "/log").then().statusCode(200); // resolve os pendentes
        Response log = given()
                .config(RestAssured.config().httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.socket.timeout", 10_000)))
                .get("/sessions/" + sessionId + "/log");
        log.then().statusCode(200).header("content-length", notNullValue());
        assertTrue(log.asByteArray().length > 8192, "o log do teste tem de passar de 8 KB");
        assertEquals(36, ((List<?>) json(log).get("entries")).size());
    }

    @Test
    void logPaginatesShowsPendingAndResolvesOnRead() {
        String sessionId = newSession();
        for (int i = 0; i < 3; i++) {
            requestRoll(sessionId, "p" + i, "1d6").then().statusCode(201);
        }
        Map<String, Object> page1 = json(get("/sessions/" + sessionId + "/log?limit=2"));
        assertEquals(2, ((List<?>) page1.get("entries")).size());
        assertEquals(2, page1.get("next"));
        Map<String, Object> page2 = json(get("/sessions/" + sessionId + "/log?after=2"));
        assertEquals(1, ((List<?>) page2.get("entries")).size());
        assertNull(page2.get("next"));

        clock.set(Quicknet.timeOf(LATEST + 2));
        Map<String, Object> resolved = json(get("/sessions/" + sessionId + "/log"));
        List<?> entries = (List<?>) resolved.get("entries");
        assertEquals(9, entries.size(), "3 REQUEST + 3 pares ROUND/RESULT resolvidos na leitura do log");
        assertTrue(ChainVerifier.verifySegmentJson(entries, ChainEntry.GENESIS_PREV_HASH, ChainFormat.CHAIN_V2).valid());
        assertEquals(9, ((Map<?, ?>) resolved.get("head")).get("seq"));
    }
}
