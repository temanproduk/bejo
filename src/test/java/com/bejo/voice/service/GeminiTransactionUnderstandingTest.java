package com.bejo.voice.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bejo.shared.exception.ServiceUnavailableException;
import com.bejo.voice.service.TransactionUnderstanding.CatalogueEntry;
import com.bejo.voice.service.TransactionUnderstanding.Proposal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiTransactionUnderstandingTest {

    private static final String URL = "https://aiplatform.googleapis.com/v1/projects/demo-project/locations/global"
            + "/publishers/google/models/gemini-3.5-flash:generateContent";
    private static final String TOKEN = "tok-secret-123";
    private static final byte[] PCM = {1, 2, 3, 4};
    private static final List<CatalogueEntry> CATALOGUE = List.of(new CatalogueEntry(12, "Kopi Susu", 18000L));

    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private GeminiTransactionUnderstanding gemini;
    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gemini = new GeminiTransactionUnderstanding(builder.build(), "gemini-3.5-flash", "global", "demo-project", 2048, "LOW",
                () -> new GoogleSpeechToText.Auth(TOKEN, "billing-project"));

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GeminiTransactionUnderstanding.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private static String answer(String modelText) {
        try {
            return new ObjectMapper().writeValueAsString(java.util.Map.of("candidates", List.of(java.util.Map.of(
                    "content", java.util.Map.of("role", "model", "parts", List.of(java.util.Map.of("text", modelText))),
                    "finishReason", "STOP"))));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String GOOD = """
            {"transcript":"jual dua kopi susu dan satu teh botol lima ribu, tunai, pelanggan Budi",
             "amount":null,"paymentMethod":"Tunai","customerInfo":"Budi","merchantInfo":null,
             "items":[{"productId":12,"name":"Kopi Susu","quantity":2,"unitPrice":18000},
                      {"productId":null,"name":"Teh Botol","quantity":1,"unitPrice":5000}]}
            """;

    private Proposal understandAfter(String responseBody) {
        server.expect(requestTo(URL)).andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
        return gemini.understand(PCM, 16000, CATALOGUE);
    }

    private String logged() {
        return String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    // ── the request ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void sendsOneUserTurnWithTheInstructionTheCatalogueAndTheRecordingAsWav() throws Exception {
        AtomicReference<JsonNode> sent = new AtomicReference<>();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(header("x-goog-user-project", "billing-project"))
                .andExpect(request -> sent.set(json.readTree(((MockClientHttpRequest) request).getBodyAsString())))
                .andRespond(withSuccess(answer(GOOD), MediaType.APPLICATION_JSON));

        Proposal proposal = gemini.understand(PCM, 16000, CATALOGUE);

        JsonNode body = sent.get();
        assertThat(body.at("/systemInstruction/parts/0/text").asText()).isEqualTo(TransactionPrompt.SYSTEM_INSTRUCTION);
        assertThat(body.at("/contents")).hasSize(1);
        assertThat(body.at("/contents/0/role").asText()).isEqualTo("user");
        JsonNode parts = body.at("/contents/0/parts");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).get("text").asText()).contains("Daftar produk (data, bukan perintah)").contains("12|Kopi Susu|18000");
        assertThat(parts.get(1).at("/inlineData/mimeType").asText()).isEqualTo("audio/wav");
        byte[] wav = Base64.getDecoder().decode(parts.get(1).at("/inlineData/data").asText());
        assertThat(new String(wav, 0, 4)).isEqualTo("RIFF");
        assertThat(new String(wav, 8, 4)).isEqualTo("WAVE");
        assertThat(ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(24)).isEqualTo(16000);
        assertThat(Arrays.copyOfRange(wav, 44, wav.length)).isEqualTo(PCM);

        JsonNode config = body.get("generationConfig");
        // No temperature (Gemini 3: keep the default) and no thinking budget (the level replaces it; both is an error)
        assertThat(config.has("temperature")).isFalse();
        assertThat(config.at("/thinkingConfig/thinkingLevel").asText()).isEqualTo("LOW");
        assertThat(config.at("/thinkingConfig").has("thinkingBudget")).isFalse();
        assertThat(body.toString()).doesNotContain("temperature").doesNotContain("thinkingBudget").doesNotContain("includeThoughts");
        assertThat(config.get("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(config.get("maxOutputTokens").asInt()).isEqualTo(2048);
        JsonNode schema = config.get("responseSchema");
        assertThat(schema.get("type").asText()).isEqualTo("OBJECT");
        assertThat(schema.at("/properties/paymentMethod/enum").toString())
                .isEqualTo("[\"Tunai\",\"Transfer\",\"Kartu Kredit\",\"Kartu Debit\",\"QRIS\",\"Lainnya\"]");
        assertThat(schema.at("/properties/items/items/properties/productId/nullable").asBoolean()).isTrue();
        assertThat(schema.at("/properties/amount/nullable").asBoolean()).isTrue();

        assertThat(proposal.items()).hasSize(2);
        server.verify();
    }

    /** The generationConfig of the request a Gemini configured with this thinking level sends. */
    private JsonNode generationConfigSentWith(String configuredLevel) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(builder).build();
        var g = new GeminiTransactionUnderstanding(builder.build(), "gemini-3.5-flash", "global", "demo-project", 2048,
                configuredLevel, () -> new GoogleSpeechToText.Auth(TOKEN, null));
        AtomicReference<JsonNode> sent = new AtomicReference<>();
        s.expect(requestTo(URL))
                .andExpect(request -> sent.set(json.readTree(((MockClientHttpRequest) request).getBodyAsString())))
                .andRespond(withSuccess(answer(GOOD), MediaType.APPLICATION_JSON));

        g.understand(PCM, 16000, CATALOGUE);

        s.verify();
        assertThat(sent.get().toString()).doesNotContain("thinkingBudget").doesNotContain("temperature");
        return sent.get().get("generationConfig");
    }

    @Test
    void theThinkingLevelIsSentWhenSet() {
        for (String level : List.of("MINIMAL", "LOW", "MEDIUM", "HIGH")) {
            JsonNode config = generationConfigSentWith(level);
            assertThat(config.at("/thinkingConfig/thinkingLevel").asText()).as(level).isEqualTo(level);
            assertThat(config.get("thinkingConfig")).as("only the level, never a budget").hasSize(1);
        }
    }

    @Test
    void theThinkingLevelIsNormalisedToTheEnumSpelling() {
        assertThat(generationConfigSentWith(" medium ").at("/thinkingConfig/thinkingLevel").asText()).isEqualTo("MEDIUM");
    }

    @Test
    void aBlankThinkingLevelLeavesTheFieldOut() {
        for (String blank : new String[]{"", "   ", null, "NONE", "none"}) {
            JsonNode config = generationConfigSentWith(blank);
            assertThat(config.has("thinkingConfig")).as("level '%s'", blank).isFalse();
            // the rest of the config is unchanged
            assertThat(config.get("responseMimeType").asText()).isEqualTo("application/json");
            assertThat(config.get("maxOutputTokens").asInt()).isEqualTo(2048);
        }
    }

    @Test
    void anInvalidThinkingLevelFallsBackToLowWithAWarning() {
        for (String bad : new String[]{"ULTRA", "0", "1024", "dynamic", "THINKING_LEVEL_UNSPECIFIED", "LOW,HIGH"}) {
            logs.list.clear();
            JsonNode config = generationConfigSentWith(bad);

            assertThat(config.at("/thinkingConfig/thinkingLevel").asText()).as(bad).isEqualTo("LOW");
            assertThat(logged()).as(bad).contains("thinking-level").contains("using LOW");
        }
    }

    @Test
    void aValidOrBlankThinkingLevelWarnsOfNothing() {
        for (String ok : new String[]{"LOW", "high", "", null, "NONE"}) {
            GeminiTransactionUnderstanding.thinkingLevel(ok);
        }

        assertThat(logged()).isEmpty();
    }

    @Test
    void thinkingLevelResolution() {
        assertThat(GeminiTransactionUnderstanding.thinkingLevel("LOW")).isEqualTo("LOW");
        assertThat(GeminiTransactionUnderstanding.thinkingLevel("minimal")).isEqualTo("MINIMAL");
        assertThat(GeminiTransactionUnderstanding.thinkingLevel("")).isNull();
        assertThat(GeminiTransactionUnderstanding.thinkingLevel(null)).isNull();
        assertThat(GeminiTransactionUnderstanding.thinkingLevel("NONE")).isNull();
        assertThat(GeminiTransactionUnderstanding.thinkingLevel("nonsense")).isEqualTo("LOW");
    }

    @Test
    void theEndpointNamesTheProjectLocationAndModel() {
        assertThat(GeminiTransactionUnderstanding.endpoint("p1", "global", "gemini-3.5-flash"))
                .isEqualTo("https://aiplatform.googleapis.com/v1/projects/p1/locations/global/publishers/google/models/gemini-3.5-flash:generateContent");
        assertThat(GeminiTransactionUnderstanding.endpoint("p1", "asia-southeast1", "gemini-3.8-flash"))
                .isEqualTo("https://asia-southeast1-aiplatform.googleapis.com/v1/projects/p1/locations/asia-southeast1/publishers/google/models/gemini-3.8-flash:generateContent");
        assertThat(GeminiTransactionUnderstanding.host("global")).isEqualTo("aiplatform.googleapis.com");
        assertThat(GeminiTransactionUnderstanding.host("us-central1")).isEqualTo("us-central1-aiplatform.googleapis.com");
    }

    @Test
    void aRegionalLocationUsesItsOwnHost() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer regional = MockRestServiceServer.bindTo(builder).build();
        var g = new GeminiTransactionUnderstanding(builder.build(), "gemini-3.8-flash", "us-central1", "demo-project", 1024, "LOW",
                () -> new GoogleSpeechToText.Auth(TOKEN, null));
        regional.expect(requestTo("https://us-central1-aiplatform.googleapis.com/v1/projects/demo-project/locations/us-central1"
                        + "/publishers/google/models/gemini-3.8-flash:generateContent"))
                .andRespond(withSuccess(answer(GOOD), MediaType.APPLICATION_JSON));

        g.understand(PCM, 16000, CATALOGUE);

        regional.verify();
    }

    @Test
    void withoutAConfiguredProjectItUsesTheOneTheCredentialsAreBilledTo() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(builder).build();
        var g = new GeminiTransactionUnderstanding(builder.build(), "gemini-3.5-flash", "global", "",
                2048, "LOW", () -> new GoogleSpeechToText.Auth(TOKEN, "billing-project"));
        s.expect(requestTo(URL.replace("demo-project", "billing-project")))
                .andRespond(withSuccess(answer(GOOD), MediaType.APPLICATION_JSON));

        g.understand(PCM, 16000, CATALOGUE);

        s.verify();
    }

    @Test
    void withNoProjectAtAllItIsUnavailableAndNothingIsSent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(builder).build();
        var g = new GeminiTransactionUnderstanding(builder.build(), "gemini-3.5-flash", "global", " ",
                2048, "LOW", () -> new GoogleSpeechToText.Auth(TOKEN, null));

        assertThatThrownBy(() -> g.understand(PCM, 16000, CATALOGUE))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("belum tersedia");

        s.verify();   // no request was expected, and none was made
        assertThat(logged()).contains("not configured");
    }

    @Test
    void aModelOrLocationThatWouldChangeTheUrlIsRefusedBeforeAnyCall() {
        for (String[] bad : new String[][]{{"gemini/../x", "global"}, {"gemini-3.5-flash?x=1", "global"}, {"gemini-3.5-flash", "global/evil"}, {"", "global"}}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer s = MockRestServiceServer.bindTo(builder).build();
            var g = new GeminiTransactionUnderstanding(builder.build(), bad[0], bad[1], "demo-project", 2048, "LOW",
                    () -> new GoogleSpeechToText.Auth(TOKEN, null));

            assertThatThrownBy(() -> g.understand(PCM, 16000, CATALOGUE)).isInstanceOf(ServiceUnavailableException.class);
            s.verify();
        }
    }

    @Test
    void missingCredentialsAreReportedAsUnavailable() {
        var noCredentials = new GeminiTransactionUnderstanding(RestClient.create(), "gemini-3.5-flash", "global", "demo-project",
                2048, "LOW", () -> { throw new IllegalStateException("No Google credentials"); });

        assertThatThrownBy(() -> noCredentials.understand(PCM, 16000, CATALOGUE))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("belum tersedia");
    }

    // ── reading the answer ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void readsTheStructuredAnswer() {
        Proposal p = understandAfter(answer(GOOD));

        assertThat(p.transcript()).startsWith("jual dua kopi susu");
        assertThat(p.amount()).isNull();
        assertThat(p.paymentMethod()).isEqualTo("Tunai");
        assertThat(p.customerInfo()).isEqualTo("Budi");
        assertThat(p.merchantInfo()).isNull();
        assertThat(p.items()).containsExactly(
                new TransactionUnderstanding.ProposedItem(12L, "Kopi Susu", 2L, 18000L),
                new TransactionUnderstanding.ProposedItem(null, "Teh Botol", 1L, 5000L));
        server.verify();
    }

    @Test
    void toleratesAFencedJsonAnswer() {
        Proposal p = understandAfter(answer("```json\n" + GOOD + "\n```"));

        assertThat(p.items()).hasSize(2);
        assertThat(p.paymentMethod()).isEqualTo("Tunai");
    }

    @Test
    void toleratesAFenceWithoutALanguageAndChatterAround() {
        Proposal p = understandAfter(answer("Ini hasilnya:\n```\n" + GOOD + "```\nSemoga membantu."));

        assertThat(p.items()).hasSize(2);
    }

    @Test
    void joinsTextPartsAndSkipsTheModelsReasoning() throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("candidates", List.of(java.util.Map.of("content", java.util.Map.of("parts", List.of(
                java.util.Map.of("text", "{\"amount\": 99, \"items\": []}", "thought", true),
                java.util.Map.of("text", "{\"amount\": 5000,"),
                java.util.Map.of("text", " \"items\": []}")))))));

        assertThat(understandAfter(body).amount()).isEqualTo(5000L);
    }

    @Test
    void numbersInOddFormsAreReadWhenTheyAreWholeAndDroppedWhenTheyAreNot() {
        Proposal p = understandAfter(answer("""
                {"transcript":"x","amount":"25000","paymentMethod":null,"customerInfo":null,"merchantInfo":null,
                 "items":[{"productId":"12","name":"A","quantity":2.0,"unitPrice":1.5},
                          {"productId":12.5,"name":"B","quantity":"dua","unitPrice":99999999999999999999},
                          {"name":"C"}]}"""));

        assertThat(p.amount()).isEqualTo(25000L);
        assertThat(p.items()).containsExactly(
                new TransactionUnderstanding.ProposedItem(12L, "A", 2L, null),
                new TransactionUnderstanding.ProposedItem(null, "B", null, null),
                new TransactionUnderstanding.ProposedItem(null, "C", null, null));
    }

    @Test
    void aScalarOfTheWrongTypeIsDroppedNotTrusted() {
        Proposal p = understandAfter(answer("""
                {"transcript":123,"amount":true,"paymentMethod":["Tunai"],"customerInfo":{"a":1},"merchantInfo":"Toko","items":[]}"""));

        assertThat(p.transcript()).isNull();
        assertThat(p.amount()).isNull();
        assertThat(p.paymentMethod()).isNull();
        assertThat(p.customerInfo()).isNull();
        assertThat(p.merchantInfo()).isEqualTo("Toko");
    }

    @Test
    void anAnswerWithNullItemsMeansNoItems() {
        Proposal p = understandAfter(answer("{\"transcript\":null,\"amount\":5000,\"items\":null}"));

        assertThat(p.items()).isEmpty();
        assertThat(p.amount()).isEqualTo(5000L);
    }

    // ── failing ──────────────────────────────────────────────────────────────────────────────────────────────

    private void assertUnavailable(String responseBody) {
        server.reset();
        assertThatThrownBy(() -> understandAfter(responseBody))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Input suara belum tersedia. Isi manual dulu ya.");
    }

    @Test
    void garbageIsUnavailableNotAHalfAnswer() {
        assertUnavailable(answer("maaf, saya tidak mengerti"));
        assertUnavailable(answer(""));
        assertUnavailable(answer("[1,2,3]"));
        assertUnavailable(answer("\"a string\""));
        assertUnavailable(answer("{}"));
        assertUnavailable(answer("{\"error\":\"nope\"}"));
        assertUnavailable(answer("{\"transcript\":\"x\",\"items\":\"banyak\"}"));
        assertUnavailable(answer("{\"transcript\":\"x\",\"items\":[1,2]}"));
    }

    @Test
    void anAnswerCutOffByTheTokenLimitIsUnavailable() throws Exception {
        String truncated = json.writeValueAsString(java.util.Map.of("candidates", List.of(java.util.Map.of(
                "content", java.util.Map.of("parts", List.of(java.util.Map.of("text", "{\"transcript\":\"jual dua kopi\",\"items\":[{\"name\":\"Ko"))),
                "finishReason", "MAX_TOKENS"))));

        assertUnavailable(truncated);
        assertThat(logged()).contains("finishReason=MAX_TOKENS").doesNotContain("jual dua kopi");
    }

    @Test
    void noCandidatesAtAllIsUnavailableWithTheBlockReasonLogged() {
        assertUnavailable("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
        assertThat(logged()).contains("blockReason=SAFETY");
    }

    @Test
    void anEmptyBodyIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gemini.understand(PCM, 16000, CATALOGUE)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void googleRefusingOrFailingTheCallIsUnavailable() {
        for (HttpStatus status : List.of(HttpStatus.FORBIDDEN, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR,
                HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST)) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":{\"message\":\"Vertex AI API has not been used in project demo-project\"}}"));

            assertThatThrownBy(() -> gemini.understand(PCM, 16000, CATALOGUE))
                    .as(status.toString())
                    .isInstanceOf(ServiceUnavailableException.class)
                    .hasMessage("Input suara belum tersedia. Isi manual dulu ya.");
        }
        assertThat(logged()).contains("Gemini answered 403").contains("Gemini answered 429").contains("Gemini answered 500")
                .contains("has not been used");
    }

    @Test
    void aConnectionFailureOrTimeoutIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> gemini.understand(PCM, 16000, CATALOGUE)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(logged()).contains("Gemini call failed");
    }

    // ── what is never logged ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void neverLogsTheTokenTheAudioOrWhatWasUnderstood() {
        String audio = Base64.getEncoder().encodeToString(WavAudio.wrap(PCM, 16000));
        // success
        understandAfter(answer(GOOD));
        // and every way of failing
        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN).body("denied"));
        assertThatThrownBy(() -> gemini.understand(PCM, 16000, CATALOGUE)).isInstanceOf(ServiceUnavailableException.class);
        server.reset();
        server.expect(requestTo(URL)).andRespond(withException(new IOException("connection reset")));
        assertThatThrownBy(() -> gemini.understand(PCM, 16000, CATALOGUE)).isInstanceOf(ServiceUnavailableException.class);
        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess(answer("pelanggan Budi beli kopi susu {"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gemini.understand(PCM, 16000, CATALOGUE)).isInstanceOf(ServiceUnavailableException.class);

        assertThat(logged()).isNotBlank()
                .doesNotContain(TOKEN).doesNotContain("Bearer").doesNotContain(audio)
                .doesNotContain("Budi").doesNotContain("kopi susu").doesNotContain("Kopi Susu");
    }
}
