package ca.lwi.trqcbot.utils;

import java.awt.*;

public class FontUtils {

    public static Font calculateOptimalNameFont(Graphics2D g, String string, int maxWidth, int initialFontSize) {
        Font font = new Font("Arial", Font.BOLD, initialFontSize);
        FontMetrics metrics = g.getFontMetrics(font);
        int textWidth = metrics.stringWidth(string);
        if (textWidth <= maxWidth) return font;
        int newFontSize = (int)((double)initialFontSize * maxWidth / textWidth);
        newFontSize = Math.max(newFontSize, 30);
        return new Font("Arial", Font.BOLD, newFontSize);
    }

}
