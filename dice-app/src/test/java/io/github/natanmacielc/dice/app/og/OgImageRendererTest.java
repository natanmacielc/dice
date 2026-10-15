package io.github.natanmacielc.dice.app.og;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class OgImageRendererTest {

    static int litPixels(BufferedImage img, int x0, int y0, int x1, int y1) {
        int bg = img.getRGB(600, 620);
        int lit = 0;
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                if (img.getRGB(x, y) != bg) {
                    lit++;
                }
            }
        }
        return lit;
    }

    @Test
    void rendersOpenGraphSizedPngWithText() throws Exception {
        byte[] png = OgImageRenderer.render(new OgImageRenderer.Card("2d6+3", "12", "dados 6 · 3",
                "drand quicknet · round 1000002 · dados.exemplo.dev/r/hh66b8sgzr4er3hg", "RESULT 5f1c9a2e"));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(OgImageRenderer.WIDTH, img.getWidth());
        assertEquals(OgImageRenderer.HEIGHT, img.getHeight());
        assertTrue(litPixels(img, 80, 260, 600, 440) > 5_000, "o total grande foi desenhado");
        assertTrue(litPixels(img, 80, 160, 600, 215) > 500, "a expressão foi desenhada");
    }

    @Test
    void longLinesAreTruncatedInsteadOfOverflowing() throws Exception {
        String dice = "dados " + "100 · ".repeat(20);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(OgImageRenderer.render(
                new OgImageRenderer.Card("20d100+100", "1100", dice, "rodapé", null))));
        assertEquals(0, litPixels(img, OgImageRenderer.WIDTH - 60, 440, OgImageRenderer.WIDTH, 510),
                "nada passa da margem direita");
    }
}
