import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Draws two made-up phone screens for ReplyDraftEvalTest (ADR-092): a chat with Maya Chen, and a settings page with no
 * message. Run with JDK 21: java -Djava.awt.headless=true scripts/MadeUpScreens.java <output dir>
 */
public class MadeUpScreens {
    static final int W = 720, H = 1440;

    static Graphics2D canvas(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        return g;
    }

    static void bubble(Graphics2D g, String text, boolean mine, int y) {
        g.setFont(new Font("SansSerif", Font.PLAIN, 30));
        FontMetrics m = g.getFontMetrics();
        int w = m.stringWidth(text) + 48, x = mine ? W - w - 32 : 32;
        g.setColor(mine ? new Color(0xD3E3FD) : new Color(0xF1F3F4));
        g.fill(new RoundRectangle2D.Float(x, y, w, 76, 38, 38));
        g.setColor(new Color(0x1F1F1F));
        g.drawString(text, x + 24, y + 49);
    }

    public static void main(String[] args) throws Exception {
        BufferedImage chat = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(chat);
        g.setColor(new Color(0x1F1F1F));
        g.setFont(new Font("SansSerif", Font.BOLD, 36));
        g.drawString("←   Maya Chen", 32, 110);
        g.setColor(new Color(0xE0E0E0));
        g.fillRect(0, 150, W, 2);
        g.setFont(new Font("SansSerif", Font.PLAIN, 24));
        g.setColor(new Color(0x757575));
        g.drawString("Today 11:42 AM", 280, 240);
        bubble(g, "Hi! Got your notes about the trip.", false, 280);
        bubble(g, "Great, thanks!", true, 380);
        bubble(g, "Are we still on for lunch at 12:30?", false, 480);
        g.setColor(new Color(0xF1F3F4));
        g.fill(new RoundRectangle2D.Float(32, H - 140, W - 64, 88, 44, 44));
        g.setColor(new Color(0x757575));
        g.setFont(new Font("SansSerif", Font.PLAIN, 28));
        g.drawString("Text message", 70, H - 84);
        ImageIO.write(chat, "jpg", new File(args[0], "reply-chat.jpg"));

        BufferedImage settings = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        g = canvas(settings);
        g.setColor(new Color(0x1F1F1F));
        g.setFont(new Font("SansSerif", Font.BOLD, 44));
        g.drawString("Settings", 32, 120);
        String[][] rows = {{"Network & internet", "Wi-Fi, mobile data"}, {"Connected devices", "Bluetooth, pairing"}, {"Battery", "82%"}, {"Display", "Dark theme, font size"}, {"Sound & vibration", "Volume, haptics"}};
        int y = 220;
        for (String[] row : rows) {
            g.setColor(new Color(0x1F1F1F));
            g.setFont(new Font("SansSerif", Font.PLAIN, 32));
            g.drawString(row[0], 48, y);
            g.setColor(new Color(0x757575));
            g.setFont(new Font("SansSerif", Font.PLAIN, 26));
            g.drawString(row[1], 48, y + 40);
            y += 130;
        }
        ImageIO.write(settings, "jpg", new File(args[0], "reply-settings.jpg"));
    }
}
