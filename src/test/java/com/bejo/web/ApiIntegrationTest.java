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
import java.util.List;
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
    @Autowired private com.bejo.repository.SessionRepository sessionRepository;

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
        mockMvc.perform(post("/api/users/register")
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

    // Posts a MANUAL transaction with an explicit date + amount; used by summary tests.
    private void addManualTxn(String token, String idempotencyKey, String transactionDate, int amount) throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL",
                                "transactionDate", transactionDate,
                                "amount", amount))))
                .andExpect(status().isCreated());
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

    @Test
    void actuatorMetricsRequiresApiToken() throws Exception {
        // Non-health actuator endpoints must NOT be public
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics").header("X-API-TOKEN", API_TOKEN))
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
                .andExpect(jsonPath("$.data.accountType").value("SELF_SERVICE"))
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

        mockMvc.perform(post("/api/users/register")
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

        mockMvc.perform(post("/api/users/register")
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

    @Test
    void logoutWithDeviceIdClearsTheDeviceFcmToken() throws Exception {
        String token = registerAndLogin("logout_dev1");
        String deviceId = "device-logout_dev1";                 // registerAndLogin's device
        // Login stored an FCM token for the device
        assertThat(sessionRepository.findByUsernameAndDeviceId("logout_dev1", deviceId)
                .orElseThrow().getPushToken()).isNotBlank();

        mockMvc.perform(post("/api/auth/logout")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("deviceId", deviceId))))
                .andExpect(status().isOk());

        // The device's FCM token is cleared and the session is marked logged out
        var session = sessionRepository.findByUsernameAndDeviceId("logout_dev1", deviceId).orElseThrow();
        assertThat(session.getPushToken()).isNull();
        assertThat(session.getLoginStatus()).isFalse();
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
    void transactionHistoryIsPaginated() throws Exception {
        String token = registerAndLogin("txn_page1");

        // Create 3 transactions
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/transactions")
                            .header("X-API-TOKEN", API_TOKEN)
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "page-key-" + i)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "type", "MANUAL",
                                    "transactionDate", "2026-07-0" + (i + 1) + "T10:00:00",
                                    "amount", 1000 * (i + 1)))))
                    .andExpect(status().isCreated());
        }

        // Default (no params) = page 1, all 3 fit on one page of 50
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.total").value(3));

        // size=2 → page 1 has 2 items, total still 3 across 2 pages
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.total").value(3));

        // page 2 of size 2 → the remaining 1 item
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void transactionSummaryReturnsTodayYesterdayAndMonthSums() throws Exception {
        String token = registerAndLogin("txn_summary1");
        // Reference "today" — use a fixed date so the buckets are deterministic
        String today = "2026-07-15";

        // 2 today (10k + 15k), 1 yesterday (7k), 1 earlier same month (5k), 1 previous month (99k)
        addManualTxn(token, "sum-1", "2026-07-15T09:00:00", 10000);
        addManualTxn(token, "sum-2", "2026-07-15T18:30:00", 15000);
        addManualTxn(token, "sum-3", "2026-07-14T12:00:00", 7000);
        addManualTxn(token, "sum-4", "2026-07-03T08:00:00", 5000);
        addManualTxn(token, "sum-5", "2026-06-30T20:00:00", 99000);

        mockMvc.perform(get("/api/transactions/summary")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .param("date", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value(today))
                .andExpect(jsonPath("$.data.today.totalAmount").value(25000))
                .andExpect(jsonPath("$.data.today.count").value(2))
                .andExpect(jsonPath("$.data.yesterday.totalAmount").value(7000))
                .andExpect(jsonPath("$.data.yesterday.count").value(1))
                // July only: 10k + 15k + 7k + 5k = 37k across 4 txns (June's 99k excluded)
                .andExpect(jsonPath("$.data.month.totalAmount").value(37000))
                .andExpect(jsonPath("$.data.month.count").value(4));
    }

    @Test
    void transactionSummaryIsZeroWhenNoTransactions() throws Exception {
        String token = registerAndLogin("txn_summary2");

        mockMvc.perform(get("/api/transactions/summary")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .param("date", "2026-07-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.today.totalAmount").value(0))
                .andExpect(jsonPath("$.data.today.count").value(0))
                .andExpect(jsonPath("$.data.month.totalAmount").value(0));
    }

    @Test
    void bulkCreateSyncsOfflineTransactionsIdempotently() throws Exception {
        String token = registerAndLogin("txn_bulk1");

        Map<String, Object> item1 = Map.of("idempotencyKey", "off-1",
                "transaction", Map.of("type", "MANUAL", "transactionDate", "2026-07-01T10:00:00", "amount", 10000));
        Map<String, Object> item2 = Map.of("idempotencyKey", "off-2",
                "transaction", Map.of("type", "PARSED", "transactionDate", "2026-07-01T11:00:00", "amount", 20000, "acquirer", "AstraPay"));
        // Invalid: negative amount
        Map<String, Object> bad = Map.of("idempotencyKey", "off-3",
                "transaction", Map.of("type", "MANUAL", "transactionDate", "2026-07-01T12:00:00", "amount", -5));

        String body = objectMapper.writeValueAsString(Map.of("transactions", List.of(item1, item2, bad)));

        // First sync: 2 created, 1 failed
        mockMvc.perform(post("/api/transactions/bulk")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.created").value(2))
                .andExpect(jsonPath("$.data.failed").value(1));

        // Re-sync the same batch (client retry): the 2 good ones are now duplicates
        mockMvc.perform(post("/api/transactions/bulk")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(0))
                .andExpect(jsonPath("$.data.duplicate").value(2))
                .andExpect(jsonPath("$.data.failed").value(1));

        // Exactly the 2 valid transactions exist — no duplicates from the re-sync
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void bulkCreateEmptyListIsRejected() throws Exception {
        String token = registerAndLogin("txn_bulk2");

        mockMvc.perform(post("/api/transactions/bulk")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("transactions", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));
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

    // Builds a well-formed callback payload. (PoC: the service ignores merchantPan/refs
    // and records against the seed user regardless.)
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

    // PoC: the callback records against the seed user (app.callback.default-username =
    // cb_seed in test config). Register it idempotently; count its transactions by delta
    // so these tests are isolated from the shared H2 state.
    private void ensureCallbackSeedUser() throws Exception {
        if (userRepository.findByUsername("cb_seed").isEmpty()) register("cb_seed");
    }

    private long seedTxnCount() {
        return transactionRepository.findByUsernameAndDeletedAtIsNullOrderByTransactionDateDesc(
                "cb_seed", org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements();
    }

    @Test
    void qrisCallbackRecordsTransactionForSeedUserWithoutAnyAuth() throws Exception {
        ensureCallbackSeedUser();
        long before = seedTxnCount();
        String body = callbackBody("9360082232504524231", "XCBS6Z450S", "DEV20250826422460",
                "INV/QRM/4542BR/250826/2G4Y6CCZ");

        // No X-API-TOKEN, no JWT — external server-to-server callback
        mockMvc.perform(post("/snap/v1.0/qr/qr-mpm-notify")
                        .header("X-TIMESTAMP", "2020-12-23T08:46:11+07:00")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.id").isNumber());

        // Recorded as a MANAGED transaction against the seed user
        assertThat(seedTxnCount()).isEqualTo(before + 1);
        String token = login("cb_seed", "password1", "device-cb_seed");
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[0].amount").value(100))
                .andExpect(jsonPath("$.data[0].acquirer").value("AstraPay"));
    }

    @Test
    void qrisCallbackCreatesANewTransactionEachTime() throws Exception {
        ensureCallbackSeedUser();
        long before = seedTxnCount();
        String body = callbackBody("8888000011112222", "IDEMREF01", "IDEMPART01", "INV-IDEM-01");

        // PoC: no reference to dedupe on — each callback creates a new transaction
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/snap/v1.0/qr/qr-mpm-notify")
                            .header("X-TIMESTAMP", "2020-12-23T08:46:11+07:00")
                            .contentType(APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        assertThat(seedTxnCount()).isEqualTo(before + 2);
    }

    @Test
    void qrisCallbackWithoutAmountDefaultsToZero() throws Exception {
        ensureCallbackSeedUser();
        long before = seedTxnCount();

        // Buggy minimal payload — no amount; PoC records it with amount 0 (still 200)
        mockMvc.perform(post("/snap/v1.0/qr/qr-mpm-notify")
                        .header("X-TIMESTAMP", "2020-12-23T08:46:11+07:00")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "latestTransactionStatus": "00", "transactionStatusDesc": "OK" }
                                """))
                .andExpect(status().isOk());

        assertThat(seedTxnCount()).isEqualTo(before + 1);
    }

    @Test
    void qrisCallbackWorksWithoutTimestampHeader() throws Exception {
        ensureCallbackSeedUser();

        // X-TIMESTAMP is now optional (PoC) — missing header falls back to now(), still 200
        mockMvc.perform(post("/snap/v1.0/qr/qr-mpm-notify")
                        .contentType(APPLICATION_JSON)
                        .content(callbackBody("9360082232504524231", "NOTS01", "NOTS02", "INV-NOTS")))
                .andExpect(status().isOk());
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
    void qrisLifecycleSelfServiceOptionalUploadReset() throws Exception {
        String token = registerAndLogin("qris_user1");

        // Fresh self-service account: QRIS is optional, no row exists yet
        mockMvc.perform(get("/api/qris")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        // Upload a custom image — creates the QRIS row on first upload
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

        // Reset clears the uploaded image; no placeholder is generated, so the
        // managed image is an empty state (qrisImage omitted)
        mockMvc.perform(post("/api/qris/reset")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/qris")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.type").value("MANAGED"))
                .andExpect(jsonPath("$.data.qrisImage").doesNotExist());
    }

    @Test
    void internalRegistrationCreatesManagedUser() throws Exception {
        // Internal registration requires a valid JWT (it is not public)
        String adminToken = registerAndLogin("int_admin1");

        Map<String, Object> body = registerBody("int_managed1");
        mockMvc.perform(post("/api/users/internal/register")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(1));

        // The created account is MANAGED...
        String managedToken = login("int_managed1", "password1", "dev-managed1");
        mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "int_managed1",
                                "password", "password1",
                                "deviceId", "dev-managed1b"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountType").value("MANAGED"));

        // ...and QRIS is optional — no row is created until the user uploads one
        mockMvc.perform(get("/api/qris")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + managedToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void internalRegistrationRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/users/internal/register")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody("int_noauth1"))))
                .andExpect(status().isUnauthorized());
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

    @Test
    void profilePictureGetSetFlow() throws Exception {
        String token = registerAndLogin("pic_user1");

        // No picture yet — the field is present and explicitly null (no placeholder)
        mockMvc.perform(get("/api/users/me/profile-picture")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profilePicture").value(org.hamcrest.Matchers.nullValue()));

        // Set a picture (valid base64)
        String base64 = java.util.Base64.getEncoder().encodeToString("fake-image-bytes".getBytes());
        mockMvc.perform(put("/api/users/me/profile-picture")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("profilePicture", base64))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profilePicture").value(base64));

        // GET returns it
        mockMvc.perform(get("/api/users/me/profile-picture")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.profilePicture").value(base64));

        // DELETE clears it
        mockMvc.perform(delete("/api/users/me/profile-picture")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1));

        // GET is null again
        mockMvc.perform(get("/api/users/me/profile-picture")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.profilePicture").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void profilePictureRejectsInvalidBase64() throws Exception {
        String token = registerAndLogin("pic_user2");

        mockMvc.perform(put("/api/users/me/profile-picture")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("profilePicture", "@@ not base64 @@"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void profilePictureRequiresJwt() throws Exception {
        mockMvc.perform(get("/api/users/me/profile-picture")
                        .header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isUnauthorized());
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

    // ---------- products & categories ----------

    private long createCategory(String token, String name) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/categories")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).at("/data/id").asLong();
    }

    @Test
    void productCategoryAndPhotoLifecycle() throws Exception {
        String token = registerAndLogin("prod_user1");
        long catId = createCategory(token, "Minuman");

        // Create a product in that category, with a small base64 photo
        String photo = java.util.Base64.getEncoder().encodeToString("img".getBytes());
        Map<String, Object> body = new HashMap<>();
        body.put("sku", "KOPI-01");
        body.put("name", "Kopi Susu");
        body.put("shortDescription", "Kopi + susu segar");
        body.put("categoryId", catId);
        body.put("price", 18000);
        body.put("costPrice", 10000);
        body.put("stock", 25);
        body.put("photo", photo);

        MvcResult created = mockMvc.perform(post("/api/products")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.categoryName").value("Minuman"))
                .andExpect(jsonPath("$.data.shortDescription").value("Kopi + susu segar"))
                .andExpect(jsonPath("$.data.stock").value(25))
                .andReturn();
        long productId = objectMapper.readTree(created.getResponse().getContentAsString()).at("/data/id").asLong();

        // List omits the photo to keep payloads small
        mockMvc.perform(get("/api/products")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].photo").doesNotExist())
                .andExpect(jsonPath("$.data[0].categoryName").value("Minuman"));

        // Detail includes the photo
        mockMvc.perform(get("/api/products/" + productId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.photo").value(photo));

        // Filter by category
        mockMvc.perform(get("/api/products?categoryId=" + catId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(1));

        // Update stock + short description (inventory + editable description via PATCH)
        mockMvc.perform(patch("/api/products/" + productId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stock", 40, "shortDescription", "Promo!"))))
                .andExpect(jsonPath("$.data.stock").value(40))
                .andExpect(jsonPath("$.data.shortDescription").value("Promo!"));

        // Deleting the category un-categorizes the product (not deletes it)
        mockMvc.perform(delete("/api/categories/" + catId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/products/" + productId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.categoryId").doesNotExist())
                .andExpect(jsonPath("$.data.categoryName").doesNotExist());

        // Delete the product
        mockMvc.perform(delete("/api/products/" + productId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/products/" + productId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void productDuplicateSkuRejected() throws Exception {
        String token = registerAndLogin("prod_user2");
        Map<String, Object> body = Map.of("sku", "DUP-1", "name", "A", "price", 1000);

        mockMvc.perform(post("/api/products")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/products")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void productWithForeignCategoryRejected() throws Exception {
        String tokenA = registerAndLogin("prod_user3a");
        String tokenB = registerAndLogin("prod_user3b");
        long catA = createCategory(tokenA, "Makanan");

        // User B cannot attach user A's category
        mockMvc.perform(post("/api/products")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sku", "X-1", "name", "X", "price", 1000, "categoryId", catA))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void productRequiresJwt() throws Exception {
        mockMvc.perform(get("/api/products")
                        .header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bulkStockAdjustmentSetsCountsAndReportsVariance() throws Exception {
        String token = registerAndLogin("opname_user1");
        long p1 = createProduct(token, "OP-1", 10, null);
        long p2 = createProduct(token, "OP-2", 5, null);

        String body = objectMapper.writeValueAsString(Map.of("items", List.of(
                Map.of("productId", p1, "stock", 8),    // counted 8  (-2)
                Map.of("productId", p2, "stock", 12)))); // counted 12 (+7)

        mockMvc.perform(post("/api/products/stock-adjustments")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].previousStock").value(10))
                .andExpect(jsonPath("$.data.items[0].newStock").value(8))
                .andExpect(jsonPath("$.data.items[0].difference").value(-2))
                .andExpect(jsonPath("$.data.items[1].difference").value(7));

        // Stock actually updated
        assertThat(stockOf(token, p1)).isEqualTo(8);
        assertThat(stockOf(token, p2)).isEqualTo(12);
    }

    @Test
    void bulkStockAdjustmentUnknownProductRejectedAtomically() throws Exception {
        String token = registerAndLogin("opname_user2");
        long p1 = createProduct(token, "OP-1", 10, null);

        String body = objectMapper.writeValueAsString(Map.of("items", List.of(
                Map.of("productId", p1, "stock", 99),
                Map.of("productId", 888888, "stock", 3))));

        mockMvc.perform(post("/api/products/stock-adjustments")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));

        // Atomic: the valid product's stock is unchanged
        assertThat(stockOf(token, p1)).isEqualTo(10);
    }

    // ---------- purchases (restock) ----------

    private long createProduct(String token, String sku, int stock, Integer costPrice) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("sku", sku);
        body.put("name", "Product " + sku);
        body.put("stock", stock);
        if (costPrice != null) body.put("costPrice", costPrice);
        MvcResult r = mockMvc.perform(post("/api/products")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).at("/data/id").asLong();
    }

    private int stockOf(String token, long productId) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/products/" + productId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).at("/data/stock").asInt();
    }

    @Test
    void purchaseRaisesStockOfAllProductsAndPersists() throws Exception {
        String token = registerAndLogin("buy_user1");
        long p1 = createProduct(token, "P1", 10, 5000);
        long p2 = createProduct(token, "P2", 3, 8000);

        String body = objectMapper.writeValueAsString(Map.of(
                "note", "restock pagi",
                "items", List.of(
                        Map.of("productId", p1, "quantity", 20),                  // uses costPrice 5000
                        Map.of("productId", p2, "quantity", 5, "unitPrice", 9000)))); // override

        MvcResult created = mockMvc.perform(post("/api/stock-mutations")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("PURCHASE"))
                .andExpect(jsonPath("$.data.totalAmount").value(145000)) // 20*5000 + 5*9000
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].previousStock").value(10))
                .andExpect(jsonPath("$.data.items[0].newStock").value(30))
                .andExpect(jsonPath("$.data.items[0].quantityChange").value(20))
                .andReturn();
        long mutationId = objectMapper.readTree(created.getResponse().getContentAsString()).at("/data/id").asLong();

        // Both products' stock rose
        assertThat(stockOf(token, p1)).isEqualTo(30);
        assertThat(stockOf(token, p2)).isEqualTo(8);

        // History: list includes items (product summary + before/after stock)
        mockMvc.perform(get("/api/stock-mutations")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].items.length()").value(2))
                .andExpect(jsonPath("$.data[0].items[0].name").value("Product P1"))
                .andExpect(jsonPath("$.data[0].items[0].newStock").value(30));

        mockMvc.perform(get("/api/stock-mutations/" + mutationId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].sku").value("P1"));
    }

    @Test
    void purchaseWithUnknownProductRejectedAndNoStockChange() throws Exception {
        String token = registerAndLogin("buy_user2");
        long p1 = createProduct(token, "P1", 10, 5000);

        String body = objectMapper.writeValueAsString(Map.of("items", List.of(
                Map.of("productId", p1, "quantity", 5),
                Map.of("productId", 999999, "quantity", 2))));

        mockMvc.perform(post("/api/stock-mutations")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));

        // Atomic: the valid product's stock is unchanged
        assertThat(stockOf(token, p1)).isEqualTo(10);
    }

    @Test
    void purchaseEmptyItemsRejected() throws Exception {
        String token = registerAndLogin("buy_user3");

        mockMvc.perform(post("/api/stock-mutations")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stockMutationHistoryCapturesPurchaseAdjustmentAndUpdate() throws Exception {
        String token = registerAndLogin("mut_user1");
        long p1 = createProduct(token, "M1", 10, 5000);

        // 1) Restock (PURCHASE): +5
        mockMvc.perform(post("/api/stock-mutations")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of(
                                Map.of("productId", p1, "quantity", 5))))))
                .andExpect(status().isCreated());

        // 2) Stock opname (ADJUSTMENT): set to 20, with an optional note
        mockMvc.perform(post("/api/products/stock-adjustments")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "note", "opname sore",
                                "items", List.of(Map.of("productId", p1, "stock", 20))))))
                .andExpect(status().isOk());

        // 3) Product edit changing stock (UPDATE): set to 25
        mockMvc.perform(patch("/api/products/" + p1)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stock", 25))))
                .andExpect(status().isOk());

        // 4) A non-stock edit (name only) must NOT create a mutation
        mockMvc.perform(patch("/api/products/" + p1)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Renamed"))))
                .andExpect(status().isOk());

        // History has exactly the 3 stock-changing mutations, newest first
        mockMvc.perform(get("/api/stock-mutations")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.data[0].type").value("UPDATE"))
                .andExpect(jsonPath("$.data[1].type").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.data[1].note").value("opname sore"))
                .andExpect(jsonPath("$.data[2].type").value("PURCHASE"));
    }

    // ---------- app settings ----------

    @Test
    void appSettingsDefaultsThenUpdate() throws Exception {
        String token = registerAndLogin("appset_user1");

        // Defaults when nothing has been synced yet
        mockMvc.perform(get("/api/app-settings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blacklistEnabled").value(true))
                .andExpect(jsonPath("$.data.whitelistEnabled").value(false))
                .andExpect(jsonPath("$.data.qrisWithProfilePicture").value(false))
                .andExpect(jsonPath("$.data.inventoryEnabled").value(false))
                .andExpect(jsonPath("$.data.monitoredApps").isEmpty())
                .andExpect(jsonPath("$.data.includeKeywords").isEmpty());

        // Save monitored apps (issuer is trimmed + lowercased)
        mockMvc.perform(put("/api/app-settings/monitored-apps")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"apps\":{\"id.dana\":\"DANA\",\"com.gojek.app\":\"gopay\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monitoredApps['id.dana']").value("dana"));

        // Save keywords + toggle (Promo/promo dedupe to one entry)
        mockMvc.perform(put("/api/app-settings/keywords")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"excludeKeywords\":[\"Promo\",\"promo\",\"diskon\"],\"whitelistEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.excludeKeywords[0]").value("promo"))
                .andExpect(jsonPath("$.data.excludeKeywords[1]").value("diskon"))
                .andExpect(jsonPath("$.data.whitelistEnabled").value(true));

        // GET reflects the saved state; untouched blacklist toggle stays at its default
        mockMvc.perform(get("/api/app-settings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.monitoredApps['com.gojek.app']").value("gopay"))
                .andExpect(jsonPath("$.data.excludeKeywords[0]").value("promo"))
                .andExpect(jsonPath("$.data.blacklistEnabled").value(true));
    }

    @Test
    void appSettingsConfigTogglesFlow() throws Exception {
        String token = registerAndLogin("appset_cfg1");

        // Enable "QRIS with profile picture"
        mockMvc.perform(put("/api/app-settings/qris-with-profile-picture")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qrisWithProfilePicture").value(true))
                .andExpect(jsonPath("$.data.inventoryEnabled").value(false));

        // Enable inventory management
        mockMvc.perform(put("/api/app-settings/inventory")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryEnabled").value(true));

        // GET reflects both flags
        mockMvc.perform(get("/api/app-settings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.qrisWithProfilePicture").value(true))
                .andExpect(jsonPath("$.data.inventoryEnabled").value(true));

        // Missing 'enabled' is rejected
        mockMvc.perform(put("/api/app-settings/inventory")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void appSettingsRejectInvalidPackage() throws Exception {
        String token = registerAndLogin("appset_user2");

        mockMvc.perform(put("/api/app-settings/monitored-apps")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"apps\":{\"bad package!\":\"x\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void appSettingsRequireJwt() throws Exception {
        mockMvc.perform(get("/api/app-settings")
                        .header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isUnauthorized());
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
