package io.github.natanmacielc.dice.app.ledger;

import static io.github.natanmacielc.dice.app.ledger.LedgerTestSupport.json;
import static io.github.natanmacielc.dice.app.ledger.LedgerTestSupport.newSession;
import static io.github.natanmacielc.dice.app.ledger.LedgerTestSupport.requestRoll;
import static io.restassured.RestAssured.get;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.natanmacielc.dice.app.beacon.MutableClock;
import io.github.natanmacielc.dice.core.Quicknet;
import io.github.natanmacielc.dice.core.chain.ChainEntry;
import io.github.natanmacielc.dice.core.chain.ChainFormat;
import io.github.natanmacielc.dice.core.chain.ChainVerification;
import io.github.natanmacielc.dice.core.chain.ChainVerifier;
import io.github.natanmacielc.dice.core.chain.Player;
import io.github.natanmacielc.dice.core.chain.ProofVerifier;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** chain-v2: todo roll é assinado pela chave do jogador (spec/ALGORITHM.md, seção 7). */
@QuarkusTest
@TestProfile(LedgerProfile.class)
@WithTestResource(LocalAwsResource.class)
class PlayerIdentityTest {

    static final long LATEST = 3_000_000;
    static final Instant T0 = Quicknet.timeOf(LATEST).plusSeconds(1);

    @Inject
    MutableClock clock;

    @Inject
    FakeBeaconClient beacon;

    @Inject
    DynamoDbClient dynamo;

    @Inject
    DynamoConfig dynamoConfig;

    @BeforeEach
    void reset() {
        clock.set(T0);
        beacon.reset();
    }

    @Test
    void signedRollShowsPlayerAndProofIsChainV2() {
        String sessionId = newSession();
        Player bruno = TestPlayers.bruno(sessionId, "machado", " 1d20 + 5 ");
        Response created = requestRoll(sessionId, "machado", " 1d20 + 5 ", bruno);
        created.then().statusCode(201)
                .body("player.name", equalTo("Bruno"))
                .body("player.publicKey", equalTo(TestPlayers.BRUNO_PK))
                .body("player.fingerprint", equalTo(Player.fingerprint(TestPlayers.BRUNO_PK)));
        String rollId = created.path("rollId");

        clock.set(Quicknet.timeOf(LATEST + 2));
        get("/api/rolls/" + rollId).then().statusCode(200).body("status", equalTo("RESOLVED"))
                .body("player.name", equalTo("Bruno"));

        Map<String, Object> proof = json(get("/api/rolls/" + rollId + "/proof"));
        assertEquals("chain-v2", proof.get("chainFormat"));
        assertEquals(bruno.toPayload(), ((Map<?, ?>) proof.get("roll")).get("player"));
        ChainVerification v = ProofVerifier.verify(proof);
        assertTrue(v.valid(), v.message());

        Map<String, Object> log = json(get("/api/sessions/" + sessionId + "/log"));
        assertEquals("chain-v2", log.get("chainFormat"));
        List<?> entries = (List<?>) log.get("entries");
        assertTrue(ChainVerifier.verifySegmentJson(entries, ChainEntry.GENESIS_PREV_HASH, ChainFormat.CHAIN_V2)
                .valid());

        get("/r/" + rollId).then().statusCode(200)
                .body(containsString("por <strong>Bruno</strong>"))
                .body(containsString(Player.fingerprint(TestPlayers.BRUNO_PK)))
                .body(containsString("Rolado por Bruno ("));
    }

    @Test
    void rejectsMissingMalformedOrForgedPlayerBeforeAskingDrand() {
        String sessionId = newSession();
        requestRoll(sessionId, "s", "1d20", null).then().statusCode(400).body("error", equalTo("PLAYER_INVALID"));

        Player ok = TestPlayers.ana(sessionId, "s", "1d20");
        requestRoll(sessionId, "s", "1d20", copy(ok, ok.publicKey(), "", ok.requestId(), ok.identityKey()))
                .then().statusCode(400).body("error", equalTo("PLAYER_INVALID"));
        requestRoll(sessionId, "s", "1d20", copy(ok, ok.publicKey(), "a".repeat(33), ok.requestId(), ok.identityKey()))
                .then().statusCode(400).body("error", equalTo("PLAYER_INVALID"));
        requestRoll(sessionId, "s", "1d20", copy(ok, ok.publicKey(), "Ana", "xyz", ok.identityKey()))
                .then().statusCode(400).body("error", equalTo("PLAYER_INVALID"));

        // Assinado para outra mesa, outra seed, outro apelido ou outra chave: não confere.
        requestRoll(sessionId, "s", "1d20", TestPlayers.ana("0000000000000000", "s", "1d20"))
                .then().statusCode(400).body("error", equalTo("PLAYER_SIGNATURE_INVALID"));
        requestRoll(sessionId, "s", "1d20", TestPlayers.ana(sessionId, "outra", "1d20"))
                .then().statusCode(400).body("error", equalTo("PLAYER_SIGNATURE_INVALID"));
        requestRoll(sessionId, "s", "1d20", copy(ok, ok.publicKey(), "Bruno", ok.requestId(), ok.identityKey()))
                .then().statusCode(400).body("error", equalTo("PLAYER_SIGNATURE_INVALID"));
        requestRoll(sessionId, "s", "1d20", copy(ok, TestPlayers.BRUNO_PK, "Ana", ok.requestId(), TestPlayers.BRUNO_PK))
                .then().statusCode(400).body("error", equalTo("PLAYER_SIGNATURE_INVALID"));

        // A assinatura cobre a expressão canônica; assinar o texto cru não vale.
        Player raw = Player.sign(TestPlayers.ANA_SK, TestPlayers.ANA_PK, sessionId, "s", "1D20", "Ana",
                TestPlayers.newRequestId());
        requestRoll(sessionId, "s", "1D20", raw)
                .then().statusCode(400).body("error", equalTo("PLAYER_SIGNATURE_INVALID"));

        assertEquals(0, beacon.latestCalls.get(), "nada disso chega a consultar o drand");
        assertEquals(List.of(), json(get("/api/sessions/" + sessionId + "/log")).get("entries"));
    }

