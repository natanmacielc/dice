package io.github.natanmacielc.dice.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** Invariante 6: no drand, randomness = SHA-256(bytes da assinatura). Não verifica a assinatura BLS. */
public final class BeaconCheck {

    private static final Pattern HEX_BYTES = Pattern.compile("(?:[0-9a-f]{2})+");

    private BeaconCheck() {
    }

    public static boolean randomnessMatchesSignature(String signatureHex, String randomnessHex) {
        Objects.requireNonNull(signatureHex, "signatureHex");
        Randomness randomness = Randomness.of(randomnessHex);
        if (!HEX_BYTES.matcher(signatureHex).matches()) {
            throw new DiceException(ErrorCode.SIGNATURE_FORMAT, "assinatura deve ser hex minúsculo com tamanho par");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(HexFormat.of().parseHex(signatureHex));
            return HexFormat.of().formatHex(digest).equals(randomness.hex());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
