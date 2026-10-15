package io.github.natanmacielc.dice.core.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerTest {

    // RFC 8032, seção 7.1, TEST 1, 2 e 3.
    static final String SK1 = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
    static final String PK1 = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    static final String SIG1_EMPTY = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065"
            + "224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";
    static final String SK2 = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb";
    static final String PK2 = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    static final String SK3 = "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7";
    static final String PK3 = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025";
    static final String REQ = "0123456789abcdef0123456789abcdef";

    @Test
    void verifiesRfc8032Vector() {
        assertTrue(Player.verify(PK1, new byte[0], SIG1_EMPTY));
        assertEquals(SIG1_EMPTY, Player.signRaw(SK1, new byte[0]));
        assertFalse(Player.verify(PK2, new byte[0], SIG1_EMPTY));
    }

    @Test
    void messagesAreCanonicalJsonWithContext() {
        byte[] m = Player.message("kq1t9m3x7c2a8z5r", "mesa|\"x\"", "2d6+3", PK1, "Thorin", REQ);
        assertEquals("{\"clientSeed\":\"mesa|\\\"x\\\"\",\"context\":\"dice-player-v1\",\"expression\":\"2d6+3\","
                + "\"identityKey\":\"" + PK1 + "\",\"name\":\"Thorin\",\"requestId\":\"" + REQ
                + "\",\"sessionId\":\"kq1t9m3x7c2a8z5r\"}", new String(m, StandardCharsets.UTF_8));
        assertEquals("{\"context\":\"dice-device-v1\",\"deviceKey\":\"" + PK2 + "\",\"identityKey\":\"" + PK1 + "\"}",
                new String(Player.linkMessage(PK1, PK2), StandardCharsets.UTF_8));
    }

    @Test
    void tablesMessageOmitsAbsentFields() {
        assertEquals("{\"action\":\"list\",\"at\":\"2026-10-15T12:00:00.000Z\",\"context\":\"dice-tables-v1\","
                + "\"identityKey\":\"" + PK1 + "\"}",
                new String(Player.tablesMessage("list", PK1, "2026-10-15T12:00:00.000Z", null, null), StandardCharsets.UTF_8));
        assertEquals("{\"action\":\"add\",\"at\":\"t\",\"context\":\"dice-tables-v1\",\"cursor\":\"c\","
                + "\"identityKey\":\"" + PK1 + "\",\"sessionId\":\"s\"}",
                new String(Player.tablesMessage("add", PK1, "t", "s", "c"), StandardCharsets.UTF_8));
    }

    @Test
    void signedPlayerVerifiesAndAnyChangeBreaksIt() {
        Player p = Player.sign(SK1, PK1, "sess", "seed", "1d20", "Ana", REQ);
        assertNull(p.formatProblem());
        assertTrue(p.linksValid());
        assertTrue(p.signatureValid("sess", "seed", "1d20"));
        assertFalse(p.signatureValid("outra", "seed", "1d20"));
        assertFalse(p.signatureValid("sess", "seed2", "1d20"));
        assertFalse(p.signatureValid("sess", "seed", "1d20+1"));
        assertFalse(with(p, "Bia", REQ).signatureValid("sess", "seed", "1d20"));
        assertFalse(with(p, "Ana", REQ.replace('0', '1')).signatureValid("sess", "seed", "1d20"));
        assertFalse(new Player(PK2, "Ana", REQ, p.signature(), PK2, List.of()).signatureValid("sess", "seed", "1d20"));
    }

    @Test
    void linkedDevicesRollAsTheIdentity() {
        // PK1 é a identidade; PK2 foi vinculado por ela; PK3 foi vinculado por PK2.
        Player.Link toPhone = Player.link(SK1, PK1, PK2);
        Player.Link toTablet = Player.link(SK2, PK1, PK3);
        Player phone = Player.sign(SK2, PK2, PK1, List.of(toPhone), "sess", "s", "1d20", "Ana", REQ);
        Player tablet = Player.sign(SK3, PK3, PK1, List.of(toPhone, toTablet), "sess", "s", "1d20", "Ana", REQ);
        for (Player p : List.of(phone, tablet)) {
            assertNull(p.formatProblem());
            assertTrue(p.linksValid());
            assertTrue(p.signatureValid("sess", "s", "1d20"));
            assertEquals(Player.fingerprint(PK1), p.fingerprint(), "a impressão é a da identidade");
        }
        // Cadeia que não termina no aparelho, vínculo fora de ordem ou assinado por quem não está na cadeia.
        assertFalse(Player.linksValid(PK1, List.of(toPhone), PK3));
        assertFalse(Player.linksValid(PK1, List.of(toTablet), PK3));
        assertFalse(Player.linksValid(PK1, List.of(Player.link(SK3, PK1, PK2)), PK2));
        // Vínculo feito para outra identidade não serve.
        assertFalse(Player.linksValid(PK3, List.of(Player.link(SK3, PK1, PK2)), PK2));
        // Sem vínculos, identidade e aparelho são a mesma chave.
        assertFalse(Player.linksValid(PK1, List.of(), PK2));
    }

    @Test
    void identityIsCoveredByTheRequestSignature() {
        // Outro jogador (PK3) vincula o aparelho da Ana a si mesmo: os vínculos fecham, mas o pedido
        // foi assinado para a identidade PK1, então não pode ser reatribuído.
        Player.Link toPhone = Player.link(SK1, PK1, PK2);
        Player ana = Player.sign(SK2, PK2, PK1, List.of(toPhone), "sess", "s", "1d20", "Ana", REQ);
        Player stolen = new Player(PK2, "Ana", REQ, ana.signature(), PK3, List.of(Player.link(SK3, PK3, PK2)));
        assertTrue(stolen.linksValid());
        assertFalse(stolen.signatureValid("sess", "s", "1d20"));
    }

    @Test
    void invalidPointOrSignatureIsJustInvalid() {
        Player p = Player.sign(SK1, PK1, "sess", "seed", "1d20", "Ana", REQ);
        byte[] m = Player.message("sess", "seed", "1d20", PK1, "Ana", REQ);
        assertFalse(Player.verify("ff".repeat(32), m, p.signature()));
        assertFalse(Player.verify(PK1, m, "ff".repeat(64)));
    }

    @Test
    void formatProblems() {
        String sig = "ab".repeat(64);
        assertNull(player(PK1, "Ana", REQ, sig, PK1, List.of()).formatProblem());
        assertEquals("publicKey", field(player(PK1.toUpperCase(), "Ana", REQ, sig, PK1, List.of())));
        assertEquals("publicKey", field(player(PK1.substring(2), "Ana", REQ, sig, PK1, List.of())));
        assertEquals("publicKey", field(player(null, "Ana", REQ, sig, PK1, List.of())));
        assertEquals("name", field(player(PK1, "", REQ, sig, PK1, List.of())));
        assertEquals("name", field(player(PK1, "a".repeat(33), REQ, sig, PK1, List.of())));
        assertNull(player(PK1, "é".repeat(16), REQ, sig, PK1, List.of()).formatProblem());
        assertEquals("name", field(player(PK1, "é".repeat(16) + "a", REQ, sig, PK1, List.of())));
        assertEquals("name", field(player(PK1, "a\tb", REQ, sig, PK1, List.of())));
        assertEquals("name", field(player(PK1, "a\u007fb", REQ, sig, PK1, List.of())));
        assertEquals("name", field(player(PK1, "a\uD800", REQ, sig, PK1, List.of())));
        assertEquals("requestId", field(player(PK1, "Ana", REQ + "0", sig, PK1, List.of())));
        assertEquals("requestId", field(player(PK1, "Ana", REQ.toUpperCase(), sig, PK1, List.of())));
        assertEquals("signature", field(player(PK1, "Ana", REQ, sig.substring(2), PK1, List.of())));
        assertEquals("identityKey", field(player(PK1, "Ana", REQ, sig, null, List.of())));
        assertEquals("links", field(player(PK1, "Ana", REQ, sig, PK1, null)));
        assertEquals("links", field(player(PK1, "Ana", REQ, sig, PK1, Collections.nCopies(9, new Player.Link(PK2, sig)))));
        assertNull(player(PK1, "Ana", REQ, sig, PK1, Collections.nCopies(8, new Player.Link(PK2, sig))).formatProblem());
        assertEquals("links:", field(player(PK1, "Ana", REQ, sig, PK1, List.of(new Player.Link(PK2, "00")))));
    }

    @Test
    void fingerprintIsFirstFourBytesOfSha256OfKey() throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(HexFormat.of().parseHex(PK1));
        String hex = HexFormat.of().formatHex(digest, 0, 4);
        assertEquals(hex.substring(0, 4) + "-" + hex.substring(4), Player.fingerprint(PK1));
    }

    private static Player with(Player p, String name, String requestId) {
        return new Player(p.publicKey(), name, requestId, p.signature(), p.identityKey(), p.links());
    }

    // O construtor canônico do record aceita links null (vindo do JSON); List.copyOf não aceitaria.
    private static Player player(String pk, String name, String req, String sig, String id, List<Player.Link> links) {
        return new Player(pk, name, req, sig, id, links);
    }

    private static String field(Player p) {
        String problem = p.formatProblem();
        return problem == null ? null : problem.split(" ")[0];
    }
}
