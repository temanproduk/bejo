package com.bejo.voice.service;

import com.bejo.shared.exception.ServiceUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini on Vertex AI ({@code generateContent}) over plain REST, authenticated as the service's own account through
 * Application Default Credentials — no key to store (the organisation forbids creating one) and no SDK in the image,
 * the same way {@link GoogleSpeechToText} works.
 *
 * <p>One request carries the instruction, the merchant's product list and the recording (inline {@code audio/wav}),
 * with a response schema so the answer is JSON in the contract's shape. No {@code temperature} is sent: Google's Gemini 3
 * guidance is to leave it at the default (1.0), lower values can loop or degrade, and the schema keeps the answer
 * structured anyway. Gemini 3 models also think by default, which is slow, so {@code thinkingConfig.thinkingLevel} is sent
 * (default {@code LOW}; see {@link #thinkingLevel}) — never together with a thinking budget. Whatever comes back is
 * parsed into a {@link Proposal} and handed to {@link VoiceTransactionService}, which treats it as untrusted. If the
 * call fails, the API is not enabled, the project is unknown, or the answer cannot be read, the result is a
 * {@link ServiceUnavailableException} — never a half-answer.
 *
 * <p>The audio and what was understood are sent and forgotten: never logged, never stored here. Google's own error
 * text (an API-not-enabled message, a quota message) is logged, abbreviated; the request never is.
 */
@Component
@Profile("!test")   // the functional tests plug in ScriptedTransactionUnderstanding (test sources) instead
public class GeminiTransactionUnderstanding implements TransactionUnderstanding {

    private static final Logger log = LoggerFactory.getLogger(GeminiTransactionUnderstanding.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the unavailable-feature answers say; the app falls back to the manual form. */
    static final String UNAVAILABLE = "Input suara belum tersedia. Isi manual dulu ya.";

    // Model ids, locations and project ids are all of this shape; anything else would change the URL's meaning
    static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    // A model that ignores "JSON only" sometimes wraps the answer in a markdown fence
    private static final Pattern FENCED = Pattern.compile("```[A-Za-z]*\\s*(.*?)```", Pattern.DOTALL);

    /** The thinking levels Google documents for Gemini 3; anything else is not sent. */
    static final List<String> THINKING_LEVELS = List.of("MINIMAL", "LOW", "MEDIUM", "HIGH");
    static final String DEFAULT_THINKING_LEVEL = "LOW";

    private static final List<String> ANSWER_FIELDS =
            List.of("transcript", "amount", "paymentMethod", "customerInfo", "merchantInfo", "items");

    private final RestClient http;
    private final String model;
    private final String location;
    private final String configuredProject;
    private final int maxOutputTokens;
    /** {@code generationConfig.thinkingConfig.thinkingLevel}, or null to leave the field out. */
    private final String thinkingLevel;
    private final Supplier<GoogleSpeechToText.Auth> auth;

    @Autowired
    public GeminiTransactionUnderstanding(
            @Value("${app.voice.transaction.model:gemini-3.5-flash}") String model,
            @Value("${app.voice.transaction.location:global}") String location,
            @Value("${app.voice.transaction.project-id:}") String projectId,
            @Value("${app.voice.transaction.timeout-seconds:12}") int timeoutSeconds,
            @Value("${app.voice.transaction.max-output-tokens:2048}") int maxOutputTokens,
            @Value("${app.voice.transaction.thinking-level:LOW}") String thinkingLevel) {
        this(restClient(timeoutSeconds), model, location, projectId, maxOutputTokens, thinkingLevel,
                GoogleSpeechToText::applicationDefaultAuth);
    }

    // Tests give it a RestClient bound to a mock server and a fixed token
    GeminiTransactionUnderstanding(RestClient http, String model, String location, String projectId,
                                   int maxOutputTokens, String thinkingLevel, Supplier<GoogleSpeechToText.Auth> auth) {
        this.http = http;
        this.model = model;
        this.location = location;
        this.configuredProject = projectId;
        this.maxOutputTokens = maxOutputTokens;
        this.thinkingLevel = thinkingLevel(thinkingLevel);
        this.auth = auth;
    }

    /**
     * The level to send, or null to send none. Blank (or {@code NONE}, for deploys that cannot carry an empty value) means
     * "leave the field out" — the fallback if a model rejects it with a 400. A value outside
     * MINIMAL/LOW/MEDIUM/HIGH (any case) falls back to LOW with a warning, so a typo never silently turns thinking back on at
     * the model's slow default.
     */
    static String thinkingLevel(String configured) {
        if (configured == null || configured.isBlank() || "NONE".equalsIgnoreCase(configured.trim())) return null;
        String level = configured.trim().toUpperCase(java.util.Locale.ROOT);
        if (THINKING_LEVELS.contains(level)) return level;
        log.warn("[VOICE] app.voice.transaction.thinking-level '{}' is not one of {}; using {}",
                abbreviate(configured), THINKING_LEVELS, DEFAULT_THINKING_LEVEL);
        return DEFAULT_THINKING_LEVEL;
    }

    private static RestClient restClient(int timeoutSeconds) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder().requestFactory(factory).build();
    }

    /** {@code global} is served by the bare host; every other location has its own. */
    static String host(String location) {
        return "global".equals(location) ? "aiplatform.googleapis.com" : location + "-aiplatform.googleapis.com";
    }

    static String endpoint(String project, String location, String model) {
        return "https://" + host(location) + "/v1/projects/" + project + "/locations/" + location
                + "/publishers/google/models/" + model + ":generateContent";
    }

    @Override
    public Proposal understand(byte[] pcm16Mono, int sampleRateHertz, List<CatalogueEntry> catalogue) {
        GoogleSpeechToText.Auth a;
        try {
            a = auth.get();
        } catch (RuntimeException e) {
            log.error("[VOICE] Gemini cannot authenticate: {}", e.getMessage());
            throw new ServiceUnavailableException(UNAVAILABLE);
        }
        // The project: the setting, else the one the credentials are billed to
        String project = StringUtils.hasText(configuredProject) ? configuredProject.trim() : a.quotaProject();
        if (!StringUtils.hasText(project)) {
            log.warn("[VOICE] Gemini is not configured: set GCP_PROJECT_ID (app.voice.transaction.project-id)");
            throw new ServiceUnavailableException(UNAVAILABLE);
        }
        if (!SAFE_SEGMENT.matcher(project).matches() || !SAFE_SEGMENT.matcher(location).matches()
                || !SAFE_SEGMENT.matcher(model).matches()) {
            log.error("[VOICE] Gemini is misconfigured: project, location or model has characters a URL path should not");
            throw new ServiceUnavailableException(UNAVAILABLE);
        }
        URI uri = URI.create(endpoint(project, location, model));

        // No temperature (Gemini 3: keep the default), no thinking budget (the level replaces it; sending both is an error)
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseSchema", TransactionPrompt.responseSchema());
        generationConfig.put("maxOutputTokens", maxOutputTokens);
        if (thinkingLevel != null) generationConfig.put("thinkingConfig", Map.of("thinkingLevel", thinkingLevel));

        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", TransactionPrompt.SYSTEM_INSTRUCTION))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(
                        Map.of("text", TransactionPrompt.userTurn(catalogue)),
                        Map.of("inlineData", Map.of(
                                "mimeType", WavAudio.MIME_TYPE,
                                "data", Base64.getEncoder().encodeToString(WavAudio.wrap(pcm16Mono, sampleRateHertz))))))),
                "generationConfig", generationConfig);

        JsonNode answer;
        try {
            answer = http.post().uri(uri)
                    .header("Authorization", "Bearer " + a.token())
                    .headers(h -> { if (a.quotaProject() != null) h.set("x-goog-user-project", a.quotaProject()); })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // 403 is usually "the API is not enabled" or "this account may not call it"; 404 "no such model or location";
            // 429 is Google's own quota. The body is Google's error text — safe to log; the request (the audio) is never logged.
            log.error("[VOICE] Gemini answered {}: {}", e.getStatusCode().value(), abbreviate(e.getResponseBodyAsString()));
            throw new ServiceUnavailableException(UNAVAILABLE);
        } catch (RuntimeException e) {
            log.error("[VOICE] Gemini call failed: {}", e.getClass().getSimpleName() + ": " + abbreviate(e.getMessage()));
            throw new ServiceUnavailableException(UNAVAILABLE);
        }
        return fromResponse(answer);
    }

    /** The model's text out of a {@code generateContent} answer, read as a {@link Proposal}. */
    static Proposal fromResponse(JsonNode answer) {
        JsonNode candidates = answer == null ? null : answer.path("candidates");
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            // A blocked prompt has no candidates; the block reason is Google's own enum, safe to log
            log.error("[VOICE] Gemini returned no answer (blockReason={})",
                    answer == null ? "n/a" : answer.path("promptFeedback").path("blockReason").asText("none"));
            throw new ServiceUnavailableException(UNAVAILABLE);
        }
        JsonNode candidate = candidates.get(0);
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            // Reasoning parts (thought=true) are not the answer
            if (part.path("thought").asBoolean(false)) continue;
            if (part.path("text").isTextual()) text.append(part.path("text").asText());
        }
        try {
            return parseModelText(text.toString());
        } catch (ServiceUnavailableException e) {
            // finishReason is Google's enum (MAX_TOKENS = the answer was cut off, SAFETY = blocked); the text itself is never logged
            log.error("[VOICE] Gemini's answer is unusable (finishReason={}, {} chars)",
                    candidate.path("finishReason").asText("n/a"), text.length());
            throw e;
        }
    }

    /**
     * The model's answer text — JSON, possibly inside a markdown fence — as a {@link Proposal}. Anything that is not
     * an object with the expected shape is unavailable rather than a partial answer; a single field of the wrong type
     * is just dropped, because the service validates every field anyway.
     */
    static Proposal parseModelText(String modelText) {
        String json = modelText == null ? "" : modelText.trim();
        Matcher fenced = FENCED.matcher(json);
        if (fenced.find()) json = fenced.group(1).trim();
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ServiceUnavailableException(UNAVAILABLE);
        }
        if (root == null || !root.isObject() || ANSWER_FIELDS.stream().noneMatch(root::has)) {
            throw new ServiceUnavailableException(UNAVAILABLE);
        }
        JsonNode itemsNode = root.get("items");
        List<ProposedItem> items = new ArrayList<>();
        if (itemsNode != null && !itemsNode.isNull()) {
            if (!itemsNode.isArray()) throw new ServiceUnavailableException(UNAVAILABLE);
            for (JsonNode n : itemsNode) {
                if (!n.isObject()) throw new ServiceUnavailableException(UNAVAILABLE);
                items.add(new ProposedItem(whole(n.get("productId")), text(n.get("name")),
                        whole(n.get("quantity")), whole(n.get("unitPrice"))));
            }
        }
        return new Proposal(text(root.get("transcript")), whole(root.get("amount")), text(root.get("paymentMethod")),
                text(root.get("customerInfo")), text(root.get("merchantInfo")), items);
    }

    static String text(JsonNode n) {
        return n != null && n.isTextual() ? n.asText() : null;
    }

    /** A whole number: a JSON integer, a float with no fraction, or digits in a string; anything else is null. */
    static Long whole(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isIntegralNumber()) return n.canConvertToLong() ? n.longValue() : null;
        if (n.isFloatingPointNumber()) {
            double d = n.doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 9.0e18 ? (long) d : null;
        }
        if (n.isTextual()) {
            String s = n.asText().trim();
            if (s.matches("-?\\d{1,18}")) return Long.parseLong(s);
        }
        return null;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 300 ? one.substring(0, 300) + "…" : one;
    }
}
