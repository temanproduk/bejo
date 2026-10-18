package com.bejo.web;

import com.bejo.voice.service.InMemoryVoiceModelStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rules around the converter-model listing, end to end: the per-user hourly limit (each call mints ~131 MB of download
 * links), that a 503 does not use the quota up, and that a manifest naming a file twice is refused. The limit is the one set for
 * the whole test profile in {@code application-test.yml} (3), not the production default. The window itself moving on (a fake
 * clock) is covered in {@code VoiceModelsServiceTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VoiceModelsLimitsApiIntegrationTest {

    private static final String API_TOKEN = "test-api-token";
    private static final String SHA = "a".repeat(64);
    private static final String ONE_FILE = "{\"revision\":\"r1\",\"files\":[{\"name\":\"convert.onnx\",\"bytes\":1234,\"sha256\":\"" + SHA + "\"}]}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.bejo.notification.InMemoryEmailSender email;
    @Autowired private InMemoryVoiceModelStorage storage;
    @Value("${app.voice.models.max-requests-per-hour}") private int limit;

    @BeforeEach
    void emptyBucket() {
        storage.clear();
    }

    private String newUserToken() throws Exception {
        String username = "modlim_" + System.nanoTime() % 1_000_000_000L;
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

    private ResultActions list(String token) throws Exception {
        return mockMvc.perform(get("/api/voice/models/converter").header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token));
    }

    @Test
    void theCallAfterTheHourlyLimitAnswers429AndAnotherUserIsUnaffected() throws Exception {
        storage.put("converter/manifest.json", ONE_FILE);
        String alice = newUserToken();
        String bob = newUserToken();

        for (int i = 0; i < limit; i++) list(alice).andExpect(status().isOk());
        list(alice).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.message").value("Terlalu sering meminta model suara. Coba lagi nanti."));

        list(bob).andExpect(status().isOk()).andExpect(jsonPath("$.data.files[0].name").value("convert.onnx"));
    }

    @Test
    void a503DoesNotUseUpTheQuota() throws Exception {
        String user = newUserToken();

        for (int i = 0; i < limit + 2; i++) list(user).andExpect(status().isServiceUnavailable());   // nothing uploaded yet

        storage.put("converter/manifest.json", ONE_FILE);
        for (int i = 0; i < limit; i++) list(user).andExpect(status().isOk());
        list(user).andExpect(status().isTooManyRequests());
    }

    @Test
    void aManifestListingAFileTwiceIsRefused() throws Exception {
        String file = "{\"name\":\"convert.onnx\",\"bytes\":1234,\"sha256\":\"" + SHA + "\"}";
        storage.put("converter/manifest.json", "{\"revision\":\"r1\",\"files\":[" + file + "," + file + "]}");

        list(newUserToken()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Model suara belum tersedia"));
    }
}
