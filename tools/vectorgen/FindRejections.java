import io.github.natanmacielc.dice.core.DiceRoller;
import io.github.natanmacielc.dice.core.RollResult;
import java.util.OptionalLong;
import java.util.stream.LongStream;

/**
 * Busca seeds que provocam rejeição de viés real (v >= limite) num roll de 20 dados.
 * Roda uma vez; as seeds encontradas ficam fixas em GenerateVectors.java.
 *
 * Uso: java -cp dice-core/target/classes tools/vectorgen/FindRejections.java
 */
public class FindRejections {

    static final String RANDOMNESS = "b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af3";
    static final long NONCE = 1;

    public static void main(String[] args) {
        for (int sides : new int[] {100, 20, 10, 12, 6}) {
            long start = System.nanoTime();
            String expr = "20d" + sides;
            OptionalLong found = LongStream.range(0, 2_000_000_000L).parallel()
                    .filter(i -> !DiceRoller.roll(RANDOMNESS, seed(sides, i), NONCE, expr).rejections().isEmpty())
                    .findFirst();
            long i = found.orElseThrow();
            RollResult r = DiceRoller.roll(RANDOMNESS, seed(sides, i), NONCE, expr);
            System.out.printf("d%d seed=%s rejections=%s (%.1fs)%n", sides, seed(sides, i), r.rejections(),
                    (System.nanoTime() - start) / 1e9);
        }
    }

    static String seed(int sides, long i) {
        return "rej-d" + sides + "-" + i;
    }
}
