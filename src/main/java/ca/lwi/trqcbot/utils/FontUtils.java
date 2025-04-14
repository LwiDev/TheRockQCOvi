package ca.lwi.trqcbot.utils;

import java.awt.*;
import java.util.Objects;

public class FontUtils {

    public static Font emojiFont;

    public static void loadFonts() {
        try {
            emojiFont = Font.createFont(Font.TRUETYPE_FONT, Objects.requireNonNull(FontUtils.class.getClassLoader().getResourceAsStream("fonts/Symbola.ttf")));
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(emojiFont);
            System.out.println("✅ Police emoji chargée.");
        } catch (Exception e) {
            System.err.println("❌ Échec chargement police emoji : " + e.getMessage());
        }
    }

    public static Font calculateOptimalNameFont(Graphics2D g, String string, int maxWidth, int initialFontSize) {
        Font font = new Font("Arial", Font.BOLD, initialFontSize);
        FontMetrics metrics = g.getFontMetrics(font);
        int textWidth = metrics.stringWidth(string);
        if (textWidth <= maxWidth) return font;
        int newFontSize = (int)((double)initialFontSize * maxWidth / textWidth);
        newFontSize = Math.max(newFontSize, 30);
        return new Font("Arial", Font.BOLD, newFontSize);
    }

    public static void drawMixedEmojiText(Graphics2D g2d, String emojiAndText, int x, int y, int emojiSize, Font textFont, Color color) {
        Font font = emojiFont.deriveFont(Font.BOLD, emojiSize);

        String emoji = emojiAndText.substring(0, 2).trim();
        String text = emojiAndText.substring(emoji.length()).trim();
        g2d.setColor(color);

        // Dessine l'emoji
        g2d.setFont(font);
        g2d.drawString(emoji, x, y);

        // Largeur de l'emoji pour positionner le texte après
        FontMetrics emojiMetrics = g2d.getFontMetrics(font);
        int emojiWidth = emojiMetrics.stringWidth(emoji) + 5;

        // Dessine le texte
        g2d.setFont(textFont);
        g2d.drawString(text, x + emojiWidth, y);
    }


}
