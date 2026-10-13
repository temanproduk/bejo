package com.bejo.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Base64;

// @Lazy(false): force eager init despite spring.main.lazy-initialization=true —
// this bean's only job is the @PostConstruct side effect, and nothing injects it,
// so under lazy init it would otherwise never be created and Firebase never starts.
@Configuration
@Lazy(false)
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${app.firebase.credentials-path:}")
    private String credentialsPath;

    @Value("${app.firebase.credentials-base64:}")
    private String credentialsBase64;

    /**
     * The Firebase project that owns the app's FCM tokens. Setting it with no explicit credentials selects
     * application-default credentials — on Cloud Run, the service's own identity — so no key file is needed
     * (organizations that enforce iam.disableServiceAccountKeyCreation can't issue one). That identity needs
     * roles/firebasecloudmessaging.admin. Explicit credentials, when given, still take precedence.
     */
    @Value("${app.firebase.project-id:}")
    private String projectId;

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
        } else if (StringUtils.hasText(projectId)) {
            // Fails startup when no application-default credentials exist, like a malformed key does:
            // a misconfigured deploy should be rejected, not run with push silently dead.
            credentials = GoogleCredentials.getApplicationDefault();
            log.info("Firebase initialized from application-default credentials (project {})", projectId);
        } else {
            log.warn("No Firebase credentials configured — push notifications will be disabled");
            return;
        }

        FirebaseOptions.Builder builder = FirebaseOptions.builder().setCredentials(credentials);
        // Application-default credentials from the metadata server carry no project id; FCM needs one.
        if (StringUtils.hasText(projectId)) {
            builder.setProjectId(projectId);
        }
        FirebaseOptions options = builder.build();

        FirebaseApp.initializeApp(options);
        log.info("FirebaseApp initialized successfully");
    }
}