    @Test
    void linkedDeviceRollsAsTheIdentity() {
        String sessionId = newSession();
        Player phone = TestPlayers.anaPhone(sessionId, "celular", "1d20");
        String rollId = requestRoll(sessionId, "celular", "1d20", phone).then().statusCode(201)
                .body("player.identityKey", equalTo(TestPlayers.ANA_PK))
                .body("player.publicKey", equalTo(TestPlayers.PHONE_PK))
                .body("player.fingerprint", equalTo(Player.fingerprint(TestPlayers.ANA_PK)))
                .extract().path("rollId");

        // Vínculo que não fecha no aparelho, ou identidade trocada: recusado antes do drand.
        Player shortChain = new Player(phone.publicKey(), "Ana", TestPlayers.newRequestId(), phone.signature(),
                TestPlayers.ANA_PK, List.of());
        requestRoll(sessionId, "celular", "1d20", shortChain)
                .then().statusCode(400).body("error", equalTo("PLAYER_SIGNATURE_INVALID"));
        Player stolen = new Player(phone.publicKey(), "Ana", phone.requestId(), phone.signature(), TestPlayers.BRUNO_PK,
                List.of(Player.link(TestPlayers.BRUNO_SK, TestPlayers.BRUNO_PK, TestPlayers.PHONE_PK)));
        requestRoll(sessionId, "celular", "1d20", stolen)
                .then().statusCode(400).body("error", equalTo("PLAYER_SIGNATURE_INVALID"));

        clock.set(Quicknet.timeOf(LATEST + 2));
        Map<String, Object> proof = json(get("/api/rolls/" + rollId + "/proof"));
        assertTrue(ProofVerifier.verify(proof).valid());
        get("/r/" + rollId).then().statusCode(200)
                .body(containsString(Player.fingerprint(TestPlayers.ANA_PK)))
                .body(containsString("Aparelho vinculado"));
    }

    @Test
    void replayedRequestIsRejected() {
        String sessionId = newSession();
        Player signed = TestPlayers.ana(sessionId, "uma-vez", "2d6");
        requestRoll(sessionId, "uma-vez", "2d6", signed).then().statusCode(201);
        requestRoll(sessionId, "uma-vez", "2d6", signed)
                .then().statusCode(409).body("error", equalTo("REQUEST_REPLAY"));
        List<?> entries = (List<?>) json(get("/api/sessions/" + sessionId + "/log")).get("entries");
        assertEquals(1, entries.size(), "o pedido reaproveitado não entra na cadeia");
    }

    @Test
    void legacyTableIsReadOnlyButStillVerifiable() {
        String sessionId = newSession();
        String rollId = requestRoll(sessionId, "antigo", "1d6").then().statusCode(201).extract().path("rollId");
        clock.set(Quicknet.timeOf(LATEST + 2));
        get("/api/rolls/" + rollId).then().statusCode(200).body("status", equalTo("RESOLVED"));
        makeLegacy(sessionId, rollId);

        requestRoll(sessionId, "novo", "1d6").then().statusCode(409).body("error", equalTo("TABLE_READ_ONLY"));

        Map<String, Object> log = json(get("/api/sessions/" + sessionId + "/log"));
        assertEquals("chain-v1", log.get("chainFormat"));
        assertEquals(3, ((List<?>) log.get("entries")).size());
        Map<String, Object> proof = json(get("/api/rolls/" + rollId + "/proof"));
        assertEquals("chain-v1", proof.get("chainFormat"));
        assertFalse(((Map<?, ?>) proof.get("roll")).containsKey("player"));
        assertTrue(ProofVerifier.verify(proof).valid());
        get("/api/rolls/" + rollId).then().statusCode(200).body("player", equalTo(null));
        get("/r/" + rollId).then().statusCode(200).body(not(containsString("roll-player")));
    }

    private static Player copy(Player p, String publicKey, String name, String requestId, String identityKey) {
        return new Player(publicKey, name, requestId, p.signature(), identityKey, p.links());
    }

    /** Simula uma mesa e um roll gravados antes da chain-v2: sem chainFormat na mesa e sem player no roll. */
    private void makeLegacy(String sessionId, String rollId) {
        remove(LedgerRepository.sessionPk(sessionId), "chainFormat");
        remove(LedgerRepository.rollPk(rollId), "player");
    }

    private void remove(String pk, String attribute) {
        dynamo.updateItem(r -> r.tableName(dynamoConfig.table())
                .key(Map.of("PK", AttributeValue.fromS(pk), "SK", AttributeValue.fromS("META")))
                .updateExpression("REMOVE " + attribute));
    }
}
