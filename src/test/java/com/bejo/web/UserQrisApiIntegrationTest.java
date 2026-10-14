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
    // Sample EMV payloads — a QRIS is stored/served as a code now, not a base64 image
    private static final String QR_A =
            "00020101021126440018ID.CO.ASTRAPAY.WWW011893600822000000000151440014ID.CO.QRIS.WWW0215ID11111111111110303UMI5204581453033605802ID5908Cabang A6007JAKARTA62110707TERMA0163040000";
    /** A real QRIS payload decoded from an actual AstraPay merchant QR. */
    private static final String REAL_QRIS =
            "00020101021126640018ID.CO.ASTRAPAY.WWW011893600822326062900102092606290010303UMI"
            + "51440014ID.CO.QRIS.WWW0215ID10265407789780303UMI5204581453033605802ID"
            + "5906Susilo6007TANGSEL61051541362080704A00163042C15";
    /** A second merchant identity, for the sub-account's own terminal. */
    private static final String OTHER_QRIS =
            "000201" + "5137" + "0014ID.CO.QRIS.WWW0215ID7777777777777"
            + "5911Toko Cabang" + "63041234";

    private static final String QR_BIZ =
            "00020101021126440018ID.CO.ASTRAPAY.WWW011893600822000000000151440014ID.CO.QRIS.WWW0215ID22222222222220303UMI5204581453033605802ID5912Warung Pusat6007JAKARTA62110707TERMBIZ63040000";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.bejo.notification.InMemoryEmailSender email;

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
        EmailVerificationTestSupport.verifyEmail(mockMvc, objectMapper, email, username, username + "@example.com");
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

    // Adds a member and returns its user id (the API keys per-user QRIS by id, like members).
    private String addMember(String adminToken, String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/users/members").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", "password1", "name", "Emp",
                                "email", username + "@e.com", "phone", "+628123456780"))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private void setUserQris(String token, String targetId, int expectedStatus) throws Exception {
        mockMvc.perform(put("/api/qris/users/" + targetId).header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("qrisCode", QR_A))))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void mainAccountAssignsUserQrisAndItOverridesTheBusinessQris() throws Exception {
        String adminToken = login(regTeam("uq_admin1"), "d-admin");
        String memberId = addMember(adminToken, "uq_member1");
        String memberToken = login("uq_member1", "d-member");

        // Business QRIS uploaded by the main account (the fallback for users without their own)
        mockMvc.perform(post("/api/qris/upload").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("qrisCode", QR_BIZ))))
                .andExpect(status().isOk());

        // Main account gives the member their own QRIS + terminal (keyed by user id)
        setUserQris(adminToken, memberId, 200);

        // Listed for the main account (userId + terminal shown, image omitted)
        mockMvc.perform(get("/api/qris/users").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].userId").value(Integer.parseInt(memberId)))
                .andExpect(jsonPath("$.data[0].username").value("uq_member1"))
                .andExpect(jsonPath("$.data[0].terminalId").value("TERMA01"))
                .andExpect(jsonPath("$.data[0].qrisCode").doesNotExist());

        // The member's GET /api/qris now returns THEIR image, not the business one
        mockMvc.perform(get("/api/qris").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qrisCode").value(QR_A));

        // Remove it → the member reverts to the shared business QRIS
        mockMvc.perform(delete("/api/qris/users/" + memberId).header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/qris").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qrisCode").value(QR_BIZ));
    }

    // The app gates its "Bunyi Uang Masuk dari Aplikasi" toggle on this flag: a member with
    // their own QRIS runs a separate merchant app and detects its own incoming payments.
    @Test
    void hasOwnQrisReflectsTheAssignmentOnLoginAndMe() throws Exception {
        String adminToken = login(regTeam("uq_flag_admin"), "d-flag-a");
        String memberId = addMember(adminToken, "uq_flag_member");

        // Before assignment: the member shares the business QRIS
        mockMvc.perform(post("/api/auth/login").header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "username", "uq_flag_member", "password", "password1", "deviceId", "d-flag-m"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasOwnQris").value(false));

        setUserQris(adminToken, memberId, 200);

        // After assignment: both login and /me report it
        MvcResult relogin = mockMvc.perform(post("/api/auth/login").header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "username", "uq_flag_member", "password", "password1", "deviceId", "d-flag-m"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasOwnQris").value(true))
                .andReturn();
        String memberToken = objectMapper.readTree(relogin.getResponse().getContentAsString())
                .at("/data/token").asText();
        mockMvc.perform(get("/api/users/me").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasOwnQris").value(true));

        // The super admin: false while the business still has no QRIS at all...
        mockMvc.perform(get("/api/users/me").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.hasOwnQris").value(false));

        // ...and true once one is uploaded — the shared business QRIS is the admin's to monitor
        mockMvc.perform(post("/api/qris/upload").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("qrisCode", QR_BIZ))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.hasOwnQris").value(true));

        // Removing the assignment flips it back
        mockMvc.perform(delete("/api/qris/users/" + memberId).header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(jsonPath("$.data.hasOwnQris").value(false));   // shares the business QRIS again
    }

    // The QR caption must describe the QR on screen: a sub-account with its own QRIS shows its
    // own merchant identity, parsed from its own code — not the business's.
    @Test
    void eachQrisReportsTheMerchantIdentityParsedFromItsOwnCode() throws Exception {
        String adminToken = login(regTeam("uq_ident_admin"), "d-ident-a");
        String memberId = addMember(adminToken, "uq_ident_member");
        String memberToken = login("uq_ident_member", "d-ident-m");

        // Business QRIS — a real AstraPay payload
        mockMvc.perform(post("/api/qris/upload").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("qrisCode", REAL_QRIS))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/qris").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantName").value("Susilo"))
                .andExpect(jsonPath("$.data.nmid").value("ID1026540778978"))
                .andExpect(jsonPath("$.data.acquirer").value("ID.CO.ASTRAPAY.WWW"))
                // businessName stays the manually-entered one, distinct from the QRIS name
                .andExpect(jsonPath("$.data.businessName").value("Warung uq_ident_admin"));

        // The member gets their own terminal with a DIFFERENT merchant identity
        mockMvc.perform(put("/api/qris/users/" + memberId).header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "qrisCode", OTHER_QRIS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantName").value("Toko Cabang"))
                .andExpect(jsonPath("$.data.nmid").value("ID7777777777777"));

        // ...and sees it, not the business's
        mockMvc.perform(get("/api/qris").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantName").value("Toko Cabang"))
                .andExpect(jsonPath("$.data.nmid").value("ID7777777777777"));

        // The admin's own QRIS is unaffected by the member's assignment
        mockMvc.perform(get("/api/qris").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.nmid").value("ID1026540778978"));
    }

    @Test
    void aMemberCannotManageUserQris() throws Exception {
        String adminToken = login(regTeam("uq_admin2"), "d-a2");
        String memberId = addMember(adminToken, "uq_member2");
        String memberToken = login("uq_member2", "d-m2");

        setUserQris(memberToken, memberId, 403);   // member → forbidden
    }

    @Test
    void duplicateTerminalIsRejected() throws Exception {
        String adminToken = login(regTeam("uq_admin3"), "d-a3");
        String m3a = addMember(adminToken, "uq_m3a");
        String m3b = addMember(adminToken, "uq_m3b");

        setUserQris(adminToken, m3a, 200);
        setUserQris(adminToken, m3b, 400);   // same terminal → 400
    }

    @Test
    void unknownTargetUserIsRejected() throws Exception {
        String adminToken = login(regTeam("uq_admin4"), "d-a4");
        setUserQris(adminToken, "999999", 400);   // no such user id
    }

    @Test
    void settingUserQrisNeedsNoTeamToggle() throws Exception {
        register("uq_solo");
        String adminToken = login("uq_solo", "d-solo");   // fresh solo account, nothing toggled
        MvcResult me = mockMvc.perform(get("/api/users/me").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken)).andReturn();
        String soloId = objectMapper.readTree(me.getResponse().getContentAsString()).at("/data/id").asText();
        // team management is always on: assigning a per-user QRIS works without any toggle
        mockMvc.perform(put("/api/qris/users/" + soloId).header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("qrisCode", QR_A))))
                .andExpect(status().isOk());
    }

    // Register + login an admin and enable team management; returns the username.
    private String regTeam(String username) throws Exception {
        register(username);
        String token = login(username, "d-" + username);
        enableTeam(token);
        return username;
    }
}
