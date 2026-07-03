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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Functional tests exercising the full HTTP stack: ApiTokenFilter, Spring Security,
 * JwtAuthFilter, controllers, services, and JPA against an in-memory H2 database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    private static final String API_TOKEN = "test-api-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.bejo.repository.TransactionRepository transactionRepository;
    @Autowired private com.bejo.repository.UserRepository userRepository;

    // ---------- helpers ----------

    private Map<String, Object> registerBody(String username) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", "password1");
        body.put("name", "Test User");
        body.put("email", username + "@example.com");
        body.put("phone", "+628123456789");
        body.put("businessName", "Warung " + username);
        return body;
    }

    private void register(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody(username))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(1));
    }

    private String login(String username, String password, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password,
                                "deviceId", deviceId,
                                "pushToken", "fcm-" + deviceId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.at("/data/token").asText();
    }

    private String registerAndLogin(String username) throws Exception {
        register(username);
        return login(username, "password1", "device-" + username);
    }

    // ---------- API token filter ----------

    @Test
    void requestWithoutApiTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Missing or invalid X-API-TOKEN"));
    }

    @Test
    void requestWithWrongApiTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", "wrong-token")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealthIsExemptFromApiToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    // ---------- auth ----------

    @Test
    void registerLoginReturnsTokenWithExpiry() throws Exception {
        register("auth_user1");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "auth_user1",
                                "password", "password1",
                                "deviceId", "dev-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("auth_user1"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(86400))
                .andExpect(jsonPath("$.data.accountType").value("MANAGED"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("token");
    }

    @Test
    void loginWithWrongPasswordFails() throws Exception {
        register("auth_user2");

        mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "auth_user2",
                                "password", "wrong-password",
                                "deviceId", "dev-1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    void duplicateRegistrationIsRejected() throws Exception {
        register("auth_user3");

        mockMvc.perform(post("/api/auth/register")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody("auth_user3"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username sudah digunakan"));
    }

    @Test
    void registerValidatesRequestBody() throws Exception {
        Map<String, Object> body = registerBody("auth_user4");
        body.put("password", "short");
        body.put("phone", "081234");

        mockMvc.perform(post("/api/auth/register")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void protectedEndpointWithoutBearerTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutBlacklistsToken() throws Exception {
        String token = registerAndLogin("auth_user5");

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // ---------- transactions ----------

    @Test
    void createTransactionAndFetchHistory() throws Exception {
        String token = registerAndLogin("txn_user1");

        mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "txn-key-1")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL",
                                "transactionDate", "2026-07-01T10:30:00",
                                "amount", 75000))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.transactionId").isNotEmpty());

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].amount").value(75000))
                .andExpect(jsonPath("$.data[0].acquirer").value("Cash"))
                .andExpect(jsonPath("$.data[0].merchantName").value("Warung txn_user1"));
    }

    @Test
    void idempotencyKeyReplayReturnsSameTransaction() throws Exception {
        String token = registerAndLogin("txn_user2");
        Map<String, Object> body = Map.of(
                "type", "MANUAL",
                "transactionDate", "2026-07-01T10:30:00",
                "amount", 10000);

        MvcResult first = mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "replay-key")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "replay-key")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        String id1 = objectMapper.readTree(first.getResponse().getContentAsString())
                .at("/data/transactionId").asText();
        String id2 = objectMapper.readTree(second.getResponse().getContentAsString())
                .at("/data/transactionId").asText();
        assertThat(id1).isEqualTo(id2);

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void transactionWithoutIdempotencyKeyIsRejected() throws Exception {
        String token = registerAndLogin("txn_user3");

        mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL",
                                "transactionDate", "2026-07-01T10:30:00",
                                "amount", 10000))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void parsedTransactionRequiresAcquirer() throws Exception {
        String token = registerAndLogin("txn_user4");

        mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "parsed-key")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "PARSED",
                                "transactionDate", "2026-07-01T10:30:00",
                                "amount", 10000))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("acquirer wajib diisi untuk type PARSED"));
    }

    @Test
    void historyDateFilterIsInclusiveOfWholeEndDay() throws Exception {
        String token = registerAndLogin("txn_user5");

        // One transaction late on 2026-06-30, one on 2026-07-01
        for (var entry : Map.of(
                "k1", "2026-06-30T23:59:59",
                "k2", "2026-07-01T08:00:00").entrySet()) {
            mockMvc.perform(post("/api/transactions")
                            .header("X-API-TOKEN", API_TOKEN)
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", entry.getKey())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "type", "MANUAL",
                                    "transactionDate", entry.getValue(),
                                    "amount", 5000))))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .param("dateStart", "2026-06-30")
                        .param("dateEnd", "2026-06-30"))
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .param("dateStart", "2026-06-30")
                        .param("dateEnd", "2026-07-01"))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void usersOnlySeeTheirOwnTransactions() throws Exception {
        String tokenA = registerAndLogin("txn_user6a");
        String tokenB = registerAndLogin("txn_user6b");

        mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "isolation-key")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL",
                                "transactionDate", "2026-07-01T10:30:00",
                                "amount", 12345))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(jsonPath("$.count").value(0));
    }

    private String createTransaction(String token, String idempotencyKey, long amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL",
                                "transactionDate", "2026-07-01T10:30:00",
                                "amount", amount))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/transactionId").asText();
    }

    @Test
    void deletedTransactionDisappearsFromHistory() throws Exception {
        String token = registerAndLogin("txn_user7");
        String keepId = createTransaction(token, "del-key-1", 10000);
        String deleteId = createTransaction(token, "del-key-2", 20000);

        mockMvc.perform(delete("/api/transactions/" + deleteId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1));

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].transactionId").value(keepId));

        // Deleting the same transaction again is a 404 (already soft-deleted)
        mockMvc.perform(delete("/api/transactions/" + deleteId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void userCannotDeleteAnotherUsersTransaction() throws Exception {
        String tokenA = registerAndLogin("txn_user8a");
        String tokenB = registerAndLogin("txn_user8b");
        String txnId = createTransaction(tokenA, "del-key-3", 30000);

        mockMvc.perform(delete("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // Still visible to its owner
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void idempotencyKeyOfDeletedTransactionStillReplays() throws Exception {
        String token = registerAndLogin("txn_user9");
        String txnId = createTransaction(token, "del-key-4", 40000);

        mockMvc.perform(delete("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Replaying the same key returns the deleted transaction's id
        // instead of violating the unique constraint with a new insert
        MvcResult replay = mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "del-key-4")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL",
                                "transactionDate", "2026-07-01T10:30:00",
                                "amount", 40000))))
                .andExpect(status().isCreated())
                .andReturn();
        String replayId = objectMapper.readTree(replay.getResponse().getContentAsString())
                .at("/data/transactionId").asText();
        assertThat(replayId).isEqualTo(txnId);

        // History stays empty — the row remains soft-deleted
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void editTransactionUpdatesOnlyProvidedFields() throws Exception {
        String token = registerAndLogin("txn_user10");
        String txnId = createTransaction(token, "edit-key-1", 50000);

        mockMvc.perform(patch("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount", 60000,
                                "issuerName", "OVO"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionId").value(txnId))
                .andExpect(jsonPath("$.data.amount").value(60000))
                .andExpect(jsonPath("$.data.issuerName").value("OVO"))
                // Untouched fields survive
                .andExpect(jsonPath("$.data.acquirer").value("Cash"))
                .andExpect(jsonPath("$.data.type").value("MANUAL"));

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[0].amount").value(60000));
    }

    @Test
    void editRejectsEmptyBodyAndInvalidAmount() throws Exception {
        String token = registerAndLogin("txn_user11");
        String txnId = createTransaction(token, "edit-key-2", 50000);

        mockMvc.perform(patch("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", -1))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void managedTransactionCannotBeEditedButCanBeDeleted() throws Exception {
        String token = registerAndLogin("txn_user13");

        // MANAGED rows are created by the notification flow (needs Firebase),
        // so seed one directly
        com.bejo.entity.Transaction t = new com.bejo.entity.Transaction();
        t.setTransactionId("TXN-MANAGED-1");
        t.setTransactionDate(java.time.LocalDateTime.of(2026, 7, 1, 10, 0));
        t.setAmount(15000L);
        t.setUsername("txn_user13");
        t.setType("MANAGED");
        t.setAcquirer("AstraPay");
        t.setStatus("SUCCESS");
        transactionRepository.save(t);

        mockMvc.perform(patch("/api/transactions/TXN-MANAGED-1")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Transaksi bertipe MANAGED tidak dapat diubah"));

        // Delete is still allowed for MANAGED
        mockMvc.perform(delete("/api/transactions/TXN-MANAGED-1")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void editDeletedOrForeignTransactionIs404() throws Exception {
        String tokenA = registerAndLogin("txn_user12a");
        String tokenB = registerAndLogin("txn_user12b");
        String txnId = createTransaction(tokenA, "edit-key-3", 50000);

        // Another user cannot edit it
        mockMvc.perform(patch("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 1))))
                .andExpect(status().isNotFound());

        // After soft delete the owner cannot edit it either
        mockMvc.perform(delete("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 1))))
                .andExpect(status().isNotFound());
    }

    // ---------- QRIS callback (snap qr-mpm-notify) ----------

    // Unique merchantPan/refs per test so NMID routing is unambiguous and the
    // global unique constraints on transaction_id/idempotency_key don't collide.
    private String callbackBody(String merchantPan, String refNo, String partnerRef, String txnId) {
        return """
                {
                  "originalReferenceNo": "%s",
                  "originalPartnerReferenceNo": "%s",
                  "latestTransactionStatus": "00",
                  "transactionStatusDesc": "SUCCESS",
                  "customerNumber": "9360082230250015792",
                  "destinationNumber": "%s",
                  "destinationAccountName": "Asep & Testing",
                  "externalStoreId": null,
                  "amount": { "value": "100.00", "currency": "IDR" },
                  "additionalInfo": {
                    "tip": "0.00", "deviceId": "AstraPay", "channel": "POS",
                    "terminalId": "5164", "userName": "AstraPay User",
                    "merchantPan": "%s", "customerPan": "9360082230250015792",
                    "issuerName": "93600822", "transactionId": "%s"
                  }
                }
                """.formatted(refNo, partnerRef, merchantPan, merchantPan, txnId);
    }

    private void registerWithNmid(String username, String nmid) throws Exception {
        register(username);
        com.bejo.entity.User u = userRepository.findByUsername(username).orElseThrow();
        u.setNmid(nmid);
        userRepository.save(u);
    }

    @Test
    void qrisCallbackRecordsTransactionWithoutAnyAuth() throws Exception {
        registerWithNmid("cb_user1", "9360082232504524231");
        String body = callbackBody("9360082232504524231", "XCBS6Z450S", "DEV20250826422460",
                "INV/QRM/4542BR/250826/2G4Y6CCZ");

        // No X-API-TOKEN, no JWT — external server-to-server callback
        mockMvc.perform(post("/snap/v1.0/qr/qr-mpm-notify")
                        .header("X-TIMESTAMP", "2020-12-23T08:46:11+07:00")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billNumber").value("XCBS6Z450S"))
                .andExpect(jsonPath("$.referenceNumber").value("DEV20250826422460"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.id").isNumber());

        // Recorded under the matched merchant and visible in history
        String token = login("cb_user1", "password1", "device-cb_user1");
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].amount").value(100))
                .andExpect(jsonPath("$.data[0].acquirer").value("AstraPay"))
                .andExpect(jsonPath("$.data[0].merchantPan").value("9360082232504524231"));

        // Raw payload stored in additional_info
        com.bejo.entity.Transaction t = transactionRepository
                .findByTransactionIdAndUsernameAndDeletedAtIsNull("INV/QRM/4542BR/250826/2G4Y6CCZ", "cb_user1")
                .orElseThrow();
        assertThat(t.getAdditionalInfo()).contains("originalReferenceNo").contains("XCBS6Z450S");
    }

    @Test
    void qrisCallbackIsIdempotent() throws Exception {
        registerWithNmid("cb_user2", "8888000011112222");
        String body = callbackBody("8888000011112222", "IDEMREF01", "IDEMPART01", "INV-IDEM-01");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/snap/v1.0/qr/qr-mpm-notify")
                            .header("X-TIMESTAMP", "2020-12-23T08:46:11+07:00")
                            .contentType(APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        String token = login("cb_user2", "password1", "device-cb_user2");
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void qrisCallbackRejectsMissingAmount() throws Exception {
        mockMvc.perform(post("/snap/v1.0/qr/qr-mpm-notify")
                        .header("X-TIMESTAMP", "2020-12-23T08:46:11+07:00")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "originalReferenceNo": "A", "originalPartnerReferenceNo": "B",
                                  "latestTransactionStatus": "00", "transactionStatusDesc": "OK" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void qrisCallbackRequiresTimestampHeader() throws Exception {
        // X-TIMESTAMP is mandatory per the spec — missing header is a 400, not a 500
        mockMvc.perform(post("/snap/v1.0/qr/qr-mpm-notify")
                        .contentType(APPLICATION_JSON)
                        .content(callbackBody("9360082232504524231", "NOTS01", "NOTS02", "INV-NOTS")))
                .andExpect(status().isBadRequest());
    }

    // ---------- devices ----------

    @Test
    void deviceOwnerCanUpdateFcmToken() throws Exception {
        String token = registerAndLogin("dev_user1");

        mockMvc.perform(put("/api/devices/device-dev_user1/fcm-token")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"pushToken\":\"updated-fcm-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pushToken").value("updated-fcm-token"));
    }

    @Test
    void userCannotUpdateAnotherUsersDeviceToken() throws Exception {
        registerAndLogin("dev_user2a");                     // owns device-dev_user2a
        String tokenB = registerAndLogin("dev_user2b");     // attacker

        mockMvc.perform(put("/api/devices/device-dev_user2a/fcm-token")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON)
                        .content("{\"pushToken\":\"hijacked-token\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------- QRIS ----------

    @Test
    void qrisLifecycleManagedUploadReset() throws Exception {
        String token = registerAndLogin("qris_user1");

        // Fresh account: MANAGED with a generated image
        mockMvc.perform(get("/api/qris")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("MANAGED"))
                .andExpect(jsonPath("$.data.qrisImage").isNotEmpty());

        // Upload a custom image
        mockMvc.perform(post("/api/qris/upload")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"qrisImage\":\"dXBsb2FkZWQtaW1hZ2U=\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/qris")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.type").value("UPLOADED"))
                .andExpect(jsonPath("$.data.qrisImage").value("dXBsb2FkZWQtaW1hZ2U="));

        // Reset back to managed
        mockMvc.perform(post("/api/qris/reset")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/qris")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.type").value("MANAGED"));
    }

    @Test
    void qrisUploadRejectsInvalidBase64() throws Exception {
        String token = registerAndLogin("qris_user2");

        mockMvc.perform(post("/api/qris/upload")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"qrisImage\":\"@@ not base64 @@\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- user profile ----------

    @Test
    void profileBankAndBusinessUpdateAndReset() throws Exception {
        String token = registerAndLogin("prof_user1");

        mockMvc.perform(patch("/api/users/me")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Updated Name",
                                "email", "updated@example.com",
                                "phone", "+628999999999"))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/users/me/bank")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankName", "Mandiri",
                                "bankNumber", "9876543210",
                                "bankAccountName", "Updated Name"))))
                .andExpect(status().isOk());

        // Bank reset restores the (empty) initial values from registration
        mockMvc.perform(post("/api/users/me/bank/reset")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/users/me/business")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "businessName", "Toko Baru"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/users/me/business/reset")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Business name restored in the next login response
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "prof_user1",
                                "password", "password1",
                                "deviceId", "dev-x"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.at("/data/businessName").asText()).isEqualTo("Warung prof_user1");
    }

    @Test
    void changePasswordFlow() throws Exception {
        String token = registerAndLogin("pass_user1");

        mockMvc.perform(patch("/api/users/me/password")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", "wrong-old",
                                "newPassword", "newpass123"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/users/me/password")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", "password1",
                                "newPassword", "newpass123"))))
                .andExpect(status().isOk());

        // Old password no longer works, new one does
        mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "pass_user1",
                                "password", "password1",
                                "deviceId", "dev-1"))))
                .andExpect(status().isBadRequest());

        login("pass_user1", "newpass123", "dev-1");
    }

    // ---------- notifications ----------

    @Test
    void notificationWithoutFirebaseConfiguredReturns503() throws Exception {
        String token = registerAndLogin("notif_user1");

        mockMvc.perform(post("/api/notifications/test")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deviceId", "device-notif_user1",
                                "title", "Pembayaran diterima",
                                "body", "Rp 10.000",
                                "data", Map.of(
                                        "transactionId", "TXN-NOTIF-1",
                                        "transactionDate", "2026-07-01T10:00:00",
                                        "amount", 10000,
                                        "issuerName", "GoPay")))))
                .andExpect(status().isServiceUnavailable());
    }

    // ---------- error handling ----------

    @Test
    void unknownRouteReturns404Json() throws Exception {
        mockMvc.perform(get("/api/does-not-exist")
                        .header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(0));
    }
}
