package io.github.natanmacielc.dice.app.ledger;

import static io.restassured.RestAssured.get;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

import io.github.natanmacielc.dice.app.beacon.MutableClock;
import io.github.natanmacielc.dice.core.Quicknet;
import io.github.natanmacielc.dice.core.chain.Player;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Repasse do pareamento de aparelhos (spec/ALGORITHM.md, seção 7.7). Os MACs só os aparelhos conferem. */
@QuarkusTest
@TestProfile(LedgerProfile.class)
@WithTestResource(LocalAwsResource.class)
class PairingTest {

    static final String MAC = "ab".repeat(32);

    @Inject
    MutableClock clock;

    @BeforeEach
    void reset() {
        clock.set(Quicknet.timeOf(4_000_000));
    }

    static String create() {
        return given().contentType(ContentType.JSON).post("/api/pairings").then().statusCode(201)
                .body("$", hasKey("expiresAt"))
                .extract().path("pairingId");
    }

    static Response post(String path, Object body) {
        return given().contentType(ContentType.JSON).body(body).post(path);
    }

    static Map<String, Object> identity(String identityKey, List<Player.Link> links, String name) {
        return Map.of("identityKey", identityKey, "links", links.stream().map(Player.Link::toPayload).toList(),
                "name", name, "mac", MAC);
    }

    @Test
    void fullFlowRelaysDeviceKeyAndLinks() {
        String id = create();
        get("/api/pairings/" + id).then().statusCode(200).body("$", not(hasKey("device"))).body("$", not(hasKey("identity")));

        // A identidade não pode mandar vínculos antes de o aparelho novo aparecer.
        Player.Link link = Player.link(TestPlayers.ANA_SK, TestPlayers.ANA_PK, TestPlayers.PHONE_PK);
        post("/api/pairings/" + id + "/identity", identity(TestPlayers.ANA_PK, List.of(link), "Ana"))
                .then().statusCode(409).body("error", equalTo("PAIRING_WAITING_DEVICE"));

        post("/api/pairings/" + id + "/device", Map.of("deviceKey", TestPlayers.PHONE_PK, "mac", MAC))
                .then().statusCode(200).body("device.deviceKey", equalTo(TestPlayers.PHONE_PK));
        post("/api/pairings/" + id + "/device", Map.of("deviceKey", TestPlayers.BRUNO_PK, "mac", MAC))
                .then().statusCode(409).body("error", equalTo("PAIRING_STEP_DONE"));

        // Vínculo para outra chave, ou de outra identidade: não fecha no aparelho registrado.
        post("/api/pairings/" + id + "/identity", identity(TestPlayers.ANA_PK,
                List.of(Player.link(TestPlayers.ANA_SK, TestPlayers.ANA_PK, TestPlayers.BRUNO_PK)), "Ana"))
                .then().statusCode(400).body("error", equalTo("PAIRING_LINK_INVALID"));
        post("/api/pairings/" + id + "/identity", identity(TestPlayers.BRUNO_PK, List.of(link), "Ana"))
                .then().statusCode(400).body("error", equalTo("PAIRING_LINK_INVALID"));
        post("/api/pairings/" + id + "/identity", identity(TestPlayers.ANA_PK, List.of(), "Ana"))
                .then().statusCode(400).body("error", equalTo("PAIRING_INVALID"));

        post("/api/pairings/" + id + "/identity", identity(TestPlayers.ANA_PK, List.of(link), "Ana"))
                .then().statusCode(200)
                .body("identity.identityKey", equalTo(TestPlayers.ANA_PK))
                .body("identity.links[0].deviceKey", equalTo(TestPlayers.PHONE_PK))
                .body("identity.name", equalTo("Ana"));
        post("/api/pairings/" + id + "/identity", identity(TestPlayers.ANA_PK, List.of(link), "Ana"))
                .then().statusCode(409).body("error", equalTo("PAIRING_STEP_DONE"));
        get("/api/pairings/" + id).then().statusCode(200).body("identity.mac", equalTo(MAC));
    }

    @Test
    void rejectsMalformedInputAndExpiresAfterTenMinutes() {
        String id = create();
        post("/api/pairings/" + id + "/device", Map.of("deviceKey", "xyz", "mac", MAC))
                .then().statusCode(400).body("error", equalTo("PAIRING_INVALID"));
        get("/api/pairings/0000000000000000").then().statusCode(404);
        get("/api/pairings/nao-e-um-id").then().statusCode(404);

        clock.set(Quicknet.timeOf(4_000_000).plusSeconds(601));
        get("/api/pairings/" + id).then().statusCode(404);
        post("/api/pairings/" + id + "/device", Map.of("deviceKey", TestPlayers.PHONE_PK, "mac", MAC))
                .then().statusCode(404);
    }
}
