package com.bejo.web;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bejo.voice.service.ScriptedTransactionUnderstanding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The "say the sale" endpoint end to end, with a scripted Gemini in place of the real one. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VoiceTransactionApiIntegrationTest {

    private static final String API_TOKEN = "test-api-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.bejo.notification.InMemoryEmailSender email;
    @Autowired private ScriptedTransactionUnderstanding gemini;

    private String token;

    @BeforeEach
    void signIn() throws Exception {
        gemini.reset();
        String username = "vtx_" + System.nanoTime() % 1_000_000_000L;
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
        token = objectMapper.readTree(login).at("/data/token").asText();
    }

    private static String clip(double seconds) {
        return Base64.getEncoder().encodeToString(new byte[(int) (seconds * 16000 * 2)]);
    }

    private static Map<String, Object> product(long id, String name, Long price) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id);
        p.put("name", name);
        p.put("price", price);
        return p;
    }

    private ResultActions say(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/voice/transaction")
                .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions say(String audio, List<Map<String, Object>> products) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("audio", audio);
        body.put("sampleRateHertz", 16000);
        if (products != null) body.put("products", products);
        return say(body);
    }

    private static final String TWO_PRODUCTS_ANSWER = """
            {"transcript":"dua kopi susu sama teh botol lima ribu, pelanggan Budi, bayar tunai",
             "amount":null,"paymentMethod":"Tunai","customerInfo":"Budi","merchantInfo":null,
             "items":[{"productId":12,"name":"kopi susu","quantity":2,"unitPrice":1},
                      {"productId":null,"name":"Teh Botol","quantity":1,"unitPrice":5000}]}""";

    @Test
    void needsASignedInUser() throws Exception {
        mockMvc.perform(post("/api/voice/transaction").header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("audio", clip(1)))))
                .andExpect(status().isUnauthorized());
        assertThat(gemini.calls()).isZero();
    }

    @Test
    void needsTheApiToken() throws Exception {
        mockMvc.perform(post("/api/voice/transaction").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("audio", clip(1)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void understandsASaleWithOneMatchedAndOneNewProduct() throws Exception {
        gemini.saysText(TWO_PRODUCTS_ANSWER);

        say(clip(3), List.of(product(12, "Kopi Susu", 18000L), product(7, "Roti Bakar", 15000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.message").value("Transaksi terdengar"))
                .andExpect(jsonPath("$.data.understood").value(true))
                .andExpect(jsonPath("$.data.transcript").value("dua kopi susu sama teh botol lima ribu, pelanggan Budi, bayar tunai"))
                .andExpect(jsonPath("$.data.paymentMethod").value("Tunai"))
                .andExpect(jsonPath("$.data.customerInfo").value("Budi"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                // matched: the catalogue's id, name and price; the spoken price (1) is ignored
                .andExpect(jsonPath("$.data.items[0].productId").value(12))
                .andExpect(jsonPath("$.data.items[0].name").value("Kopi Susu"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(18000))
                .andExpect(jsonPath("$.data.items[0].isNew").value(false))
                // new: no id, the name as heard, the heard price
                .andExpect(jsonPath("$.data.items[1].name").value("Teh Botol"))
                .andExpect(jsonPath("$.data.items[1].quantity").value(1))
                .andExpect(jsonPath("$.data.items[1].unitPrice").value(5000))
                .andExpect(jsonPath("$.data.items[1].isNew").value(true));
        assertThat(gemini.lastSampleRate()).isEqualTo(16000);
        assertThat(gemini.lastPcm()).hasSize(96_000);
        assertThat(gemini.lastCatalogue()).extracting(c -> c.name()).containsExactly("Kopi Susu", "Roti Bakar");
    }

    @Test
    void theContractFieldsAreAllPresentWithExplicitNulls() throws Exception {
        gemini.saysText(TWO_PRODUCTS_ANSWER);

        String body = say(clip(3), List.of(product(12, "Kopi Susu", 18000L))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).get("data");
        assertThat(data.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("understood", "transcript", "amount", "paymentMethod", "customerInfo", "merchantInfo", "items");
        assertThat(data.has("amount")).isTrue();
        assertThat(data.get("amount").isNull()).isTrue();
        assertThat(data.get("merchantInfo").isNull()).isTrue();
        JsonNode newItem = data.get("items").get(1);
        assertThat(newItem.fieldNames()).toIterable().containsExactlyInAnyOrder("productId", "name", "quantity", "unitPrice", "isNew");
        assertThat(newItem.get("productId").isNull()).isTrue();
        assertThat(data.get("items").get(0).get("unitPrice").asLong()).isEqualTo(18000L);
    }

    @Test
    void anIdFromNowhereBecomesANewProductAndAnUnknownPaymentMethodIsDropped() throws Exception {
        gemini.saysText("""
                {"transcript":"x","amount":15000,"paymentMethod":"Bitcoin","customerInfo":null,"merchantInfo":null,
                 "items":[{"productId":4242,"name":"Roti Bakar","quantity":1,"unitPrice":15000}]}""");

        String body = say(clip(2), List.of(product(12, "Kopi Susu", 18000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(15000))
                .andExpect(jsonPath("$.data.items[0].isNew").value(true))
                .andExpect(jsonPath("$.data.items[0].name").value("Roti Bakar"))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).get("data");
        assertThat(data.get("paymentMethod").isNull()).isTrue();      // "Bitcoin" is not one of the six
        assertThat(data.at("/items/0/productId").isNull()).isTrue();  // 4242 is not in the request
    }

    @Test
    void aSaleThatWasNotUnderstoodSaysSo() throws Exception {
        gemini.saysText("{\"transcript\":\"halo halo\",\"amount\":null,\"paymentMethod\":null,\"customerInfo\":null,\"merchantInfo\":null,\"items\":[]}");

        say(clip(2), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.message").value("Transaksi tidak terdengar jelas"))
                .andExpect(jsonPath("$.data.understood").value(false))
                .andExpect(jsonPath("$.data.transcript").value("halo halo"))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void sampleRateDefaultsTo16kHz() throws Exception {
        gemini.saysText(TWO_PRODUCTS_ANSWER);

        say(Map.of("audio", clip(2))).andExpect(status().isOk());

        assertThat(gemini.lastSampleRate()).isEqualTo(16000);
    }

    @Test
    void rejectsAnUnusableRequestWith400() throws Exception {
        say(clip(0.2), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Suara terlalu pendek"));
        say(clip(31), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Suara terlalu panjang (maksimal 30 detik)"));
        say("%%%", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("audio bukan base64 yang valid"));
        say(Base64.getEncoder().encodeToString(new byte[33_001]), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("audio harus PCM 16-bit"));
        say(Map.of("audio", clip(2), "sampleRateHertz", 7999)).andExpect(status().isBadRequest());
        say(Map.of("audio", clip(2), "sampleRateHertz", 48001)).andExpect(status().isBadRequest());
        say(Map.of()).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/voice/transaction").header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON).content("not json")).andExpect(status().isBadRequest());
        assertThat(gemini.calls()).isZero();
    }

    @Test
    void rejectsMoreThan200ProductsAndAnUnusableProduct() throws Exception {
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 1; i <= 201; i++) tooMany.add(product(i, "Produk " + i, 1000L));

        say(clip(2), tooMany).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("products maksimal 200 produk"));
        say(clip(2), List.of(product(1, "  ", 1000L))).andExpect(status().isBadRequest());
        say(clip(2), List.of(product(1, "Kopi", -5L))).andExpect(status().isBadRequest());
        assertThat(gemini.calls()).isZero();

        say(clip(2), tooMany.subList(0, 200)).andExpect(status().isOk());   // exactly 200 is fine
    }

    @Test
    void geminiBeingUnavailableIs503WithTheManualFallbackMessage() throws Exception {
        gemini.isUnavailable();

        say(clip(2), null).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.message").value("Input suara belum tersedia. Isi manual dulu ya."));
    }

    @Test
    void anUnreadableAnswerIs503NotAHalfAnswer() throws Exception {
        // saysText runs the real answer parser, which refuses garbage
        org.junit.jupiter.api.Assertions.assertThrows(com.bejo.shared.exception.ServiceUnavailableException.class,
                () -> gemini.saysText("this is not json"));
    }

    @Test
    void aUserCannotHammerGemini() throws Exception {
        gemini.saysText(TWO_PRODUCTS_ANSWER);
        for (int i = 0; i < 6; i++) say(clip(1), null).andExpect(status().isOk());

        say(clip(1), null).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Terlalu sering memakai input suara. Coba lagi sebentar lagi."));
        assertThat(gemini.calls()).isEqualTo(6);
    }

    @Test
    void aRejectedRequestNeverLogsTheRecording() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.bejo");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            String audio = Base64.getEncoder().encodeToString(new byte[1000]);   // 0.03 s: rejected as too short
            say(audio, List.of(product(1, "Produk Rahasia", 1000L))).andExpect(status().isBadRequest());

            String logged = String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            assertThat(logged).contains("/api/voice/transaction").contains("400");   // the request line is logged
            assertThat(logged).doesNotContain("[BODY]").doesNotContain(audio.substring(0, 40)).doesNotContain("Produk Rahasia");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
