package com.bejo.voice.service;

import com.bejo.shared.exception.ServiceUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Google Cloud Speech-to-Text (v1 {@code speech:recognize}) over plain REST, authenticated as the service's own
 * account through Application Default Credentials — no key to store (the organisation forbids creating one), and
 * no gRPC client in the image.
 *
 * <p>Tuned for a spoken amount: Indonesian, the {@code latest_short} model (built for one short utterance), and
 * hints for the money words so "ribu" / "juta" are not dropped and merchant slang like "ceban" is heard at all.
 * What comes back is a mix of digits and words, which {@link IndonesianAmountParser} understands.
 *
 * <p>The audio is sent and forgotten: never logged, never stored here.
 */
@Component
@Profile("!test")   // the functional tests plug in ScriptedSpeechToText (test sources) instead
public class GoogleSpeechToText implements SpeechToText {

    private static final Logger log = LoggerFactory.getLogger(GoogleSpeechToText.class);

    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    /** Words the recogniser should lean towards; without them "dua puluh lima ribu" tended to come back as "25". */
    private static final List<String> PHRASE_HINTS = List.of(
            "ribu", "juta", "ratus", "puluh", "belas", "seribu", "sejuta", "seratus", "setengah", "rupiah",
            "seceng", "ceceng", "noceng", "goceng", "ceban", "noban", "gocap", "goban", "gopek");

    private final RestClient http;
    private final String url;
    private final String model;
    private final Supplier<Auth> auth;

    /** An access token and, when the credentials carry one, the project to bill the call to. */
    record Auth(String token, String quotaProject) {}

    @Autowired
    public GoogleSpeechToText(
            @Value("${app.voice.speech-url:https://speech.googleapis.com/v1/speech:recognize}") String url,
            @Value("${app.voice.model:latest_short}") String model,
            @Value("${app.voice.timeout-seconds:10}") int timeoutSeconds) {
        this(restClient(timeoutSeconds), url, model, GoogleSpeechToText::applicationDefaultAuth);
    }

    // Tests give it a RestClient bound to a mock server and a fixed token
    GoogleSpeechToText(RestClient http, String url, String model, Supplier<Auth> auth) {
        this.http = http;
        this.url = url;
        this.model = model;
        this.auth = auth;
    }

    private static RestClient restClient(int timeoutSeconds) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder().requestFactory(factory).build();
    }

    // Resolved on each call; the library caches the token and refreshes it only when it is about to expire
    private static volatile GoogleCredentials credentials;

    private static Auth applicationDefaultAuth() {
        try {
            GoogleCredentials c = credentials;
            if (c == null) {
                c = GoogleCredentials.getApplicationDefault().createScoped(SCOPE);
                credentials = c;
            }
            c.refreshIfExpired();
            return new Auth(c.getAccessToken().getTokenValue(), c.getQuotaProjectId());
        } catch (IOException e) {
            throw new IllegalStateException("No Google credentials for speech-to-text: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Transcript> transcribe(byte[] pcm16Mono, int sampleRateHertz) {
        Map<String, Object> body = Map.of(
                "config", Map.of(
                        "encoding", "LINEAR16",
                        "sampleRateHertz", sampleRateHertz,
                        "languageCode", "id-ID",
                        "model", model,
                        "speechContexts", List.of(Map.of("phrases", PHRASE_HINTS, "boost", 15))),
                "audio", Map.of("content", Base64.getEncoder().encodeToString(pcm16Mono)));

        JsonNode answer;
        try {
            Auth a = auth.get();
            answer = http.post().uri(url)
                    .header("Authorization", "Bearer " + a.token())
                    .headers(h -> { if (a.quotaProject() != null) h.set("x-goog-user-project", a.quotaProject()); })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // 403 is usually "the API is not enabled" or "this account may not call it"; 429 is Google's own quota.
            // The body is Google's error text — safe to log; the request (the audio) is never logged.
            log.error("[VOICE] Speech-to-Text answered {}: {}", e.getStatusCode().value(), abbreviate(e.getResponseBodyAsString()));
            throw new ServiceUnavailableException("Input suara belum tersedia. Ketik nominalnya dulu ya.");
        } catch (RuntimeException e) {
            log.error("[VOICE] Speech-to-Text call failed: {}", e.getMessage());
            throw new ServiceUnavailableException("Input suara belum tersedia. Ketik nominalnya dulu ya.");
        }
        return firstTranscript(answer);
    }

    /** Every result's best guess joined (a pause can split one sentence into two results), with their mean confidence. */
    static Optional<Transcript> firstTranscript(JsonNode answer) {
        if (answer == null || !answer.has("results")) return Optional.empty();
        List<String> parts = new ArrayList<>();
        double confidenceSum = 0;
        int counted = 0;
        for (JsonNode result : answer.get("results")) {
            JsonNode best = result.path("alternatives").path(0);
            String text = best.path("transcript").asText("").trim();
            if (text.isEmpty()) continue;
            parts.add(text);
            if (best.has("confidence")) { confidenceSum += best.get("confidence").asDouble(); counted++; }
        }
        if (parts.isEmpty()) return Optional.empty();
        return Optional.of(new Transcript(String.join(" ", parts), counted == 0 ? 0 : confidenceSum / counted));
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 300 ? one.substring(0, 300) + "…" : one;
    }
}
