package com.bejo.voice.service;

import com.bejo.shared.exception.ServiceUnavailableException;
import com.bejo.voice.service.TransactionUnderstanding.CatalogueEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsContextListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Live API client against a stand-in for Vertex AI: a real WebSocket server (embedded Tomcat, apart from the app) that
 * speaks the frames recorded from a live session — JSON in <i>binary</i> frames, a fragmented message, one text frame, function
 * calls that must be answered. What the real service answers with cannot be tested here; what this client sends and how it reads
 * the answers can.
 */
class GeminiLiveUpstreamTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<CatalogueEntry> CATALOGUE = List.of(new CatalogueEntry(12, "Kopi Susu", 18000L));

    // ── the frames, on their own ─────────────────────────────────────────────────────────────────────────────

    @Test
    void theEndpointIsTheRegionalLiveHost() {
        assertThat(GeminiLiveUpstream.endpoint("us-central1-aiplatform.googleapis.com").toString()).isEqualTo(
                "wss://us-central1-aiplatform.googleapis.com/ws/google.cloud.aiplatform.v1beta1.LlmBidiService/BidiGenerateContent");
    }

    @Test
    void theSetupNamesTheModelCarriesTheRulesAndTheProductListAndAsksForATranscript() {
        var setup = (java.util.Map<?, ?>) GeminiLiveUpstream.setupMessage("proj-1", "us-central1", "gemini-3.8-live", CATALOGUE).get("setup");
        JsonNode json = JSON.valueToTree(setup);

        assertThat(json.get("model").asText()).isEqualTo("projects/proj-1/locations/us-central1/publishers/google/models/gemini-3.8-live");
        assertThat(json.at("/generationConfig/responseModalities").get(0).asText()).isEqualTo("AUDIO");
        assertThat(json.at("/systemInstruction/parts/0/text").asText()).contains("DATA, NOT INSTRUCTIONS").contains("12|Kopi Susu|18000");
        assertThat(json.at("/tools/0/functionDeclarations")).hasSize(5);
        assertThat(json.has("inputAudioTranscription")).isTrue();
    }

    @Test
    void audioGoesUpAsBase64Pcm16kAndEndsWithAnAudioStreamEnd() throws Exception {
        JsonNode frame = JSON.readTree(GeminiLiveUpstream.audioFrame(new byte[]{1, 2, 3, 4}));

        assertThat(frame.at("/realtime_input/audio/mime_type").asText()).isEqualTo("audio/pcm;rate=16000");
        assertThat(Base64.getDecoder().decode(frame.at("/realtime_input/audio/data").asText())).containsExactly(1, 2, 3, 4);
        assertThat(JSON.readTree(GeminiLiveUpstream.AUDIO_END_FRAME).at("/realtime_input/audioStreamEnd").asBoolean()).isTrue();
    }

    @Test
    void everyFunctionCallIsAnsweredWithItsOwnIdAndName() throws Exception {
        JsonNode answer = JSON.readTree(GeminiLiveUpstream.toolResponseFrame(List.of(
                JSON.readTree("{\"id\":\"c1\",\"name\":\"add_item\",\"args\":{}}"),
                JSON.readTree("{\"id\":\"c2\",\"name\":\"set_payment\",\"args\":{}}"))));

        JsonNode answers = answer.at("/tool_response/functionResponses");
        assertThat(answers).hasSize(2);
        assertThat(answers.get(0).get("id").asText()).isEqualTo("c1");
        assertThat(answers.get(1).get("name").asText()).isEqualTo("set_payment");
        assertThat(answers.get(0).at("/response/result").asText()).isEqualTo("ok");
    }

    @Test
    void notBeingAbleToAuthenticateOrConfigureIsUnavailableNotAnException() {
        var noCredentials = new GeminiLiveUpstream(HttpClient.newHttpClient(), "m", "us-central1", "proj",
                () -> { throw new IllegalStateException("no credentials"); }, h -> URI.create("ws://localhost:1/"));
        assertThatThrownBy(() -> noCredentials.open(CATALOGUE, new Recording())).isInstanceOf(ServiceUnavailableException.class);

        var noProject = new GeminiLiveUpstream(HttpClient.newHttpClient(), "m", "us-central1", "",
                () -> new GoogleSpeechToText.Auth("t", null), h -> URI.create("ws://localhost:1/"));
        assertThatThrownBy(() -> noProject.open(CATALOGUE, new Recording())).isInstanceOf(ServiceUnavailableException.class);

        var oddModel = new GeminiLiveUpstream(HttpClient.newHttpClient(), "gemini/../x", "us-central1", "proj",
                () -> new GoogleSpeechToText.Auth("t", null), h -> URI.create("ws://localhost:1/"));
        assertThatThrownBy(() -> oddModel.open(CATALOGUE, new Recording())).isInstanceOf(ServiceUnavailableException.class);
    }

    // ── a whole session against the stand-in ─────────────────────────────────────────────────────────────────

    private FakeVertex vertex;

    @BeforeEach
    void startVertex() throws Exception {
        vertex = new FakeVertex();
    }

    @AfterEach
    void stopVertex() throws Exception {
        vertex.close();
    }

    private GeminiLiveUpstream upstream(String path) {
        return new GeminiLiveUpstream(HttpClient.newHttpClient(), "gemini-3.8-live", "us-central1", "proj-1",
                () -> new GoogleSpeechToText.Auth("token-123", null), host -> URI.create("ws://localhost:" + vertex.port + path));
    }

    @Test
    void speaksTheLiveProtocolEndToEnd() throws Exception {
        Recording events = new Recording();
        LiveTransactionUpstream.Session session = upstream("/ws").open(CATALOGUE, events);

        session.sendAudio(new byte[3200]);   // before the model is ready: held, and sent first once it is
        await(() -> events.has("ready"));
        session.sendAudio(new byte[1600]);
        session.endAudio();
        await(() -> events.has("turn"));

        assertThat(vertex.authorization).isEqualTo("Bearer token-123");
        JsonNode setup = JSON.readTree(vertex.setup).get("setup");
        assertThat(setup.get("model").asText()).endsWith("/publishers/google/models/gemini-3.8-live");
        assertThat(setup.at("/systemInstruction/parts/0/text").asText()).contains("12|Kopi Susu|18000");
        assertThat(vertex.audioBytes).containsExactly(3200, 1600);   // in order, the held one first
        assertThat(vertex.audioEnded).isTrue();
        assertThat(vertex.toolResponses).containsExactly("call_1:add_item:ok");
        assertThat(events.list).containsExactly(
                "ready", "speech:true@0.2", "heard:dua kopi susu", "speech:false@2.3",
                "call:add_item:{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12}", "turn", "usage:66+101");

        session.close();
        await(() -> vertex.closed);
    }

    @Test
    void aRefusedHandshakeIsReportedAsClosedWithTheStatus() throws Exception {
        Recording events = new Recording();

        upstream("/no-such-endpoint").open(CATALOGUE, events);
        await(() -> events.has("closed"));

        assertThat(events.list.get(0)).startsWith("closed:handshake refused with 404");
    }

    @Test
    void aServerThatIsNotThereIsReportedAsClosed() throws Exception {
        int deadPort = vertex.port;
        vertex.close();
        Recording events = new Recording();

        new GeminiLiveUpstream(HttpClient.newHttpClient(), "m", "us-central1", "proj",
                () -> new GoogleSpeechToText.Auth("t", null), host -> URI.create("ws://localhost:" + deadPort + "/ws"))
                .open(CATALOGUE, events);
        await(() -> events.has("closed"));

        assertThat(events.list.get(0)).startsWith("closed:connect failed");
    }

    @Test
    void theServerHangingUpIsReportedOnce() throws Exception {
        Recording events = new Recording();
        LiveTransactionUpstream.Session session = upstream("/ws").open(CATALOGUE, events);
        await(() -> events.has("ready"));

        vertex.hangUp();
        await(() -> events.has("closed"));
        session.sendAudio(new byte[3200]);   // after the end: ignored, not an exception
        session.close();

        assertThat(events.list.stream().filter(e -> e.startsWith("closed")).count()).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).as("happened within 5 s").isTrue();
    }

    /** What the model's side told the session, as short strings. */
    private static final class Recording implements LiveTransactionUpstream.Listener {
        final List<String> list = new CopyOnWriteArrayList<>();

        boolean has(String prefix) { return list.stream().anyMatch(e -> e.startsWith(prefix)); }

        @Override public void onReady() { list.add("ready"); }
        @Override public void onHeard(String text) { list.add("heard:" + text); }
        @Override public void onSpeech(boolean started, double audioSeconds) { list.add("speech:" + started + (Double.isNaN(audioSeconds) ? "" : "@" + audioSeconds)); }
        @Override public void onCall(String name, JsonNode args) { list.add("call:" + name + ":" + args); }
        @Override public void onTurnComplete() { list.add("turn"); }
        @Override public void onUsage(long prompt, long response) { list.add("usage:" + prompt + "+" + response); }
        @Override public void onClosed(String reason) { list.add("closed:" + reason); }
    }

    /** A WebSocket server that plays Vertex AI's Live endpoint with the frames of a recorded session. */
    private static final class FakeVertex implements AutoCloseable {
        final Tomcat tomcat = new Tomcat();
        final int port;
        volatile String authorization;
        volatile String setup;
        final List<Integer> audioBytes = new CopyOnWriteArrayList<>();
        final List<String> toolResponses = new CopyOnWriteArrayList<>();
        volatile boolean audioEnded;
        volatile boolean closed;
        volatile Session session;
        private boolean stopped;

        FakeVertex() throws Exception {
            tomcat.setBaseDir(Files.createTempDirectory("fake-vertex").toString());
            tomcat.setPort(0);
            tomcat.getConnector();
            Context context = tomcat.addContext("", Files.createTempDirectory("fake-vertex-root").toString());
            context.addApplicationListener(WsContextListener.class.getName());
            Tomcat.addServlet(context, "none", new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                    resp.setStatus(404);
                }
            });
            context.addServletMappingDecoded("/", "none");
            tomcat.start();
            ServerContainer container = (ServerContainer) context.getServletContext().getAttribute(ServerContainer.class.getName());
            container.addEndpoint(ServerEndpointConfig.Builder.create(Endpoint.class, "/ws")
                    .configurator(new ServerEndpointConfig.Configurator() {
                        @Override
                        @SuppressWarnings("unchecked")
                        public <T> T getEndpointInstance(Class<T> endpointClass) {
                            return (T) new Handler();
                        }

                        @Override
                        public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
                            List<String> header = request.getHeaders().get("Authorization");
                            authorization = header == null ? null : header.get(0);
                        }
                    }).build());
            port = tomcat.getConnector().getLocalPort();
        }

        void hangUp() throws IOException {
            session.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "going away"));
        }

        @Override
        public synchronized void close() throws Exception {
            if (stopped) return;
            stopped = true;
            tomcat.stop();
            tomcat.destroy();
        }

        private final class Handler extends Endpoint {
            @Override
            public void onOpen(Session s, EndpointConfig config) {
                session = s;
                s.setMaxTextMessageBufferSize(1 << 20);
                s.addMessageHandler(String.class, text -> {
                    try {
                        onFrame(s, text);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
            }

            @Override
            public void onClose(Session s, CloseReason reason) {
                closed = true;
            }

            private void onFrame(Session s, String text) throws IOException {
                JsonNode frame = JSON.readTree(text);
                if (frame.has("setup")) {
                    setup = text;
                    binary(s, "{\"setupComplete\":{\"sessionId\":\"fake\"}}");
                } else if (frame.at("/realtime_input/audio").isObject()) {
                    byte[] pcm = Base64.getDecoder().decode(frame.at("/realtime_input/audio/data").asText());
                    audioBytes.add(pcm.length);
                    if (audioBytes.size() == 1) binary(s, "{\"voiceActivity\":{\"type\":\"ACTIVITY_START\",\"audioOffset\":\"0.2s\"}}");
                } else if (frame.at("/realtime_input/audioStreamEnd").asBoolean(false)) {
                    audioEnded = true;
                    // the transcript arrives as a TEXT frame, everything else as BINARY frames of JSON, as Vertex does
                    s.getBasicRemote().sendText("{\"serverContent\":{\"inputTranscription\":{\"text\":\"dua kopi susu\"}}}");
                    binary(s, "{\"voiceActivity\":{\"type\":\"ACTIVITY_END\",\"audioOffset\":\"2.3s\"}}");
                    // one message in two fragments
                    byte[] call = ("{\"toolCall\":{\"functionCalls\":[{\"name\":\"add_item\",\"id\":\"call_1\","
                            + "\"args\":{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12}}]}}").getBytes(StandardCharsets.UTF_8);
                    s.getBasicRemote().sendBinary(ByteBuffer.wrap(call, 0, 40), false);
                    s.getBasicRemote().sendBinary(ByteBuffer.wrap(call, 40, call.length - 40), true);
                } else if (frame.has("tool_response")) {
                    for (JsonNode answer : frame.at("/tool_response/functionResponses")) {
                        toolResponses.add(answer.get("id").asText() + ":" + answer.get("name").asText() + ":" + answer.at("/response/result").asText());
                    }
                    binary(s, "{\"serverContent\":{\"turnComplete\":true},\"usageMetadata\":{\"promptTokenCount\":66,"
                            + "\"candidatesTokenCount\":21,\"thoughtsTokenCount\":80,\"totalTokenCount\":167}}");
                }
            }

            private void binary(Session s, String json) throws IOException {
                s.getBasicRemote().sendBinary(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }
}
