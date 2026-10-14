package com.bejo.web;

import com.bejo.voice.service.ScriptedSpeechToText;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The "say the amount" endpoint end to end, with a scripted recogniser in place of Google Speech-to-Text. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VoiceApiIntegrationTest {

    private static final String API_TOKEN = "test-api-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.bejo.notification.InMemoryEmailSender email;
    @Autowired private ScriptedSpeechToText speech;

    private String token;

    @BeforeEach
    void signIn() throws Exception {
        String username = "voice_" + System.nanoTime() % 1_000_000_000L;
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

    private org.springframework.test.web.servlet.ResultActions say(String audio) throws Exception {
        return mockMvc.perform(post("/api/voice/amount")
                .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("audio", audio, "sampleRateHertz", 16000))));
    }

    @Test
    void returnsTheAmountWhatWasSaidComesTo() throws Exception {
        speech.hears("dua ratus lima puluh ribu rupiah");

        say(clip(2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.message").value("Nominal terdengar"))
                .andExpect(jsonPath("$.data.amount").value(250_000))
                .andExpect(jsonPath("$.data.transcript").value("dua ratus lima puluh ribu rupiah"))
                .andExpect(jsonPath("$.data.confidence").value(0.92));
        assertThat(speech.lastSampleRate()).isEqualTo(16000);
        assertThat(speech.lastAudio()).hasSize(64_000);   // exactly what was sent, decoded
    }

    @Test
    void speechThatHoldsNoNumberIsAnswerNotAnError() throws Exception {
        speech.hears("selamat pagi");

        say(clip(2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Nominal tidak terdengar jelas"))
                .andExpect(jsonPath("$.data.transcript").value("selamat pagi"))
                .andExpect(jsonPath("$.data.amount").doesNotExist());
    }

    @Test
    void anUnsureGuessIsShownButNeverBecomesAnAmount() throws Exception {
        speech.hears("dua puluh lima ribu", 0.2);

        say(clip(2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Nominal tidak terdengar jelas"))
                .andExpect(jsonPath("$.data.transcript").value("dua puluh lima ribu"))
                .andExpect(jsonPath("$.data.amount").doesNotExist());
    }

    @Test
    void silenceIsAnAnswerToo() throws Exception {
        speech.hearsNothing();

        say(clip(2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Suara tidak terdengar"))
                .andExpect(jsonPath("$.data.transcript").doesNotExist());
    }

    @Test
    void rejectsAnUnusableClip() throws Exception {
        say(clip(0.05)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Suara terlalu pendek"));
        say("%%%").andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/voice/amount").header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void needsASignedInUser() throws Exception {
        mockMvc.perform(post("/api/voice/amount").header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("audio", clip(1)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aUserCannotHammerTheRecogniser() throws Exception {
        speech.hears("goceng");
        for (int i = 0; i < 12; i++) say(clip(1)).andExpect(status().isOk());

        say(clip(1)).andExpect(status().isTooManyRequests());
    }
}
