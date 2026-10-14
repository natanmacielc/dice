package io.github.natanmacielc.dice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiceRollerTest {

    private static final long TWO_32 = 1L << 32;
    private static final String R = "b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af3";

    /** Fonte de palavras fixa para testar as bordas da rejeição sem depender do SHA-256. */
    private static WordSource words(long... values) {
        ArrayDeque<Long> q = new ArrayDeque<>();
        Arrays.stream(values).forEach(q::add);
        return q::removeFirst;
    }

    @Test
    void limitIsLargestMultipleOfSides() {
        assertEquals(TWO_32, DiceRoller.limit(4));
        assertEquals(TWO_32, DiceRoller.limit(8));
        assertEquals(TWO_32 - 4, DiceRoller.limit(6));
        assertEquals(TWO_32 - 6, DiceRoller.limit(10));
        assertEquals(TWO_32 - 4, DiceRoller.limit(12));
        assertEquals(TWO_32 - 16, DiceRoller.limit(20));
        assertEquals(TWO_32 - 96, DiceRoller.limit(100));
    }

    @Test
    void acceptsValueJustBelowLimitAndRejectsLimitAndMax() {
        long limit = DiceRoller.limit(6);
        RollResult r = DiceRoller.roll(words(limit, TWO_32 - 1, limit - 1, 0, 5), DiceExpression.parse("3d6+2"));
        // limit-1 = 4294967291 -> % 6 = 5 -> 6 ; 0 -> 1 ; 5 -> 6
        assertEquals(List.of(6, 1, 6), r.dice());
        assertEquals(15, r.total());
        assertEquals(5, r.wordsConsumed());
        assertEquals(List.of(new Rejection(0, limit), new Rejection(1, TWO_32 - 1)), r.rejections());
    }

    @Test
    void d4AndD8NeverReject() {
        RollResult d4 = DiceRoller.roll(words(TWO_32 - 1), DiceExpression.parse("1d4"));
        assertEquals(List.of(4), d4.dice());
        assertEquals(List.of(), d4.rejections());
        RollResult d8 = DiceRoller.roll(words(TWO_32 - 1), DiceExpression.parse("1d8"));
        assertEquals(List.of(8), d8.dice());
        assertEquals(List.of(), d8.rejections());
    }

    @Test
    void firstWordComesFromBlockZeroBigEndian() throws Exception {
        byte[] block0 = MessageDigest.getInstance("SHA-256")
                .digest((R + "|seed|7|0").getBytes(StandardCharsets.UTF_8));
        long w0 = ((block0[0] & 0xffL) << 24) | ((block0[1] & 0xffL) << 16) | ((block0[2] & 0xffL) << 8) | (block0[3] & 0xffL);
        RollResult r = DiceRoller.roll(R, "seed", 7, "1d4");
        assertEquals((int) (w0 % 4) + 1, r.dice().get(0));
        assertEquals(1, r.wordsConsumed());
    }

    @Test
    void streamCrossesBlockBoundaryWithIncrementingCounter() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] block1 = sha.digest((R + "|seed|7|1").getBytes(StandardCharsets.UTF_8));
        long w8 = ((block1[0] & 0xffL) << 24) | ((block1[1] & 0xffL) << 16) | ((block1[2] & 0xffL) << 8) | (block1[3] & 0xffL);
        RollResult r = DiceRoller.roll(R, "seed", 7, "9d8");
        assertEquals((int) (w8 % 8) + 1, r.dice().get(8));
        assertEquals(9, r.wordsConsumed());
    }

    @Test
    void beaconCheckHashesSignatureBytes() {
        String sig = "83ad29e4c409f9470fc2ef02f90214df49e02b441a1a241a82d622d9f608ef98fd8b11a029f1bee9d9e83b45088abe72";
        assertEquals(true, BeaconCheck.randomnessMatchesSignature(sig, R));
        String tampered = "93" + sig.substring(2);
        assertEquals(false, BeaconCheck.randomnessMatchesSignature(tampered, R));
    }

    @Test
    void verifierAcceptsHonestResultAndRejectsTampering() {
        RollResult honest = DiceRoller.roll(R, "mesa", 3, "2d20+1");
        assertEquals(true, RollVerifier.verify(R, "mesa", 3, "2d20+1", honest.dice(), honest.total()).valid());

        int first = honest.dice().get(0);
        List<Integer> forged = List.of(first % 20 + 1, honest.dice().get(1));
        int forgedTotal = forged.get(0) + forged.get(1) + 1;
        assertEquals(false, RollVerifier.verify(R, "mesa", 3, "2d20+1", forged, forgedTotal).valid());
        assertEquals(false, RollVerifier.verify(R, "mesa", 3, "2d20+1", honest.dice(), honest.total() + 1).valid());    }
}
