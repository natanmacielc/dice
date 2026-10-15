package io.github.natanmacielc.dice.app.ledger;

import static io.github.natanmacielc.dice.app.ledger.LedgerTestSupport.json;
import static io.github.natanmacielc.dice.app.ledger.LedgerTestSupport.newSession;
import static io.github.natanmacielc.dice.app.ledger.LedgerTestSupport.requestRoll;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.natanmacielc.dice.app.beacon.MutableClock;
import io.github.natanmacielc.dice.core.Quicknet;
import io.github.natanmacielc.dice.core.chain.Payloads;
import io.github.natanmacielc.dice.core.chain.Player;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Lista de mesas por identidade (spec/ALGORITHM.md, seção 7.8): sincronizada entre aparelhos, leitura assinada. */
@QuarkusTest
@TestProfile(LedgerProfile.class)
@WithTestResource(LocalAwsResource.class)
class TablesTest {

    static final long LATEST = 5_000_000;
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

    /** Pedido assinado por um aparelho: sk/pk do aparelho, identidade e vínculos até ele. */
    static Response call(String sk, String pk, String identityKey, List<Player.Link> links, String action,
                         String sessionId, String cursor, Instant at) {
        String when = Payloads.formatInstant(at);
        String signature = Player.signRaw(sk, Player.tablesMessage(action, identityKey, when, sessionId, cursor));
        Map<String, Object> auth = Map.of("publicKey", pk, "identityKey", identityKey,
                "links", links.stream().map(Player.Link::toPayload).toList(), "at", when, "signature", signature);
        Map<String, Object> body = new HashMap<>(Map.of("auth", auth, "action", action));
        if (sessionId != null) {
            body.put("sessionId", sessionId);
        }
        if (cursor != null) {
            body.put("cursor", cursor);
        }
        return given().contentType(ContentType.JSON).body(body).post("/me/tables");
    }

    /** Uma identidade nova por teste, com um aparelho vinculado: a lista começa vazia. */
    record Identity(TestPlayers.Keys root, TestPlayers.Keys phone, List<Player.Link> links) {

        static Identity fresh() {
            TestPlayers.Keys root = TestPlayers.newKeys();
            TestPlayers.Keys phone = TestPlayers.newKeys();
            return new Identity(root, phone, List.of(Player.link(root.sk(), root.pk(), phone.pk())));
        }

        Response root(String action, String sessionId, String cursor, Instant at) {
            return call(root.sk(), root.pk(), root.pk(), List.of(), action, sessionId, cursor, at);
        }

        Response phone(String action, String sessionId, String cursor, Instant at) {
            return call(phone.sk(), phone.pk(), root.pk(), links, action, sessionId, cursor, at);
        }

        Player rollFromPhone(String sessionId, String seed, String expression) {
            return Player.sign(phone.sk(), phone.pk(), root.pk(), links, sessionId, seed, expression, "Ana",
                    TestPlayers.newRequestId());
        }

        Player rollFromRoot(String sessionId, String seed, String expression) {
            return Player.sign(root.sk(), root.pk(), sessionId, seed, expression, "Ana", TestPlayers.newRequestId());
        }
    }

    /** Todas as mesas da lista, seguindo o cursor. */
    List<String> listAll(Identity p, boolean fromPhone) {
        List<String> out = new ArrayList<>();
        String cursor = null;
        do {
            Response r = fromPhone ? p.phone("list", null, cursor, clock.instant()) : p.root("list", null, cursor, clock.instant());
            r.then().statusCode(200);
            Map<String, Object> page = json(r);
            ((List<?>) page.get("tables")).forEach(t -> out.add((String) ((Map<?, ?>) t).get("sessionId")));
            cursor = (String) page.get("next");
        } while (cursor != null);
        return out;
    }

    @Test
    void rollingAddsTheTableAndLinkedDevicesSeeTheSameList() {
        Identity ana = Identity.fresh();
        String first = newSession();
        String second = newSession();
        requestRoll(first, "s", "1d20", ana.rollFromRoot(first, "s", "1d20")).then().statusCode(201);
        clock.set(T0.plusSeconds(30));
        requestRoll(second, "s", "1d6", ana.rollFromPhone(second, "s", "1d6")).then().statusCode(201);

        // Mais recente primeiro, igual nos dois aparelhos.
        assertEquals(List.of(second, first), listAll(ana, false));
        assertEquals(List.of(second, first), listAll(ana, true));
        ana.root("list", null, null, clock.instant()).then()
                .body("tables[0].name", equalTo("Mesa do Dragão"))
                .body("tables[0].lastActiveAt", equalTo(Payloads.formatInstant(T0.plusSeconds(30))));
    }

