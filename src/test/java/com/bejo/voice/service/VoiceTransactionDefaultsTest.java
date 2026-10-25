package com.bejo.voice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The shipped defaults of "say the sale" (application.yml with no environment variable set), and how they sit with the deploy script. */
class VoiceTransactionDefaultsTest {

    private StandardEnvironment env;

    @BeforeEach
    void loadApplicationYml() throws Exception {
        env = new StandardEnvironment();
        // Only the file's own defaults: drop the real environment and system properties so a developer's shell cannot change the answer
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(env.getPropertySources()::addLast);
    }

    private String property(String name) {
        return env.getProperty("app.voice.transaction." + name);
    }

    @Test
    void theDefaultsAreTheDocumentedOnes() {
        assertThat(property("model")).isEqualTo("gemini-3.5-flash");
        assertThat(property("location")).isEqualTo("global");
        assertThat(property("project-id")).isEmpty();
        assertThat(property("max-output-tokens")).isEqualTo("2048");
        assertThat(property("max-seconds")).isEqualTo("30");
        assertThat(property("max-requests-per-minute")).isEqualTo("6");
        assertThat(property("max-requests-per-day")).isEqualTo("100");
        assertThat(property("max-amount")).isEqualTo("999999999");
    }

    @Test
    void thinkingDefaultsToLowAndIsAValidLevel() {
        assertThat(property("thinking-level")).isEqualTo("LOW");
        assertThat(GeminiTransactionUnderstanding.THINKING_LEVELS).contains(property("thinking-level"));
    }

    @Test
    void geminiGivesUpBeforeCloudRunCutsTheRequest() throws Exception {
        int gemini = Integer.parseInt(property("timeout-seconds"));
        assertThat(gemini).isEqualTo(12);

        // CLOUD_RUN_TIMEOUT is in seconds: `--timeout="${CLOUD_RUN_TIMEOUT:-45}"`
        String script = Files.readString(Path.of("scripts/deploy-cloud-run.sh"));
        Matcher m = Pattern.compile("--timeout=\"\\$\\{CLOUD_RUN_TIMEOUT:-(\\d+)}\"").matcher(script);
        assertThat(m.find()).as("the deploy script's request timeout default").isTrue();
        int platform = Integer.parseInt(m.group(1));

        assertThat(gemini).as("Gemini timeout must stay below the Cloud Run request timeout (%s s)", platform).isLessThan(platform);
    }

    private String live(String name) {
        return env.getProperty("app.voice.live." + name);
    }

    @Test
    void theLiveDefaultsAreTheDocumentedOnes() {
        assertThat(live("enabled")).isEqualTo("true");
        assertThat(live("model")).isEqualTo("gemini-3.8-live");
        assertThat(live("location")).isEqualTo("us-central1");   // the Live models are served in regions, not at "global"
        assertThat(live("max-audio-seconds")).isEqualTo("30");   // the same sale length as the one-shot endpoint
        assertThat(live("max-session-seconds")).isEqualTo("40");
        assertThat(live("settle-seconds")).isEqualTo("6");
        assertThat(live("max-sessions")).isEqualTo("40");
    }

    @Test
    void aLiveSessionEndsItselfBeforeCloudRunCutsTheConnection() throws Exception {
        String script = Files.readString(Path.of("scripts/deploy-cloud-run.sh"));
        Matcher m = Pattern.compile("--timeout=\"\\$\\{CLOUD_RUN_TIMEOUT:-(\\d+)}\"").matcher(script);
        assertThat(m.find()).as("the deploy script's request timeout default").isTrue();
        int platform = Integer.parseInt(m.group(1));

        int session = Integer.parseInt(live("max-session-seconds"));
        assertThat(session).as("a live session must end itself below the Cloud Run request timeout (%s s)", platform).isLessThan(platform);
        // ...and long enough for the longest sale (30 s of audio) plus the model's time to finish it
        assertThat(session).isGreaterThan(Integer.parseInt(live("max-audio-seconds")) + 5);
    }
}
