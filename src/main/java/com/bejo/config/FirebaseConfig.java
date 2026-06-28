package com.bejo.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Base64;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${app.firebase.credentials-path:}")
    private String credentialsPath;

    @Value("${app.firebase.credentials-base64:}")
    private String credentialsBase64;

    @PostConstruct
    public void initialize() throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) {
            return;
        }

        GoogleCredentials credentials;

        if (StringUtils.hasText(credentialsBase64)) {
            byte[] decoded = Base64.getDecoder().decode(credentialsBase64);
            credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(decoded));
            log.info("Firebase initialized from base64 credentials");
        } else if (StringUtils.hasText(credentialsPath)) {
            credentials = GoogleCredentials.fromStream(new FileInputStream(credentialsPath));
            log.info("Firebase initialized from credentials file: {}", credentialsPath);
        } else {
            log.warn("No Firebase credentials configured — push notifications will be disabled");
            return;
        }

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .build();

        FirebaseApp.initializeApp(options);
        log.info("FirebaseApp initialized successfully");
    }
}
