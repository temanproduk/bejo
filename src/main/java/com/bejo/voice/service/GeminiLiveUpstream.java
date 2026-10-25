package com.bejo.voice.service;

import com.bejo.shared.exception.ServiceUnavailableException;
import com.bejo.voice.service.TransactionUnderstanding.CatalogueEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Gemini Live API on Vertex AI: one WebSocket per sale, authenticated as the service's own account through Application
 * Default Credentials (no key to store — the organisation forbids creating one), with the JDK's built-in WebSocket client (no
 * SDK in the image), the same way {@link GeminiTransactionUnderstanding} talks to {@code generateContent}.
 *
 * <p>The protocol, as the Google SDK speaks it (checked against a live session): the first frame is {@code setup} (model, the
 * instruction with the merchant's product list, the functions the model may call, and a request for a transcript of what it
 * hears); the server answers {@code setupComplete}; then audio goes up as {@code realtime_input} frames of base64 PCM while the
 * server sends back, as <b>binary</b> frames of JSON, {@code voiceActivity}, {@code serverContent.inputTranscription},
 * {@code toolCall}, {@code serverContent.turnComplete} and {@code usageMetadata}. Every function call must be answered with a
 * {@code tool_response} or the model waits for it; this class does that, so {@link LiveSale} only sees what was asked for. The
 * model can only answer in audio (that is what the Live models offer), so its voice is simply dropped; the instruction asks it
 * to stay silent.
 *
 * <p>The JDK client allows one send at a time, so frames are chained, never concurrent. Audio sent before {@code setupComplete}
 * is held. Nothing the server sends is logged but its close code and Google's own close reason; the audio, the transcript and
 * the product list are sent and forgotten.
 */
@Component
@Profile("!test")   // the functional tests plug in ScriptedLiveUpstream (test sources) instead
public class GeminiLiveUpstream implements LiveTransactionUpstream {

