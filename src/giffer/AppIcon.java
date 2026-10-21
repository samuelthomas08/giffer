package giffer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** The window/taskbar icon, drawn in code: a flat near-black tile with a hairline border and a white "GIF". */
final class AppIcon {
    private AppIcon() {}

    private static final Color TILE_COLOR = new Color(0x18181B);
    private static final Color BORDER_COLOR = new Color(0x71717A);

    static BufferedImage render(int sizePixels) {
        BufferedImage image = new BufferedImage(sizePixels, sizePixels, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        double inset = Math.max(0.5, sizePixels / 64.0);
        double tileSize = sizePixels - 2 * inset;
        double cornerDiameter = sizePixels * 0.24;
        RoundRectangle2D tile = new RoundRectangle2D.Double(inset, inset, tileSize, tileSize, cornerDiameter, cornerDiameter);
        graphics.setColor(TILE_COLOR);
        graphics.fill(tile);
        graphics.setColor(BORDER_COLOR);
        graphics.setStroke(new BasicStroke((float) Math.max(1, sizePixels / 42.0)));
        graphics.draw(tile);

        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font("Segoe UI", Font.BOLD, (int) (sizePixels * 0.36)));
        FontMetrics metrics = graphics.getFontMetrics();
        String text = "GIF";
        float textLeft = (sizePixels - metrics.stringWidth(text)) / 2f;
        float textBaseline = (sizePixels - metrics.getHeight()) / 2f + metrics.getAscent();
        graphics.drawString(text, textLeft, textBaseline);
        graphics.dispose();
        return image;
    }
}
