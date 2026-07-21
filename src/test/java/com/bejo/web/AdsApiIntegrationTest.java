package com.bejo.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Functional tests for the voice-ad campaign flow: the admin surface (create/prepare/play, gated
 * by X-ADS-ADMIN-TOKEN) and the device reports (download/played/location, JWT-scoped to the
 * target). Audio storage is the in-memory test double, so no bucket is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdsApiIntegrationTest {

    private static final String API_TOKEN = "test-api-token";
    private static final String ADS_ADMIN_TOKEN = "test-ads-admin-token";
    // A real (if silent) 1-second PCM WAV so the duration check has something valid to parse.
    private static final byte[] AUDIO = wav(1.0);
    private static final String CONTENT_TYPE = "audio/wav";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // Minimal valid mono 8-bit 8kHz PCM WAV of the given duration (silence) — jaudiotagger reads
    // its length from the header, so this exercises the real duration check without a fixture file.
    private static byte[] wav(double seconds) {
        int sampleRate = 8000;
        int numSamples = (int) Math.round(seconds * sampleRate);
        int total = 44 + numSamples;
        ByteBuffer bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes()).putInt(total - 8).put("WAVE".getBytes());
        bb.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate).putShort((short) 1).putShort((short) 8);
        bb.put("data".getBytes()).putInt(numSamples);
        for (int i = 0; i < numSamples; i++) bb.put((byte) 128); // unsigned-8-bit silence
        return bb.array();
    }

    // ---------- helpers ----------

    private void register(String username) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", "password1");
        body.put("name", "Test User");
        body.put("email", username + "@example.com");
        body.put("phone", "+628123456789");
        body.put("businessName", "Warung " + username);
        mockMvc.perform(post("/api/users/register")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private String login(String username, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "password1",
                                "deviceId", deviceId,
                                "pushToken", "fcm-" + deviceId))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/token").asText();
    }

    // Creates a campaign targeting (username, deviceId); returns the response data node.
    private JsonNode createCampaign(String username, String deviceId) throws Exception {
        Map<String, Object> body = Map.of(
                "name", "Promo Kopi",
                "brand", "Kopi Bejo",
                "audioBase64", Base64.getEncoder().encodeToString(AUDIO),
                "contentType", CONTENT_TYPE,
                "targetUsername", username,
                "targetDeviceId", deviceId);
        MvcResult result = mockMvc.perform(post("/api/ads/campaigns")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("X-ADS-ADMIN-TOKEN", ADS_ADMIN_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state").value("CREATED"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private void prepare(long campaignId) throws Exception {
        mockMvc.perform(post("/api/ads/campaigns/" + campaignId + "/prepare")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("X-ADS-ADMIN-TOKEN", ADS_ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PREPARING"));
    }

    // ---------- happy path ----------

    @Test
    void fullLifecycleCreatePreparePlayFinished() throws Exception {
        register("adfull");
        String token = login("adfull", "device-adfull");

        JsonNode campaign = createCampaign("adfull", "device-adfull");
        long campaignId = campaign.get("id").asLong();
        long deliveryId = campaign.at("/deliveries/0/id").asLong();
        String sha256 = campaign.get("audioSha256").asText();
        long sizeBytes = campaign.get("audioSizeBytes").asLong();

        prepare(campaignId);

        // Device reports a good download → READY
        mockMvc.perform(post("/api/ads/deliveries/" + deliveryId + "/downloaded")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "success", true, "sha256", sha256, "sizeBytes", sizeBytes))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("READY"));

        // Admin plays → STARTING
        mockMvc.perform(post("/api/ads/campaigns/" + campaignId + "/play")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("X-ADS-ADMIN-TOKEN", ADS_ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("STARTING"));

        // Device reports played WITH location → straight to FINISHED
        mockMvc.perform(post("/api/ads/deliveries/" + deliveryId + "/played")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "playedAt", LocalDateTime.now().toString(),
                                "latitude", -6.2, "longitude", 106.8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("FINISHED"))
                .andExpect(jsonPath("$.data.playLatitude").value(-6.2));
    }

    @Test
    void playedWithoutLocationThenDeferredLocationFinishes() throws Exception {
        register("addef");
        String token = login("addef", "device-addef");
        JsonNode campaign = createCampaign("addef", "device-addef");
        long campaignId = campaign.get("id").asLong();
        long deliveryId = campaign.at("/deliveries/0/id").asLong();
        String sha256 = campaign.get("audioSha256").asText();
        long sizeBytes = campaign.get("audioSizeBytes").asLong();

        prepare(campaignId);
        markDownloaded(token, deliveryId, sha256, sizeBytes);
        play(campaignId);

        // Played but background → no location → PLAYED
        mockMvc.perform(post("/api/ads/deliveries/" + deliveryId + "/played")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "playedAt", LocalDateTime.now().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PLAYED"));

        // Deferred location arrives → FINISHED
        mockMvc.perform(post("/api/ads/deliveries/" + deliveryId + "/location")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", -6.1, "longitude", 106.7))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("FINISHED"))
                .andExpect(jsonPath("$.data.playLongitude").value(106.7));
    }

    @Test
    void downloadReportWithBadHashGoesToError() throws Exception {
        register("aderr");
        String token = login("aderr", "device-aderr");
        JsonNode campaign = createCampaign("aderr", "device-aderr");
        long campaignId = campaign.get("id").asLong();
        long deliveryId = campaign.at("/deliveries/0/id").asLong();

        prepare(campaignId);
        mockMvc.perform(post("/api/ads/deliveries/" + deliveryId + "/downloaded")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "success", true, "sha256", "deadbeef", "sizeBytes", 999))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ERROR"))
                .andExpect(jsonPath("$.data.errorReason").isNotEmpty());

        // Play is refused while not READY
        mockMvc.perform(post("/api/ads/campaigns/" + campaignId + "/play")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("X-ADS-ADMIN-TOKEN", ADS_ADMIN_TOKEN))
                .andExpect(status().isBadRequest());
    }

    // ---------- auth ----------

    @Test
    void createWithoutAdminTokenIsForbidden() throws Exception {
        register("adnoauth");
        login("adnoauth", "device-adnoauth");
        Map<String, Object> body = Map.of(
                "name", "X", "audioBase64", Base64.getEncoder().encodeToString(AUDIO),
                "contentType", CONTENT_TYPE, "targetUsername", "adnoauth", "targetDeviceId", "device-adnoauth");
        mockMvc.perform(post("/api/ads/campaigns")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deviceCannotReportOnAnotherUsersDelivery() throws Exception {
        register("adowner");
        login("adowner", "device-adowner");
        register("adother");
        String otherToken = login("adother", "device-adother");

        JsonNode campaign = createCampaign("adowner", "device-adowner");
        long campaignId = campaign.get("id").asLong();
        long deliveryId = campaign.at("/deliveries/0/id").asLong();
        prepare(campaignId);

        // A different user's JWT may not report on this delivery
        mockMvc.perform(post("/api/ads/deliveries/" + deliveryId + "/downloaded")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("success", false))))
                .andExpect(status().isForbidden());
    }

    @Test
    void createWithUnknownDeviceIsRejected() throws Exception {
        register("adnodev");
        login("adnodev", "device-adnodev");
        Map<String, Object> body = Map.of(
                "name", "X", "audioBase64", Base64.getEncoder().encodeToString(AUDIO),
                "contentType", CONTENT_TYPE, "targetUsername", "adnodev", "targetDeviceId", "nonexistent-device");
        mockMvc.perform(post("/api/ads/campaigns")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("X-ADS-ADMIN-TOKEN", ADS_ADMIN_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsAudioLongerThanTenSeconds() throws Exception {
        register("adlong");
        login("adlong", "device-adlong");
        Map<String, Object> body = Map.of(
                "name", "Panjang", "audioBase64", Base64.getEncoder().encodeToString(wav(11.0)),
                "contentType", CONTENT_TYPE, "targetUsername", "adlong", "targetDeviceId", "device-adlong");
        mockMvc.perform(post("/api/ads/campaigns")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("X-ADS-ADMIN-TOKEN", ADS_ADMIN_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("detik")));
    }

    // ---------- shared helpers ----------

    private void markDownloaded(String token, long deliveryId, String sha256, long sizeBytes) throws Exception {
        mockMvc.perform(post("/api/ads/deliveries/" + deliveryId + "/downloaded")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "success", true, "sha256", sha256, "sizeBytes", sizeBytes))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("READY"));
    }

    private void play(long campaignId) throws Exception {
        mockMvc.perform(post("/api/ads/campaigns/" + campaignId + "/play")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("X-ADS-ADMIN-TOKEN", ADS_ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("STARTING"));
    }
}
