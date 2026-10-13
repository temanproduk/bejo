package com.bejo.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.SpringApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProdSecretsEnvironmentPostProcessorTest {

    // A real-looking 32-byte base64 key (not the dev default)
    private static final String GOOD_JWT = "cHJvZC1rZXktMzItYnl0ZXMtbG9uZy1lbm91Z2gtMTIzNDU=";

    private final ProdSecretsEnvironmentPostProcessor processor =
            new ProdSecretsEnvironmentPostProcessor(supplier -> supplier.get());

    private MockEnvironment prodEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    private void run(MockEnvironment env) {
        processor.postProcessEnvironment(env, new SpringApplication());
    }

    @Test
    void doesNothingOutsideTheProdProfile() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.api-token", ProdSecretsEnvironmentPostProcessor.DEV_API_TOKEN)
                .withProperty("app.jwt.secret", ProdSecretsEnvironmentPostProcessor.DEV_JWT_SECRET);

        assertThatCode(() -> run(env)).doesNotThrowAnyException();
    }

    @Test
    void refusesToStartInProdWithTheBuiltInDefaults() {
        MockEnvironment env = prodEnv()
                .withProperty("app.api-token", ProdSecretsEnvironmentPostProcessor.DEV_API_TOKEN)
                .withProperty("app.jwt.secret", ProdSecretsEnvironmentPostProcessor.DEV_JWT_SECRET);

        assertThatThrownBy(() -> run(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API_TOKEN is still the built-in default")
                .hasMessageContaining("JWT_SECRET is still the built-in development key");
    }

    @Test
    void refusesBlankSecrets() {
        MockEnvironment env = prodEnv()
                .withProperty("app.api-token", "  ")
                .withProperty("app.jwt.secret", "");

        assertThatThrownBy(() -> run(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API_TOKEN is not set")
                .hasMessageContaining("JWT_SECRET is not set");
    }

    @Test
    void refusesAJwtSecretThatIsTooShortOrNotBase64() {
        MockEnvironment tooShort = prodEnv()
                .withProperty("app.api-token", "a-real-token")
                .withProperty("app.jwt.secret", "c2hvcnQ=");   // "short"
        assertThatThrownBy(() -> run(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 256 bits");

        MockEnvironment notBase64 = prodEnv()
                .withProperty("app.api-token", "a-real-token")
                .withProperty("app.jwt.secret", "%%% not base64 %%%");
        assertThatThrownBy(() -> run(notBase64))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET must be base64");
    }

    @Test
    void startsInProdWithRealSecrets() {
        MockEnvironment env = prodEnv()
                .withProperty("app.api-token", "a-real-token")
                .withProperty("app.jwt.secret", GOOD_JWT);

        assertThatCode(() -> run(env)).doesNotThrowAnyException();
    }

    @Test
    void warnsAboutRiskySettingsWithoutRefusingToStart() {
        MockEnvironment env = prodEnv()
                .withProperty("app.api-token", "a-real-token")
                .withProperty("app.jwt.secret", GOOD_JWT)
                .withProperty("spring.datasource.password", "bejo")
                .withProperty("app.jwt.expiration-ms", "0")
                .withProperty("app.callback.fallback-to-default", "true");

        assertThat(ProdSecretsEnvironmentPostProcessor.warnings(env))
                .anyMatch(w -> w.contains("DB_PASSWORD"))
                .anyMatch(w -> w.contains("disables token expiry"))
                .anyMatch(w -> w.contains("UNAUTHENTICATED"))
                .anyMatch(w -> w.contains("CALLBACK_FALLBACK_TO_DEFAULT"))
                .anyMatch(w -> w.contains("password reset is OFF"));
        assertThatCode(() -> run(env)).doesNotThrowAnyException();
    }

    @Test
    void noEmailWarningOnceMailIsConfigured() {
        MockEnvironment env = prodEnv()
                .withProperty("spring.mail.host", "smtp.zoho.com")
                .withProperty("spring.mail.username", "noreply@example.com")
                .withProperty("spring.mail.password", "app-password");

        assertThat(ProdSecretsEnvironmentPostProcessor.warnings(env))
                .noneMatch(w -> w.contains("password reset is OFF"));
    }

    @Test
    void noCallbackWarningOnceASecretIsSet() {
        MockEnvironment env = prodEnv()
                .withProperty("app.callback.secret", "s3cret");

        assertThat(ProdSecretsEnvironmentPostProcessor.warnings(env))
                .noneMatch(w -> w.contains("UNAUTHENTICATED"));
    }

    @Test
    void theDevDefaultsMatchWhatApplicationYmlShipsWith() {
        // If application.yml's fallbacks ever change, the check above would silently stop
        // recognising them — so pin the two together.
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = yaml.getObject();

        assertThat(props.getProperty("app.api-token"))
                .endsWith(":" + ProdSecretsEnvironmentPostProcessor.DEV_API_TOKEN + "}");
        assertThat(props.getProperty("app.jwt.secret"))
                .endsWith(":" + ProdSecretsEnvironmentPostProcessor.DEV_JWT_SECRET + "}");
        assertThat(props.getProperty("spring.datasource.password"))
                .endsWith(":" + ProdSecretsEnvironmentPostProcessor.DEV_DB_PASSWORD + "}");
    }
}
