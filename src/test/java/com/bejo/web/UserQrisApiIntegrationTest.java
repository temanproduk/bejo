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

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Per-user QRIS: the main account assigns a user their own QRIS + terminal id (payment callbacks
 * then route by terminal). Only the super admin may manage it (+ team management for the write);
 * a user with an assignment sees it via GET /api/qris, otherwise the shared business QRIS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserQrisApiIntegrationTest {

    private static final String API_TOKEN = "test-api-token";
    private static final String QR_A = Base64.getEncoder().encodeToString("qris-terminal-A".getBytes());
    private static final String QR_BIZ = Base64.getEncoder().encodeToString("qris-business".getBytes());

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private void register(String username) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", "password1");
        body.put("name", "Test User");
        body.put("email", username + "@example.com");
        body.put("phone", "+628123456789");
        body.put("businessName", "Warung " + username);
        mockMvc.perform(post("/api/users/register").header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private String login(String username, String deviceId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login").header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", "password1", "deviceId", deviceId))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).at("/data/token").asText();
    }

    private void enableTeam(String token) throws Exception {
        mockMvc.perform(put("/api/account-settings/toggle").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"setting\":\"teamManagement\",\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    private void addMember(String adminToken, String username) throws Exception {
        mockMvc.perform(post("/api/users/members").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", "password1", "name", "Emp",
                                "email", username + "@e.com", "phone", "+628123456780"))))
                .andExpect(status().isCreated());
    }

    private void setUserQris(String token, String targetUser, String terminalId, int expectedStatus) throws Exception {
        mockMvc.perform(put("/api/qris/users/" + targetUser).header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("terminalId", terminalId, "qrisImage", QR_A))))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void mainAccountAssignsUserQrisAndItOverridesTheBusinessQris() throws Exception {
        String adminToken = login(regTeam("uq_admin1"), "d-admin");
        addMember(adminToken, "uq_member1");
        String memberToken = login("uq_member1", "d-member");

        // Business QRIS uploaded by the main account (the fallback for users without their own)
        mockMvc.perform(post("/api/qris/upload").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("qrisImage", QR_BIZ))))
                .andExpect(status().isOk());

        // Main account gives the member their own QRIS + terminal
        setUserQris(adminToken, "uq_member1", "A001", 200);

        // Listed for the main account (terminal shown, image omitted)
        mockMvc.perform(get("/api/qris/users").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].username").value("uq_member1"))
                .andExpect(jsonPath("$.data[0].terminalId").value("A001"))
                .andExpect(jsonPath("$.data[0].qrisImage").doesNotExist());

        // The member's GET /api/qris now returns THEIR image, not the business one
        mockMvc.perform(get("/api/qris").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qrisImage").value(QR_A));

        // Remove it → the member reverts to the shared business QRIS
        mockMvc.perform(delete("/api/qris/users/uq_member1").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/qris").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qrisImage").value(QR_BIZ));
    }

    @Test
    void aMemberCannotManageUserQris() throws Exception {
        String adminToken = login(regTeam("uq_admin2"), "d-a2");
        addMember(adminToken, "uq_member2");
        String memberToken = login("uq_member2", "d-m2");

        setUserQris(memberToken, "uq_member2", "B001", 403);   // member → forbidden
    }

    @Test
    void duplicateTerminalIsRejected() throws Exception {
        String adminToken = login(regTeam("uq_admin3"), "d-a3");
        addMember(adminToken, "uq_m3a");
        addMember(adminToken, "uq_m3b");

        setUserQris(adminToken, "uq_m3a", "T100", 200);
        setUserQris(adminToken, "uq_m3b", "T100", 400);   // same terminal → 400
    }

    @Test
    void unknownTargetUserIsRejected() throws Exception {
        String adminToken = login(regTeam("uq_admin4"), "d-a4");
        setUserQris(adminToken, "ghost_user", "T200", 400);
    }

    @Test
    void settingUserQrisRequiresTeamManagement() throws Exception {
        register("uq_solo");
        String adminToken = login("uq_solo", "d-solo");   // fresh solo account, team OFF
        // team off → assigning a per-user QRIS (even to self) is gated
        mockMvc.perform(put("/api/qris/users/uq_solo").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("terminalId", "S1", "qrisImage", QR_A))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Fitur manajemen tim belum diaktifkan"));
    }

    // Register + login an admin and enable team management; returns the username.
    private String regTeam(String username) throws Exception {
        register(username);
        String token = login(username, "d-" + username);
        enableTeam(token);
        return username;
    }
}
