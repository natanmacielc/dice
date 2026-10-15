package io.github.natanmacielc.dice.app.api;

import io.github.natanmacielc.dice.app.ledger.RollRecord;
import io.github.natanmacielc.dice.app.ledger.RollService;
import io.github.natanmacielc.dice.app.ledger.SessionRecord;
import io.github.natanmacielc.dice.core.DiceRoller;
import io.github.natanmacielc.dice.core.Quicknet;
import io.github.natanmacielc.dice.core.chain.ChainEntry;
import io.github.natanmacielc.dice.core.chain.ChainFormat;
import io.github.natanmacielc.dice.core.chain.Payloads;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Corpos JSON das respostas. Mapas ordenados para a saída ficar estável e legível. */
final class Views {

    private Views() {
    }

    static Map<String, Object> session(SessionRecord s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", s.sessionId());
        if (s.name() != null) {
            m.put("name", s.name());
        }
        m.put("createdAt", s.createdAt());
        m.put("chainFormat", s.chainFormat().id());
        m.put("links", Map.of("log", "/sessions/" + s.sessionId() + "/log", "rolls", "/sessions/" + s.sessionId() + "/rolls"));
        return m;
    }

    static Map<String, Object> receipt(RollRecord r, ChainEntry request, String status) {
        Map<String, Object> m = rollBase(r, status);
        m.put("entry", Map.of("seq", request.seq(), "hash", request.hash()));
        m.put("links", links(r.rollId()));
        return m;
    }

    static Map<String, Object> roll(RollRecord r, String status) {
        Map<String, Object> m = rollBase(r, status);
        if (r.resolved()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dice", r.dice());
            result.put("modifier", r.modifier());
            result.put("total", r.total());
            result.put("round", r.round());
            result.put("randomness", r.randomness());
            result.put("signature", r.signature());
            m.put("result", result);
        }
        m.put("links", links(r.rollId()));
        return m;
    }

    /** Formato da prova: spec/ALGORITHM.md, seções 6.5 e 7.4. */
    static Map<String, Object> proof(RollService.Proof p) {
        RollRecord r = p.roll();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("algorithm", DiceRoller.ALGORITHM);
        m.put("chainFormat", p.chainFormat().id());
        Map<String, Object> drand = new LinkedHashMap<>();
        drand.put("network", Quicknet.NETWORK);
        drand.put("chainHash", Quicknet.CHAIN_HASH);
        m.put("drand", drand);
        Map<String, Object> beacon = new LinkedHashMap<>();
        beacon.put("round", r.round());
        beacon.put("randomness", r.randomness());
        beacon.put("signature", r.signature());
        m.put("beacon", beacon);
        Map<String, Object> roll = new LinkedHashMap<>();
        roll.put("rollId", r.rollId());
        roll.put("sessionId", r.sessionId());
        roll.put("nonce", r.nonce());
        roll.put("clientSeed", r.clientSeed());
        roll.put("expression", r.expression());
        roll.put("targetRound", r.targetRound());
        roll.put("requestedAt", r.requestedAt());
        roll.put("dice", r.dice());
        roll.put("modifier", r.modifier());
        roll.put("total", r.total());
        if (p.chainFormat() == ChainFormat.CHAIN_V2) {
            roll.put("player", r.player().toPayload());
        }
        m.put("roll", roll);
        m.put("entries", entries(p.entries()));
        return m;
    }

    static Map<String, Object> log(RollService.LogPage page) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", page.session().sessionId());
        m.put("chainFormat", page.session().chainFormat().id());
        m.put("head", Map.of("seq", page.session().headSeq(), "hash", page.session().headHash()));
        m.put("entries", entries(page.entries()));
        m.put("next", page.next());
        return m;
    }

    private static List<Map<String, Object>> entries(List<ChainEntry> entries) {
        return entries.stream().map(ChainEntry::toJson).toList();
    }

    private static Map<String, Object> rollBase(RollRecord r, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rollId", r.rollId());
        m.put("sessionId", r.sessionId());
        m.put("status", status);
        m.put("nonce", r.nonce());
        m.put("clientSeed", r.clientSeed());
        m.put("expression", r.expression());
        m.put("targetRound", r.targetRound());
        m.put("requestedAt", r.requestedAt());
        m.put("expectedAt", Payloads.formatInstant(Quicknet.timeOf(r.targetRound())));
        if (r.player() != null) {
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("name", r.player().name());
            player.put("identityKey", r.player().identityKey());
            player.put("publicKey", r.player().publicKey());
            player.put("fingerprint", r.player().fingerprint());
            m.put("player", player);
        }
        return m;
    }

    private static Map<String, Object> links(String rollId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("self", "/rolls/" + rollId);
        m.put("proof", "/rolls/" + rollId + "/proof");
        m.put("page", "/r/" + rollId);
        return m;
    }
}
