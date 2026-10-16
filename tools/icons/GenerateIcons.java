import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Ícones do site: um d20 visto de frente, no gradiente da marca. O desenho é o mesmo de web/favicon.svg
 * (viewBox 64x64); mudou um, mude o outro. Saída reprodutível: rodar duas vezes dá os mesmos bytes.
 *
 * Uso: java tools/icons/GenerateIcons.java web
 * Grava favicon.ico (PNGs de 16, 32 e 48), apple-touch-icon.png (180, fundo opaco) e icon-512.png.
 */
public class GenerateIcons {

    static final Color MAGENTA = new Color(0xFF3D9A);
    static final Color VIOLET = new Color(0xA86BFF);
    static final Color BG = new Color(0x0A0616);
    static final Path FONT = Path.of("dice-app/src/main/resources/fonts/Inter-Bold.ttf");

    // Vértices no viewBox 64x64: hexágono externo e o triângulo da face central.
    static final double[] T = {32, 3}, UR = {57.1, 17.5}, LR = {57.1, 46.5}, B = {32, 61}, LL = {6.9, 46.5},
            UL = {6.9, 17.5}, A = {32, 16}, BL = {16.5, 42.5}, BR = {47.5, 42.5};

    /** Faces e o sombreado de cada uma (alfa positivo clareia, negativo escurece). */
    static final double[][][] FACES = {
            {A, BR, BL}, {T, UL, A}, {T, A, UR}, {UL, BL, A}, {UR, A, BR},
            {UL, LL, BL}, {UR, BR, LR}, {BL, B, BR}, {LL, B, BL}, {BR, B, LR}};
    static final double[] SHADE = {.30, .16, .10, .04, -.02, -.12, -.16, -.22, -.30, -.32};

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "web");
        Font bold;
        try (InputStream in = Files.newInputStream(FONT)) {
            bold = Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (FontFormatException e) {
            throw new IOException(e);
        }
        byte[][] ico = {png(die(16, null)), png(die(32, null)), png(die(48, bold))};
        Files.write(out.resolve("favicon.ico"), ico(new int[] {16, 32, 48}, ico));
        Files.write(out.resolve("apple-touch-icon.png"), png(tile(180, bold)));
        Files.write(out.resolve("icon-512.png"), png(tile(512, bold)));
    }

    /** O dado sozinho, fundo transparente. O "20" só aparece quando cabe (fonte != null). */
    static BufferedImage die(int size, Font font) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        drawDie(g, size / 64.0, 0, 0, font);
        g.dispose();
        return img;
    }

    /** Ícone de app: fundo escuro com brilho na cor da marca e o dado a 72%. */
    static BufferedImage tile(int size, Font font) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        g.setColor(BG);
        g.fillRect(0, 0, size, size);
        g.setPaint(new RadialGradientPaint(size / 2f, size / 2f, size * .55f, new float[] {0f, 1f},
                new Color[] {new Color(0xA8, 0x6B, 0xFF, 110), new Color(0xA8, 0x6B, 0xFF, 0)}));
        g.fillRect(0, 0, size, size);
        double scale = size * .72 / 64.0;
        double offset = (size - 64 * scale) / 2;
        drawDie(g, scale, offset, offset, font);
        g.dispose();
        return img;
    }

    static void drawDie(Graphics2D g, double scale, double dx, double dy, Font font) {
        AffineTransform saved = g.getTransform();
        g.translate(dx, dy);
        g.scale(scale, scale);
        Path2D hex = poly(T, UR, LR, B, LL, UL);
        g.setPaint(new GradientPaint(8, 4, MAGENTA, 56, 60, VIOLET));
        g.fill(hex);
        for (int i = 0; i < FACES.length; i++) {
            int alpha = (int) Math.round(Math.abs(SHADE[i]) * 255);
            g.setColor(SHADE[i] >= 0 ? new Color(255, 255, 255, alpha) : new Color(20, 6, 40, alpha));
            g.fill(poly(FACES[i]));
        }
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 120));
        for (double[][] face : FACES) {
            g.draw(poly(face));
        }
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 200));
        g.draw(hex);
        if (font != null) {
            g.setFont(font.deriveFont(Font.BOLD, 11.5f));
            g.setColor(Color.WHITE);
            FontMetrics fm = g.getFontMetrics();
            String text = "20";
            float x = 32 - fm.stringWidth(text) / 2f;
            g.drawString(text, x, 38f);
        }
        g.setTransform(saved);
    }

    static Path2D poly(double[]... pts) {
        Path2D p = new Path2D.Double();
        p.moveTo(pts[0][0], pts[0][1]);
        for (int i = 1; i < pts.length; i++) {
            p.lineTo(pts[i][0], pts[i][1]);
        }
        p.closePath();
        return p;
    }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** ICO com PNG embutido (aceito por todos os navegadores atuais): ICONDIR, entradas de 16 bytes e os PNGs. */
    static byte[] ico(int[] sizes, byte[][] pngs) {
        int header = 6 + 16 * sizes.length;
        int total = header;
        for (byte[] p : pngs) {
            total += p.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = header;
        for (int i = 0; i < sizes.length; i++) {
            buf.put((byte) (sizes[i] >= 256 ? 0 : sizes[i])).put((byte) (sizes[i] >= 256 ? 0 : sizes[i]));
            buf.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            buf.putInt(pngs[i].length).putInt(offset);
            offset += pngs[i].length;
        }
        for (byte[] p : pngs) {
            buf.put(p);
        }
        return buf.array();
    }
}
