package io.github.natanmacielc.dice.app.ledger;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/** IDs de mesa e de roll: 16 caracteres base32 Crockford minúsculo (80 bits aleatórios). */
public final class Ids {

    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();
    private static final Pattern FORMAT = Pattern.compile("[0-9a-hjkmnp-tv-z]{16}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String newId() {
        byte[] bytes = new byte[10];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(16);
        long buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET[(int) (buffer >>> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        return out.toString();
    }

    public static boolean isValid(String id) {
        return id != null && FORMAT.matcher(id).matches();
    }
}
