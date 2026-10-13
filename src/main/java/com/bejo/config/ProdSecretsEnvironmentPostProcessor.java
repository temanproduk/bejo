package com.bejo.config;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start under the {@code prod} profile with placeholder secrets.
 *
 * <p>{@code application.yml} carries development defaults (a JWT key that is public in this
 * repository, {@code change-me-in-production} as the API token) so local runs need no setup.
 * That is convenient and dangerous: a production deploy whose environment variable is missing or
 * mistyped would otherwise boot happily with a signing key anyone can read and forge tokens
 * with. Failing at startup turns that silent misconfiguration into a failed deploy — and since a
 * revision that never becomes healthy never takes traffic (Cloud Run) and gets rolled back by
 * {@code deploy-vm.sh}, a refusal here cannot cause an outage.
 *
 * <p>This is an {@link EnvironmentPostProcessor} rather than a bean on purpose:
 * {@code spring.main.lazy-initialization} is on, so a validator bean nobody depends on would
 * never be created, and the check would silently do nothing. It runs last, after
 * {@code application.yml} and the environment have been merged. Registered in
 * {@code META-INF/spring.factories}.
 */
public class ProdSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    // Must match the fallbacks in application.yml (ProdSecretsEnvironmentPostProcessorTest checks).
    static final String DEV_API_TOKEN = "change-me-in-production";
    static final String DEV_JWT_SECRET =
            "YmVqby1kZXYtc2VjcmV0LWtleS1tdXN0LWJlLWNoYW5nZWQtaW4tcHJvZHVjdGlvbi1taW4tMjU2Yml0cw==";
    static final String DEV_DB_PASSWORD = "bejo";

    private final Log log;

    public ProdSecretsEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(ProdSecretsEnvironmentPostProcessor.class);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        for (String warning : warnings(environment)) {
            log.warn("[SECURITY] " + warning);
        }
        List<String> errors = errors(environment);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start with the 'prod' profile and placeholder secrets:\n  - "
                            + String.join("\n  - ", errors));
        }
    }

    static List<String> errors(Environment env) {
        List<String> errors = new ArrayList<>();

        String apiToken = env.getProperty("app.api-token");
        if (apiToken == null || apiToken.isBlank()) {
            errors.add("API_TOKEN is not set");
        } else if (DEV_API_TOKEN.equals(apiToken)) {
            errors.add("API_TOKEN is still the built-in default '" + DEV_API_TOKEN + "'");
        }

        String jwt = env.getProperty("app.jwt.secret");
        if (jwt == null || jwt.isBlank()) {
            errors.add("JWT_SECRET is not set");
        } else if (DEV_JWT_SECRET.equals(jwt)) {
            errors.add("JWT_SECRET is still the built-in development key, which is public in the repository");
        } else {
            try {
                Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwt));
            } catch (RuntimeException e) {
                errors.add("JWT_SECRET must be base64 and at least 256 bits (openssl rand -base64 32): "
                        + e.getMessage());
            }
        }
        return errors;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    static List<String> warnings(Environment env) {
        List<String> warnings = new ArrayList<>();

        if (DEV_DB_PASSWORD.equals(env.getProperty("spring.datasource.password"))) {
            warnings.add("DB_PASSWORD is the built-in default '" + DEV_DB_PASSWORD + "'");
        }
        Long expirationMs = env.getProperty("app.jwt.expiration-ms", Long.class);
        if (expirationMs != null && expirationMs <= 0) {
            warnings.add("JWT_EXPIRATION_MS <= 0 disables token expiry: a leaked token stays valid until the "
                    + "password changes or the user logs out");
        }
        String callbackSecret = env.getProperty("app.callback.secret");
        if (callbackSecret == null || callbackSecret.isBlank()) {
            warnings.add("The QRIS callback (/snap/**) is UNAUTHENTICATED: CALLBACK_SECRET is not set, so anyone who "
                    + "can reach it can forge a 'payment received' notification. Set CALLBACK_SECRET (and have the "
                    + "provider send it as X-Callback-Secret), or restrict the path to the provider's IPs at the edge.");
        }
        if (isBlank(env.getProperty("spring.mail.host")) || isBlank(env.getProperty("spring.mail.username"))
                || isBlank(env.getProperty("spring.mail.password"))) {
            warnings.add("Email is not configured (MAIL_HOST / MAIL_USERNAME / MAIL_PASSWORD), so password reset "
                    + "is OFF: /api/auth/forgot-password answers 503");
        }
        if (env.getProperty("app.callback.fallback-to-default", Boolean.class, false)) {
            warnings.add("CALLBACK_FALLBACK_TO_DEFAULT is on: a callback whose merchantPan matches no business is "
                    + "recorded against the default account instead of being rejected");
        }
        return warnings;
    }
}
