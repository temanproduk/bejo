package com.bejo.service;

import com.bejo.dto.response.QrisData;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

@Service
public class QrisService {

    private final UserRepository userRepository;

    public QrisService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public QrisData getQrisData(String username, HttpServletRequest request) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String base = request.getScheme() + "://" + request.getServerName()
                + (isStandardPort(request) ? "" : ":" + request.getServerPort());
        String imageUrl = base + "/api/qris/image/" + user.getQrisCode() + ".png";

        return new QrisData(imageUrl, user.getBusinessName(), user.getBusinessAddress());
    }

    public byte[] generateImage(String qrisCode) throws IOException {
        int size = 300;
        int moduleSize = 6;
        int offset = 30;
        int gridSize = 20;

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // White background
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);

        // Pseudo-random data modules derived from qrisCode
        Random rng = new Random(qrisCode.hashCode());
        g.setColor(Color.BLACK);
        for (int row = 0; row < gridSize; row++) {
            for (int col = 0; col < gridSize; col++) {
                if (rng.nextBoolean()) {
                    g.fillRect(offset + col * moduleSize, offset + row * moduleSize,
                            moduleSize - 1, moduleSize - 1);
                }
            }
        }

        // Three finder patterns at corners
        drawFinderPattern(g, offset, offset, moduleSize);
        drawFinderPattern(g, offset + (gridSize - 7) * moduleSize, offset, moduleSize);
        drawFinderPattern(g, offset, offset + (gridSize - 7) * moduleSize, moduleSize);

        // White out center area for label
        g.setColor(Color.WHITE);
        g.fillRoundRect(90, 105, 120, 55, 10, 10);

        // "QRIS" label
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        FontMetrics fm = g.getFontMetrics();
        String label = "QRIS";
        g.drawString(label, (size - fm.stringWidth(label)) / 2, 130);

        // Short code below label
        g.setFont(new Font("Monospaced", Font.PLAIN, 9));
        fm = g.getFontMetrics();
        String shortCode = qrisCode.length() > 16 ? qrisCode.substring(0, 16) : qrisCode;
        g.drawString(shortCode, (size - fm.stringWidth(shortCode)) / 2, 148);

        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private void drawFinderPattern(Graphics2D g, int x, int y, int ms) {
        g.setColor(Color.BLACK);
        g.fillRect(x, y, 7 * ms, 7 * ms);
        g.setColor(Color.WHITE);
        g.fillRect(x + ms, y + ms, 5 * ms, 5 * ms);
        g.setColor(Color.BLACK);
        g.fillRect(x + 2 * ms, y + 2 * ms, 3 * ms, 3 * ms);
    }

    private boolean isStandardPort(HttpServletRequest request) {
        int port = request.getServerPort();
        return ("http".equals(request.getScheme()) && port == 80)
                || ("https".equals(request.getScheme()) && port == 443);
    }
}
