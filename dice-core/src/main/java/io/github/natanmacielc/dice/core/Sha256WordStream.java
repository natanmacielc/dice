package io.github.natanmacielc.dice.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * bloco(c) = SHA-256(utf8(randomness + "|" + clientSeed + "|" + nonce + "|" + c)), c = 0, 1, 2...
 * Cada bloco rende 8 palavras de 4 bytes big-endian sem sinal, lidas em ordem.
 */
final class Sha256WordStream implements WordSource {

    private static final int WORDS_PER_BLOCK = 8;

    private final MessageDigest sha256;
    private final byte[] prefix;
    private long counter;
    private byte[] block;
    private int position = WORDS_PER_BLOCK;

    Sha256WordStream(Randomness randomness, ClientSeed seed, long nonce) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes((randomness.hex() + "|").getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(seed.utf8Unsafe());
        out.writeBytes(("|" + Nonce.check(nonce) + "|").getBytes(StandardCharsets.US_ASCII));
        this.prefix = out.toByteArray();
        try {
            this.sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    @Override
    public long next() {
        if (position == WORDS_PER_BLOCK) {
            sha256.update(prefix);
            sha256.update(Long.toString(counter++).getBytes(StandardCharsets.US_ASCII));
            block = sha256.digest();
            position = 0;
        }
        int i = 4 * position++;
        return ((block[i] & 0xffL) << 24) | ((block[i + 1] & 0xffL) << 16)
                | ((block[i + 2] & 0xffL) << 8) | (block[i + 3] & 0xffL);
    }
}