    @Test
    void addAndRemoveWithoutRolling() {
        Identity ana = Identity.fresh();
        String table = newSession();
        ana.phone("add", table, null, clock.instant()).then().statusCode(200).body("table.sessionId", equalTo(table));
        assertEquals(List.of(table), listAll(ana, false));
        ana.root("remove", table, null, clock.instant()).then().statusCode(200);
        assertEquals(List.of(), listAll(ana, true));
        ana.root("add", "0000000000000000", null, clock.instant()).then().statusCode(404);
        ana.root("add", "nao-e-um-id", null, clock.instant()).then().statusCode(404);
    }

    @Test
    void paginatesWithCursor() {
        Identity bruno = Identity.fresh();
        List<String> tables = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            String t = newSession();
            clock.set(T0.plusSeconds(i));
            bruno.root("add", t, null, clock.instant()).then().statusCode(200);
            tables.add(0, t);
        }
        Map<String, Object> page1 = json(bruno.root("list", null, null, clock.instant()));
        assertEquals(20, ((List<?>) page1.get("tables")).size());
        String cursor = (String) page1.get("next");
        Map<String, Object> page2 = json(bruno.phone("list", null, cursor, clock.instant()));
        assertEquals(3, ((List<?>) page2.get("tables")).size());
        assertNull(page2.get("next"));
        List<String> all = new ArrayList<>();
        for (Map<String, Object> page : List.of(page1, page2)) {
            ((List<?>) page.get("tables")).forEach(t -> all.add((String) ((Map<?, ?>) t).get("sessionId")));
        }
        assertEquals(tables, all);
    }

    @Test
    void rejectsForgedOrStaleRequests() {
        String when = Payloads.formatInstant(clock.instant());
        // Assinatura feita para outra ação.
        String sig = Player.signRaw(TestPlayers.ANA_SK, Player.tablesMessage("remove", TestPlayers.ANA_PK, when, null, null));
        given().contentType(ContentType.JSON).body(Map.of("action", "list", "auth", Map.of("publicKey", TestPlayers.ANA_PK,
                        "identityKey", TestPlayers.ANA_PK, "links", List.of(), "at", when, "signature", sig)))
                .post("/me/tables").then().statusCode(401).body("error", equalTo("AUTH_INVALID"));
        // Bruno tentando ler a lista da Ana com a chave dele.
        call(TestPlayers.BRUNO_SK, TestPlayers.BRUNO_PK, TestPlayers.ANA_PK, List.of(), "list", null, null, clock.instant())
                .then().statusCode(401).body("error", equalTo("AUTH_INVALID"));
        // Bruno vincula o celular a si mesmo e tenta ler a lista da Ana.
        call(TestPlayers.PHONE_SK, TestPlayers.PHONE_PK, TestPlayers.ANA_PK,
                List.of(Player.link(TestPlayers.BRUNO_SK, TestPlayers.BRUNO_PK, TestPlayers.PHONE_PK)), "list", null, null,
                clock.instant()).then().statusCode(401).body("error", equalTo("AUTH_INVALID"));
        // Fora da janela de 5 minutos, nos dois sentidos.
        call(TestPlayers.ANA_SK, TestPlayers.ANA_PK, TestPlayers.ANA_PK, List.of(), "list", null, null,
                clock.instant().minusSeconds(301)).then().statusCode(401).body("error", equalTo("AUTH_EXPIRED"));
        call(TestPlayers.ANA_SK, TestPlayers.ANA_PK, TestPlayers.ANA_PK, List.of(), "list", null, null,
                clock.instant().plusSeconds(301)).then().statusCode(401).body("error", equalTo("AUTH_EXPIRED"));
        // Ação desconhecida ou corpo sem auth.
        Identity.fresh().root("drop", null, null, clock.instant())
                .then().statusCode(400).body("error", equalTo("INVALID_REQUEST"));
        given().contentType(ContentType.JSON).body(Map.of("action", "list")).post("/me/tables")
                .then().statusCode(401).body("error", equalTo("AUTH_INVALID"));
    }
}
