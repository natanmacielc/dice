package io.github.natanmacielc.dice.app.ledger;

import io.github.natanmacielc.dice.core.DiceException;
import io.github.natanmacielc.dice.core.DiceExpression;
import io.github.natanmacielc.dice.core.chain.Player;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jogadores de teste com as chaves do RFC 8032 (TEST 1, 2 e 3). Nunca use estas chaves de verdade. O
 * "celular da Ana" (TEST 3) é vinculado à identidade da Ana.
 */
final class TestPlayers {

    static final String ANA_SK = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
    static final String ANA_PK = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    static final String BRUNO_SK = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb";
    static final String BRUNO_PK = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    static final String PHONE_SK = "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7";
    static final String PHONE_PK = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025";

    private TestPlayers() {
    }

    /** Ana assina o pedido como o navegador faria: expressão canônica e requestId novo. */
    static Player ana(String sessionId, String seed, String expression) {
        return Player.sign(ANA_SK, ANA_PK, sessionId, seed, canonical(expression), "Ana", newRequestId());
    }

    static Player anaPhone(String sessionId, String seed, String expression) {
        return Player.sign(PHONE_SK, PHONE_PK, ANA_PK, List.of(Player.link(ANA_SK, ANA_PK, PHONE_PK)), sessionId, seed,
                canonical(expression), "Ana", newRequestId());
    }

    static Player bruno(String sessionId, String seed, String expression) {
        return Player.sign(BRUNO_SK, BRUNO_PK, sessionId, seed, canonical(expression), "Bruno", newRequestId());
    }

    static String newRequestId() {
        byte[] b = new byte[16];
        ThreadLocalRandom.current().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    // Expressão inválida vai crua: o servidor a recusa antes de olhar a assinatura.
    private static String canonical(String expression) {
        try {
            return DiceExpression.parse(expression).canonical();
        } catch (DiceException e) {
            return expression;
        }
    }
}
