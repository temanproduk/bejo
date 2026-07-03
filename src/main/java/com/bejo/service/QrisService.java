package com.bejo.service;

import com.bejo.dto.response.QrisData;
import com.bejo.entity.User;
import com.bejo.entity.UserQris;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.UserQrisRepository;
import com.bejo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Random;

@Service
public class QrisService {

    private static final Logger log = LoggerFactory.getLogger(QrisService.class);

    private final UserRepository userRepository;
    private final UserQrisRepository userQrisRepository;

    public QrisService(UserRepository userRepository, UserQrisRepository userQrisRepository) {
        this.userRepository = userRepository;
        this.userQrisRepository = userQrisRepository;
    }

    @Transactional
    public QrisData getQrisData(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        UserQris qris = userQrisRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("QRIS data not found"));

        if (qris.getQrisImage() != null) {
            log.info("[QRIS] Serving UPLOADED image for username={}", username);
            return new QrisData("UPLOADED", qris.getQrisImage(),
                    user.getBusinessName(), user.getBusinessAddress(), user.getNmid());
        }

        String initialImage = qris.getInitialQrisImage();
        if (initialImage == null) {
            log.info("[QRIS] Generating initial MANAGED image for username={}", username);
            initialImage = generateBase64Image(qris.getQrisCode());
            qris.setInitialQrisImage(initialImage);
            userQrisRepository.save(qris);
        }

        log.info("[QRIS] Serving MANAGED image for username={}", username);
        return new QrisData("MANAGED", initialImage,
                user.getBusinessName(), user.getBusinessAddress(), user.getNmid());
    }

    @Transactional
    public void uploadQris(String username, String base64Image) {
        UserQris qris = userQrisRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("QRIS data not found"));

        validateBase64Image(base64Image);

        qris.setQrisImage(base64Image);
        userQrisRepository.save(qris);
        log.info("[QRIS] Uploaded custom image ({} chars) for username={}", base64Image.length(), username);
    }

    @Transactional
    public void resetQris(String username) {
        UserQris qris = userQrisRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("QRIS data not found"));

        qris.setQrisImage(null);

        if (qris.getInitialQrisImage() == null) {
            qris.setInitialQrisImage(generateBase64Image(qris.getQrisCode()));
        }

        userQrisRepository.save(qris);
        log.info("[QRIS] Reset to MANAGED image for username={}", username);
    }

    public String generateBase64Image(String qrisCode) {
        try {
            byte[] png = generatePng(qrisCode);
            return Base64.getEncoder().encodeToString(png);
        } catch (IOException e) {
            log.error("[QRIS] Failed to generate QRIS image: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to generate QRIS image", e);
        }
    }

    private void validateBase64Image(String value) {
        try {
            Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("qrisImage bukan format base64 yang valid");
        }
    }

    private byte[] generatePng(String qrisCode) throws IOException {
        int size = 300;
        int moduleSize = 6;
        int offset = 30;
        int gridSize = 20;

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);

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

        drawFinderPattern(g, offset, offset, moduleSize);
        drawFinderPattern(g, offset + (gridSize - 7) * moduleSize, offset, moduleSize);
        drawFinderPattern(g, offset, offset + (gridSize - 7) * moduleSize, moduleSize);

        g.setColor(Color.WHITE);
        g.fillRoundRect(90, 105, 120, 55, 10, 10);

        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        FontMetrics fm = g.getFontMetrics();
        String label = "QRIS";
        g.drawString(label, (size - fm.stringWidth(label)) / 2, 130);

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
}
