package com.bejo.web;

import com.bejo.voice.service.ScriptedLiveUpstream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Live "say the sale" over a real WebSocket, through the whole filter chain (API token, JWT), with a scripted model standing in
 * for Gemini Live. The state machine behind it is covered by {@code LiveSaleTest}; this checks the wire: who is let in, what
 * frames come back, and that the connection ends.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VoiceLiveApiIntegrationTest {

    private static final String API_TOKEN = "test-api-token";
    private static final String START = """
            {"type":"start","products":[{"id":12,"name":"Kopi Susu","price":18000},{"id":7,"name":"Roti Bakar","price":15000}]}""";

    @LocalServerPort private int port;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.bejo.notification.InMemoryEmailSender email;
    @Autowired private ScriptedLiveUpstream model;
    @Autowired private com.bejo.voice.service.VoiceLiveService live;

    private String token;

    @BeforeEach
    void signIn() throws Exception {
        model.reset();
        token = newUserToken();
    }

    private String newUserToken() throws Exception {
        String username = "vlive_" + System.nanoTime() % 1_000_000_000L;
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", "password1");
        body.put("name", "Test User");
        body.put("email", username + "@example.com");
        body.put("businessName", "Warung " + username);
        mockMvc.perform(post("/api/users/register").header("X-API-TOKEN", API_TOKEN)
                .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(body))).andExpect(status().isCreated());
        EmailVerificationTestSupport.verifyEmail(mockMvc, objectMapper, email, username, username + "@example.com");
        String login = mockMvc.perform(post("/api/auth/login").header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "password1", "deviceId", "dev-1"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login).at("/data/token").asText();
    }

    /** The app's end of the connection: every frame it is sent, in order, and how the connection ended. */
    private final class App extends TextWebSocketHandler {
        final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        WebSocketSession session;

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
            frames.add(objectMapper.readTree(message.getPayload()));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            closed.complete(status);
        }

        JsonNode next() throws InterruptedException {
            JsonNode frame = frames.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("a frame within 5 s").isNotNull();
            return frame;
        }

        /** The next frame of this type, skipping the ones before it. */
        JsonNode nextOf(String type) throws InterruptedException {
            while (true) {
                JsonNode frame = next();
                if (type.equals(frame.path("type").asText())) return frame;
            }
        }

        void send(String text) throws Exception { session.sendMessage(new TextMessage(text)); }

        void sendAudio(int bytes) throws Exception { session.sendMessage(new BinaryMessage(new byte[bytes])); }
    }

    private App connect(String apiToken, String bearer) throws Exception {
        App app = new App();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (apiToken != null) headers.add("X-API-TOKEN", apiToken);
        if (bearer != null) headers.add("Authorization", "Bearer " + bearer);
        app.session = new StandardWebSocketClient()
                .execute(app, headers, URI.create("ws://localhost:" + port + "/api/voice/transaction/live"))
                .get(10, TimeUnit.SECONDS);
        return app;
    }

    private App connect() throws Exception {
        return connect(API_TOKEN, token);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).as("happened within 5 s").isTrue();
    }

    // ── who is let in ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void refusesAConnectionWithoutTheApiToken() {
        assertThatThrownBy(() -> connect(null, token)).isInstanceOf(ExecutionException.class).hasMessageContaining("401");
    }

    @Test
    void refusesAConnectionWithoutALoginAsUnauthorizedNotAsMissing() {
        assertThatThrownBy(() -> connect(API_TOKEN, null)).isInstanceOf(ExecutionException.class).hasMessageContaining("401");
        assertThatThrownBy(() -> connect(API_TOKEN, "not-a-jwt")).isInstanceOf(ExecutionException.class).hasMessageContaining("401");
        assertThat(model.opened()).isZero();
    }

    // ── a sale, start to finish ──────────────────────────────────────────────────────────────────────────────

    @Test
    void carriesASaleFromTheMicrophoneToTheConfirmationWhileItIsStillBeingSaid() throws Exception {
        model.readyAtOnce();
        App app = connect();

        app.send(START);
        assertThat(app.next().get("type").asText()).isEqualTo("ready");
        assertThat(model.catalogue()).extracting(c -> c.name()).containsExactly("Kopi Susu", "Roti Bakar");

        app.sendAudio(64_000);
        await(() -> model.audioBytes() >= 64_000);
        model.phrase("dua kopi susu", "add_item", "{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12,\"unit_price\":1}");

        // while the merchant is still talking: the first product is already on the app's screen, priced from its own list
        JsonNode draft = app.nextOf("draft");
        while (draft.get("items").isEmpty()) draft = app.nextOf("draft");
        assertThat(draft.get("understood").asBoolean()).isTrue();
        assertThat(draft.at("/items/0/productId").asLong()).isEqualTo(12);
        assertThat(draft.at("/items/0/name").asText()).isEqualTo("Kopi Susu");
        assertThat(draft.at("/items/0/quantity").asInt()).isEqualTo(2);
        assertThat(draft.at("/items/0/unitPrice").asLong()).isEqualTo(18000);
        assertThat(draft.at("/items/0/isNew").asBoolean()).isFalse();

        app.send("{\"type\":\"end\"}");
        JsonNode done = app.nextOf("done");

        assertThat(done.get("understood").asBoolean()).isTrue();
        assertThat(done.get("items")).hasSize(1);
        assertThat(done.get("transcript").asText()).isEqualTo("dua kopi susu");
        assertThat(done.has("amount")).isTrue();   // nulls are written out, as in the one-shot answer
        assertThat(done.get("amount").isNull()).isTrue();
        assertThat(app.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(1000);
        assertThat(model.audioEnded()).isTrue();
        assertThat(model.closed()).isTrue();
    }

    // ── when it cannot go on ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void aFrameThatIsNotJsonIsToldSoAndTheConnectionEnds() throws Exception {
        App app = connect();

        app.send("this is not json");

        JsonNode error = app.nextOf("error");
        assertThat(error.get("code").asText()).isEqualTo("bad_request");
        assertThat(app.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(1000);
    }

    @Test
    void anUnknownFrameTypeAndAnInvalidProductListAreBadRequests() throws Exception {
        App unknown = connect();
        unknown.send("{\"type\":\"dance\"}");
        assertThat(unknown.nextOf("error").get("code").asText()).isEqualTo("bad_request");

        // a user may have only one session at a time; the first one is over by now
        await(() -> unknown.closed.isDone() && live.openSessions() == 0);
        App invalid = connect();
        invalid.send("{\"type\":\"start\",\"products\":[{\"id\":1,\"name\":\"\",\"price\":1}]}");
        assertThat(invalid.nextOf("error").get("code").asText()).isEqualTo("bad_request");
        assertThat(model.opened()).isZero();
    }

    @Test
    void aSecondSessionForTheSameUserIsRefusedWhileTheFirstIsOpen() throws Exception {
        App first = connect();
        App second = connect();

        JsonNode error = second.nextOf("error");

        assertThat(error.get("code").asText()).isEqualTo("rate_limited");
        assertThat(error.get("message").asText()).isNotBlank();
        assertThat(second.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(1000);
        assertThat(first.closed.isDone()).isFalse();   // the first one is untouched
        first.session.close();
    }

    @Test
    void eachSessionIsOneRequestAgainstTheUsersMinuteBudget() throws Exception {
        model.readyAtOnce();
        // the budget is 6 a minute (the default): six sessions are fine, the seventh is turned away
        for (int i = 0; i < 6; i++) {
            App app = connect();
            app.send(START);
            assertThat(app.next().get("type").asText()).isEqualTo("ready");
            app.session.close();
            await(() -> model.closed() && live.openSessions() == 0);
            model.reset();
            model.readyAtOnce();
        }

        App seventh = connect();
        seventh.send(START);

        assertThat(seventh.nextOf("error").get("code").asText()).isEqualTo("rate_limited");
        assertThat(model.opened()).isZero();
    }

    @Test
    void aModelThatIsNotAvailableIsAnErrorFrameTheAppFallsBackFrom() throws Exception {
        model.isUnavailable();
        App app = connect();

        app.send(START);

        JsonNode error = app.nextOf("error");
        assertThat(error.get("code").asText()).isEqualTo("unavailable");
        assertThat(error.get("message").asText()).isNotBlank();
        assertThat(app.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(1000);
    }

    @Test
    void theAppHangingUpMidSaleStopsTheModelSession() throws Exception {
        model.readyAtOnce();
        App app = connect();
        app.send(START);
        assertThat(app.next().get("type").asText()).isEqualTo("ready");
        app.sendAudio(32_000);
        await(() -> model.audioBytes() >= 32_000);

        app.session.close();

        await(() -> model.closed() && live.openSessions() == 0);
        assertThat(model.opened()).isEqualTo(1);
    }
}
