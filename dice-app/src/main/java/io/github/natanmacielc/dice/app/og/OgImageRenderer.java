package io.github.natanmacielc.dice.app.og;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Imagem Open Graph 1200x630 em Java2D headless. As fontes (Inter, OFL) vêm do jar, então não
 * depende de fontes do sistema (o Lambda não tem fontconfig).
 */
public final class OgImageRenderer {

    public static final int WIDTH = 1200;
    public static final int HEIGHT = 630;
    private static final int MARGIN = 80;

    private static final Color BG = new Color(0x14, 0x13, 0x12);
    private static final Color INK = new Color(0xF1, 0xEE, 0xE9);
    private static final Color MUTED = new Color(0xA3, 0x9D, 0x96);
    private static final Color ACCENT = new Color(0xA5, 0x8B, 0xFF);

    private static final Font BOLD;
    private static final Font REGULAR;

    static {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        configureFontsWithoutFontconfig();
        BOLD = load("/fonts/Inter-Bold.ttf");
        REGULAR = load("/fonts/Inter-Regular.ttf");
    }

    /**
     * No Linux sem fontconfig (imagem do AWS Lambda), o gerenciador de fontes do JDK falha com
     * "Fontconfig head is null" antes mesmo de carregar um TTF. Um fontconfig.properties no formato
     * do JDK (propriedade sun.awt.fontconfig) mapeando as fontes lógicas para a Inter resolve.
     * Precisa rodar antes do primeiro uso de fontes no processo.
     */
    private static void configureFontsWithoutFontconfig() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")
                || System.getProperty("sun.awt.fontconfig") != null) {
            return;
        }
        try {
            Path dir = Files.createTempDirectory("dice-fonts");
            Path regular = extract("/fonts/Inter-Regular.ttf", dir.resolve("Inter-Regular.ttf"));
            Path bold = extract("/fonts/Inter-Bold.ttf", dir.resolve("Inter-Bold.ttf"));
            StringBuilder p = new StringBuilder("version=1\n");
            for (String logical : new String[] {"serif", "sansserif", "monospaced", "dialog", "dialoginput"}) {
                p.append(logical).append(".plain.latin-1=Inter Regular\n");
                p.append(logical).append(".italic.latin-1=Inter Regular\n");
                p.append(logical).append(".bold.latin-1=Inter Bold\n");
                p.append(logical).append(".bolditalic.latin-1=Inter Bold\n");
            }
            p.append("sequence.allfonts=latin-1\n");
            p.append("filename.Inter_Regular=").append(regular).append('\n');
            p.append("filename.Inter_Bold=").append(bold).append('\n');
            p.append("awtfontpath.latin-1=").append(dir).append('\n');
            Path props = dir.resolve("fontconfig.properties");
            Files.writeString(props, p);
            System.setProperty("sun.awt.fontconfig", props.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível preparar as fontes", e);
        }
    }

    private static Path extract(String resource, Path target) throws IOException {
        try (InputStream in = OgImageRenderer.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("fonte ausente: " + resource);
            }
            Files.copy(in, target);
            return target;
        }
    }

    /** Conteúdo da imagem. Só texto que a Inter cobre; a clientSeed fica fora (pode ser qualquer Unicode). */
    public record Card(String expression, String total, String diceLine, String footer, String corner) {
    }

    private OgImageRenderer() {
    }

    public static byte[] render(Card card) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(BG);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setColor(ACCENT);
            g.fillRect(0, 0, 16, HEIGHT);

            int maxWidth = WIDTH - 2 * MARGIN;
            text(g, BOLD.deriveFont(30f), ACCENT, "DADO VERIFICÁVEL", MARGIN, 110, maxWidth);
            if (card.corner() != null) {
                Font f = REGULAR.deriveFont(24f);
                g.setFont(f);
                int w = g.getFontMetrics().stringWidth(card.corner());
                text(g, f, MUTED, card.corner(), WIDTH - MARGIN - w, 110, maxWidth);
            }
            text(g, BOLD.deriveFont(60f), INK, card.expression(), MARGIN, 210, maxWidth);
            text(g, BOLD.deriveFont(210f), INK, card.total(), MARGIN, 430, maxWidth);
            text(g, REGULAR.deriveFont(40f), MUTED, card.diceLine(), MARGIN, 500, maxWidth);
            text(g, REGULAR.deriveFont(26f), MUTED, card.footer(), MARGIN, 570, maxWidth);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Desenha o texto, cortando com reticências se passar da largura. */
    private static void text(Graphics2D g, Font font, Color color, String text, int x, int y, int maxWidth) {
        g.setFont(font);
        g.setColor(color);
        FontMetrics m = g.getFontMetrics();
        String s = text;
        if (m.stringWidth(s) > maxWidth) {
            while (!s.isEmpty() && m.stringWidth(s + "…") > maxWidth) {
                s = s.substring(0, s.length() - 1);
            }
            s = s + "…";
        }
        g.drawString(s, x, y);
    }

    private static Font load(String resource) {
        try (InputStream in = OgImageRenderer.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("fonte ausente: " + resource);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException | FontFormatException e) {
            throw new IllegalStateException("fonte ilegível: " + resource, e);
        }
    }

    public static Card pendingCard() {
        return new Card("Rolagem pendente", "?", "aguardando o round do drand", "drand quicknet · resultado verificável", null);
    }

    public static Card defaultCard() {
        return new Card("Rolagens que qualquer um confere", "d20", "o resultado vem do drand e é verificável",
                "drand quicknet · sem como prever, escolher ou reescrever", null);
    }

    /**
     * Ferramenta: gera as imagens estáticas (web/static/og-pending.png e og-default.png) e uma de exemplo.
     * Também serve para conferir a renderização na imagem do Lambda (sem fontconfig).
     */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(dir);
        Files.write(dir.resolve("og-pending.png"), render(pendingCard()));
        Files.write(dir.resolve("og-default.png"), render(defaultCard()));
        Files.write(dir.resolve("og-sample.png"), render(new Card("2d6+3", "12", "dados 6 · 3",
                "drand quicknet · round 1000002 · exemplo.dev/r/hh66b8sgzr4er3hg", "RESULT 5f1c9a2e")));
        System.out.println("PNGs gerados em " + dir.toAbsolutePath());
    }
}