    private static final Logger log = LoggerFactory.getLogger(GeminiLiveUpstream.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The Live API is served on this API version. */
    static final String API_VERSION = "v1beta1";
    private static final String AUDIO_MIME_TYPE = "audio/pcm;rate=16000";
    /** A frame bigger than this is not a message of ours: the session is dropped. */
    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;

    private final HttpClient http;
    private final String model;
    private final String location;
    private final String configuredProject;
    private final Supplier<GoogleSpeechToText.Auth> auth;
    private final Function<String, URI> endpoint;

    @Autowired
    public GeminiLiveUpstream(
            @Value("${app.voice.live.model:gemini-3.8-live}") String model,
            @Value("${app.voice.live.location:us-central1}") String location,
            @Value("${app.voice.transaction.project-id:}") String projectId) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), model, location, projectId,
                GoogleSpeechToText::applicationDefaultAuth, GeminiLiveUpstream::endpoint);
    }

    // Tests give it a fixed token and an endpoint of their own
    GeminiLiveUpstream(HttpClient http, String model, String location, String projectId,
                       Supplier<GoogleSpeechToText.Auth> auth, Function<String, URI> endpoint) {
        this.http = http;
        this.model = model;
        this.location = location;
        this.configuredProject = projectId;
        this.auth = auth;
        this.endpoint = endpoint;
    }

    static URI endpoint(String host) {
        return URI.create("wss://" + host + "/ws/google.cloud.aiplatform." + API_VERSION + ".LlmBidiService/BidiGenerateContent");
    }

    @Override
    public Session open(List<CatalogueEntry> catalogue, Listener listener) {
        GoogleSpeechToText.Auth a;
        try {
            a = auth.get();
        } catch (RuntimeException e) {
            log.error("[VOICE] Gemini Live cannot authenticate: {}", e.getMessage());
            throw new ServiceUnavailableException(GeminiTransactionUnderstanding.UNAVAILABLE);
        }
        String project = StringUtils.hasText(configuredProject) ? configuredProject.trim() : a.quotaProject();
        if (!StringUtils.hasText(project)) {
            log.warn("[VOICE] Gemini Live is not configured: set GCP_PROJECT_ID (app.voice.transaction.project-id)");
            throw new ServiceUnavailableException(GeminiTransactionUnderstanding.UNAVAILABLE);
        }
        if (!GeminiTransactionUnderstanding.SAFE_SEGMENT.matcher(project).matches()
                || !GeminiTransactionUnderstanding.SAFE_SEGMENT.matcher(location).matches()
                || !GeminiTransactionUnderstanding.SAFE_SEGMENT.matcher(model).matches()) {
            log.error("[VOICE] Gemini Live is misconfigured: project, location or model has characters a URL path should not");
            throw new ServiceUnavailableException(GeminiTransactionUnderstanding.UNAVAILABLE);
        }
        String setup;
        try {
            setup = JSON.writeValueAsString(setupMessage(project, location, model, catalogue));
        } catch (JsonProcessingException e) {
            throw new ServiceUnavailableException(GeminiTransactionUnderstanding.UNAVAILABLE);
        }

        LiveSession session = new LiveSession(listener, setup);
        WebSocket.Builder builder = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + a.token());
        if (a.quotaProject() != null) builder.header("x-goog-user-project", a.quotaProject());
        builder.buildAsync(endpoint.apply(GeminiTransactionUnderstanding.host(location)), session)
                .whenComplete((ws, error) -> { if (error != null) session.connectFailed(error); });
        return session;
    }

    // ── the frames we send ───────────────────────────────────────────────────────────────────────────────────

    /** The first frame: which model, how it behaves, what it may call. */
    static Map<String, Object> setupMessage(String project, String location, String model, List<CatalogueEntry> catalogue) {
        return Map.of("setup", Map.of(
                "model", "projects/" + project + "/locations/" + location + "/publishers/google/models/" + model,
                "generationConfig", Map.of("responseModalities", List.of("AUDIO")),
                "systemInstruction", Map.of("parts", List.of(Map.of("text", TransactionPrompt.liveInstruction(catalogue)))),
                "tools", TransactionPrompt.liveTools(),
                "inputAudioTranscription", Map.of()));
    }

    static String audioFrame(byte[] pcm) {
        return "{\"realtime_input\":{\"audio\":{\"data\":\"" + Base64.getEncoder().encodeToString(pcm)
                + "\",\"mime_type\":\"" + AUDIO_MIME_TYPE + "\"}}}";
    }

    static final String AUDIO_END_FRAME = "{\"realtime_input\":{\"audioStreamEnd\":true}}";

    static String toolResponseFrame(List<JsonNode> calls) throws JsonProcessingException {
        List<Map<String, Object>> answers = new ArrayList<>();
        for (JsonNode call : calls) {
            answers.add(Map.of("id", call.path("id").asText(""), "name", call.path("name").asText(""),
                    "response", Map.of("result", "ok")));
        }
        return JSON.writeValueAsString(Map.of("tool_response", Map.of("functionResponses", answers)));
    }

    // ── one session ──────────────────────────────────────────────────────────────────────────────────────────

    private final class LiveSession implements Session, WebSocket.Listener {

        private final Listener listener;
        private final String setup;
        private final Object lock = new Object();

        // guarded by lock
        private WebSocket ws;
        private boolean ready;
        private boolean closed;
        private final List<String> held = new ArrayList<>();   // audio sent before setupComplete
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

        // touched only by the receive callbacks, which the client runs one at a time
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

        LiveSession(Listener listener, String setup) {
            this.listener = listener;
            this.setup = setup;
        }

        // ── Session ──

        @Override
        public void sendAudio(byte[] pcm) {
            send(audioFrame(pcm), true);
        }

        @Override
        public void endAudio() {
            send(AUDIO_END_FRAME, true);
        }

        @Override
        public void close() {
            WebSocket w;
            CompletableFuture<Void> last;
            synchronized (lock) {
                if (closed) return;
                closed = true;
                held.clear();
                w = ws;
                last = tail;
            }
            if (w != null) {
                // after whatever is still being sent: the client allows one send at a time
                last.whenComplete((v, e) -> w.sendClose(WebSocket.NORMAL_CLOSURE, "").whenComplete((x, err) -> { if (err != null) w.abort(); }));
            }
        }

        /** Sends one frame after the one before it. {@code audio}: held until the model is ready; the setup frame is not. */
        private void send(String frame, boolean audio) {
            synchronized (lock) {
                if (closed) return;
                // The connection takes a second or two to come up and the app is already talking: hold the audio, in order
                if (audio && !ready) {
                    held.add(frame);
                    return;
                }
                enqueue(frame);
            }
        }

        /** With the lock held: the client allows one send at a time, so each frame goes out when the one before it has. */
        private void enqueue(String frame) {
            WebSocket w = ws;
            tail = tail.thenCompose(v -> w.sendText(frame, true).thenApply(sent -> (Void) null));
            tail.whenComplete((v, e) -> { if (e != null) lost("send failed: " + e.getClass().getSimpleName()); });
        }

        private void lost(String reason) {
            boolean first;
            synchronized (lock) {
                first = !closed;
                closed = true;
                held.clear();
            }
            if (first) {
                WebSocket w = ws;
                if (w != null) w.abort();
                listener.onClosed(reason);
            }
        }

        void connectFailed(Throwable error) {
            Throwable cause = error.getCause() != null && error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
            String why = cause instanceof WebSocketHandshakeException h
                    // 401/403 is "no permission / API not enabled", 404 "no such model or location": the status is Google's, safe to log
                    ? "handshake refused with " + h.getResponse().statusCode()
                    : "connect failed: " + cause.getClass().getSimpleName();
            log.error("[VOICE] Gemini Live: {}", why);
            lost(why);
        }

        // ── WebSocket.Listener ──

        @Override
        public void onOpen(WebSocket webSocket) {
            synchronized (lock) {
                if (closed) { webSocket.abort(); return; }
                ws = webSocket;
            }
            send(setup, false);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (text.length() > MAX_FRAME_BYTES) {
                text.setLength(0);
                lost("frame too large");
            } else if (last) {
                String frame = text.toString();
                text.setLength(0);
                handle(frame);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] piece = new byte[data.remaining()];
            data.get(piece);
            binary.writeBytes(piece);
            if (binary.size() > MAX_FRAME_BYTES) {
                binary.reset();
                lost("frame too large");
            } else if (last) {
                String frame = binary.toString(StandardCharsets.UTF_8);
                binary.reset();
                handle(frame);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // the reason is Google's own text (a quota or permission message); never anything the merchant said
            log.warn("[VOICE] Gemini Live closed the session: {} {}", statusCode, abbreviate(reason));
            lost("closed " + statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            lost("error: " + error.getClass().getSimpleName());
        }

        // ── what the server says ──

        private void handle(String frame) {
            JsonNode message;
            try {
                message = JSON.readTree(frame);
            } catch (JsonProcessingException e) {
                log.debug("[VOICE] Gemini Live sent a frame that is not JSON");
                return;
            }
            if (message == null || !message.isObject()) return;

            if (message.has("setupComplete")) {
                synchronized (lock) {
                    if (ready) return;
                    ready = true;
                    // under the lock, so that audio sent from now on cannot overtake the audio that was held
                    if (!closed) held.forEach(this::enqueue);
                    held.clear();
                }
                listener.onReady();
            }
            JsonNode voice = message.path("voiceActivity");
            String activity = voice.path("type").asText("");
            if (activity.equals("ACTIVITY_START")) listener.onSpeech(true, offsetSeconds(voice));
            else if (activity.equals("ACTIVITY_END")) listener.onSpeech(false, offsetSeconds(voice));

            JsonNode content = message.path("serverContent");
            String heard = content.path("inputTranscription").path("text").asText("");
            if (!heard.isEmpty()) listener.onHeard(heard);

            JsonNode calls = message.path("toolCall").path("functionCalls");
            if (calls.isArray() && !calls.isEmpty()) {
                List<JsonNode> asked = new ArrayList<>();
                calls.forEach(asked::add);
                for (JsonNode call : asked) listener.onCall(call.path("name").asText(null), call.get("args"));
                try {
                    send(toolResponseFrame(asked), false);
                } catch (JsonProcessingException e) {
                    lost("could not answer a function call");
                }
            }

            if (content.path("turnComplete").asBoolean(false)) listener.onTurnComplete();

            JsonNode usage = message.path("usageMetadata");
            if (usage.isObject()) {
                listener.onUsage(usage.path("promptTokenCount").asLong(0),
                        usage.path("candidatesTokenCount").asLong(0) + usage.path("thoughtsTokenCount").asLong(0));
            }
        }
    }

    /** {@code "audioOffset": "2.360s"} as 2.36; NaN when absent or not in that form. */
    static double offsetSeconds(JsonNode voiceActivity) {
        String text = voiceActivity.path("audioOffset").asText("");
        if (!text.endsWith("s")) return Double.NaN;
        try {
            return Double.parseDouble(text.substring(0, text.length() - 1));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 300 ? one.substring(0, 300) + "…" : one;
    }
}
