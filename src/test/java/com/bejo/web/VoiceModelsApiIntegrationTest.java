package com.bejo.web;

import com.bejo.voice.service.InMemoryVoiceModelStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The converter-model listing end to end, with an in-memory bucket in place of Cloud Storage. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VoiceModelsApiIntegrationTest {

    private static final String API_TOKEN = "test-api-token";
    private static final String SHA = "a".repeat(64);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.bejo.notification.InMemoryEmailSender email;
    @Autowired private InMemoryVoiceModelStorage storage;

    private String token;

    @BeforeEach
    void signIn() throws Exception {
        storage.clear();
        String username = "models_" + System.nanoTime() % 1_000_000_000L;
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

    private static String manifest(String name) {
        return "{\"revision\":\"openvoice-v2@f36e7edf\",\"files\":[{\"name\":\"" + name + "\",\"bytes\":1234,\"sha256\":\"" + SHA + "\"}]}";
    }

    @Test
    void listsTheFilesWithSignedUrls() throws Exception {
        storage.put("converter/manifest.json", manifest("convert.onnx"));
        mockMvc.perform(get("/api/voice/models/converter").header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.data.revision").value("openvoice-v2@f36e7edf"))
                .andExpect(jsonPath("$.data.files[0].name").value("convert.onnx"))
                .andExpect(jsonPath("$.data.files[0].bytes").value(1234))
                .andExpect(jsonPath("$.data.files[0].sha256").value(SHA))
                .andExpect(jsonPath("$.data.files[0].url").value("https://storage.example.test/converter/convert.onnx?ttl=3600"));
    }

    @Test
    void answers503WhenNothingHasBeenUploaded() throws Exception {
        mockMvc.perform(get("/api/voice/models/converter").header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.message").value("Model suara belum tersedia"));
    }

    @Test
    void refusesAManifestWithAnUnsafeFileName() throws Exception {
        storage.put("converter/manifest.json", manifest("../../secrets.txt"));
        mockMvc.perform(get("/api/voice/models/converter").header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void requiresASignedInUser() throws Exception {
        storage.put("converter/manifest.json", manifest("convert.onnx"));
        mockMvc.perform(get("/api/voice/models/converter").header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isUnauthorized());
    }
}
