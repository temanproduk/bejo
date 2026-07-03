# Bejo

**Real-time payment tracking for Indonesian QRIS merchants.**

Bejo is the backend service behind the Bejo merchant mobile app. It turns a static QRIS code into a live point of sale: the moment a customer pays, the merchant's phone rings with a "payment received" notification, and every transaction is stored, searchable, and reconciled in one place.

## The problem

QRIS ([Quick Response Code Indonesian Standard](https://www.bi.go.id/QRIS)) is Bank Indonesia's unified QR payment standard — a single code accepts money from any Indonesian e-wallet or mobile bank (GoPay, OVO, DANA, ShopeePay, bank apps, and more). But a merchant with a printed QRIS sticker has no idea when a payment actually arrives. They have to open their banking app and check, which is slow, error-prone, and easy to game with fake "payment success" screenshots.

Bejo closes that gap.

## What merchants get

- **Instant payment alerts** — a push notification with an audible chime the moment money lands, so staff never have to trust a customer's screenshot.
- **Transaction history** — every payment recorded and filterable by date, for daily reconciliation and bookkeeping.
- **Manual & external entries** — record cash sales, or log transactions from another acquirer, alongside QRIS payments. Entries can be edited or removed.
- **QRIS management** — display a Bejo-generated code or upload the merchant's own image.
- **Business & bank profile** — keep merchant name, address, National Merchant ID (NMID), and payout bank details up to date, with one-tap reset back to the officially registered values.

## Account types

| Type | Who it's for | What Bejo does |
|---|---|---|
| **MANAGED** | Merchants using a Bejo-issued QRIS | Bejo manages the QR and the payment gateway integration, so alerts and the ring sound are delivered automatically on every payment. |
| **EXTERNAL** | Merchants who keep their own acquirer / QRIS | Bejo stores and tracks the transactions the merchant submits, but does not manage the QR code itself. |

## How a payment flows

1. A customer scans the merchant's QRIS and pays from any Indonesian e-wallet or bank app.
2. For MANAGED accounts, the payment gateway notifies Bejo, which sends an FCM push (with a ring sound) to the merchant's registered device and records the transaction.
3. The transaction appears in the merchant's history, available for filtering, reconciliation, and manual correction.

<br />

---
---

<div align="center">

# ⚙️ Technical Documentation

The rest of this document is the developer/operator reference for the REST API — stack, running, endpoints, and configuration.

</div>

---
---

<br />

REST API built with **Java 25 + Spring Boot 3.5**: local-DB authentication (JWT), transaction history and manual entry, QRIS image management, bank/business profile management, FCM token management, and push notification delivery.

**Production:** `https://api.bejo.temanproduk.com`

## Contents

- [Stack](#stack)
- [Running](#running)
- [Testing](#testing)
- [Authentication](#authentication)
- [Response format](#response-format)
- [Data model](#data-model)
- [Endpoints](#endpoints)
  - [Auth](#auth)
  - [Transactions](#transactions)
  - [Notifications](#notifications)
  - [Devices](#devices)
  - [User profile](#user-profile)
  - [QRIS](#qris)
  - [QRIS callback](#qris-callback)
- [Error reference](#error-reference)
- [Observability](#observability)
- [Configuration](#configuration)

---

## Stack

| | |
|---|---|
| **Runtime** | Java 25 + Spring Boot 3.5 |
| **Database** | PostgreSQL 17 via Cloud SQL (prod) / Docker (local) |
| **Auth** | JWT (JJWT) + shared API token |
| **Push** | Firebase Admin SDK (FCM) |
| **Migrations** | Flyway |
| **Metrics** | Micrometer + Prometheus |
| **Tests** | JUnit 5 + Mockito + MockMvc with in-memory H2 |
| **Timezone** | UTC+7 (Asia/Jakarta) |

---

## Running

### Production

Deployments trigger automatically on push to the `release` branch via GitHub Actions — builds the JAR, pushes a Docker image to Artifact Registry, and deploys to Cloud Run.

**First-time infrastructure setup:**

```bash
cp .env.gcp.example .env.gcp   # fill in all values
bash scripts/init-gcp.sh
```

Provisions Cloud SQL, Artifact Registry, Secret Manager, service accounts, and maps `api.bejo.temanproduk.com`. Requires `gcloud auth login` and domain verified in [Google Search Console](https://search.google.com/search-console).

### Local — Docker Compose

```bash
cp .env.example .env            # fill in API_TOKEN, JWT_SECRET, Firebase creds
docker compose up --build -d
```

Available at `http://localhost:8080`. Prometheus at `http://localhost:9090`.

### Local — Maven

Requires a running PostgreSQL instance. Flyway migrations run on startup.

```bash
export API_TOKEN=dev-token
export JWT_SECRET=YmVqby1kZXYtc2VjcmV0LWtleS1tdXN0LWJlLWNoYW5nZWQtaW4tcHJvZHVjdGlvbi1taW4tMjU2Yml0cw==
export DB_URL=jdbc:postgresql://localhost:5432/bejo
export DB_USERNAME=bejo
export DB_PASSWORD=bejo
mvn spring-boot:run
```

---

## Testing

```bash
mvn test                              # everything
mvn test -Dtest=JwtServiceTest        # one class
mvn test -Dtest=ApiIntegrationTest#logoutBlacklistsToken   # one method
```

No database or other services needed — tests are self-contained:

| Suite | Location | Approach |
|---|---|---|
| Unit tests | `src/test/java/com/bejo/service/` | Mockito, no Spring context — service logic in isolation |
| Functional tests | `src/test/java/com/bejo/web/ApiIntegrationTest.java` | `@SpringBootTest` + MockMvc through the full HTTP stack (API token filter, Spring Security, JWT filter, controllers, JPA) against in-memory H2 in PostgreSQL mode |

Test configuration lives in `src/test/resources/application-test.yml` (profile `test`): Flyway disabled, schema generated from the JPA entities via `ddl-auto: create-drop`.

---

## Authentication

Every request requires:

| Header | Endpoints | Value |
|---|---|---|
| `X-API-TOKEN` | All | Shared secret — `API_TOKEN` env var |
| `Authorization` | Protected only | `Bearer <JWT>` returned by login |

Public (no JWT): `POST /api/auth/register`, `POST /api/auth/login`  
Exempt entirely: `/actuator/**`, `/snap/**` (inbound QRIS payment callbacks — server-to-server)

Tokens expire after `JWT_EXPIRATION_MS` (default 90 days — the login response's `expiresIn` is the same value in seconds). Logout blacklists the token immediately; blacklist entries are purged automatically once expired.

---

## Response format

All responses share the same envelope:

```json
{
  "status": 1,
  "message": "...",
  "data": { },
  "count": 0
}
```

| Field | Present when |
|---|---|
| `status` | Always — `1` success, `0` error |
| `message` | Always on errors; on success for action endpoints |
| `data` | Success responses with a payload |
| `count` | List responses only |

Null fields are omitted.

---

## Data model

### User fields

| Field | Editable | Description |
|---|---|---|
| `username` | No | 3–50 chars, alphanumeric + underscore |
| `accountType` | No | `MANAGED` or `EXTERNAL` — see below |
| `name` | Yes | Display name |
| `email` | Yes | Email address |
| `isEmailVerified` | System | Always `false` (verification not yet implemented) |
| `phone` | Yes | International format e.g. `+628123456789` |
| `isPhoneVerified` | System | Always `false` (verification not yet implemented) |
| `businessName` | Yes | Current business name |
| `businessAddress` | Yes | Current business address |
| `nmid` | Yes | National Merchant ID from QRIS registration |
| `bankName` | Yes | Bank name |
| `bankNumber` | Yes | Account number (digits only) |
| `bankAccountName` | Yes | Account holder name |

Initial values (`initialBusinessName`, `initialBusinessAddress`, `initialNmid`, `initialBankName`, `initialBankNumber`, `initialBankAccountName`) are stored server-side and used by reset endpoints — not returned in responses.

### Account types

| Value | Description |
|---|---|
| `MANAGED` | QRIS managed by Bejo — includes push notification and ring sound |
| `EXTERNAL` | Own acquirer — Bejo stores and tracks transactions only |

### Transaction types

| `type` | `acquirer` | Description |
|---|---|---|
| `MANAGED` | `AstraPay` | Pushed from Bejo server via FCM |
| `MANUAL` | `Cash` | Manually entered from the mobile app |
| `PARSED` | *(from request)* | Received from another acquirer's push notification |

---

## Endpoints

### Auth

#### `POST /api/auth/register`

Creates a new user account. `X-API-TOKEN` required, no JWT.

**Request body**

| Field | Required | Validation |
|---|---|---|
| `username` | yes | 3–50 chars, alphanumeric + underscore, unique |
| `password` | yes | Min 8 chars, at least 1 letter and 1 digit |
| `name` | yes | 2–100 chars |
| `email` | yes | Valid email format |
| `phone` | yes | International format e.g. `+628123456789` |
| `businessName` | yes | Max 255 chars |
| `businessAddress` | no | Free text |
| `nmid` | no | Max 50 chars |
| `bankName` | no | Max 100 chars |
| `bankNumber` | no | Digits only, 6–20 digits |
| `bankAccountName` | no | Max 100 chars |

```json
{
  "username": "alice",
  "password": "Secret1",
  "name": "Alice",
  "email": "alice@example.com",
  "phone": "+628100000000",
  "businessName": "Alice Shop",
  "businessAddress": "Jl. Contoh No. 1",
  "nmid": "ID1026540778978"
}
```

**Responses**

| HTTP | Result |
|---|---|
| 201 | `{ "status": 1, "message": "Registrasi berhasil" }` |
| 400 | Validation error — `username wajib diisi`, `password harus mengandung minimal 1 huruf dan 1 angka`, `format email tidak valid`, etc. |
| 400 | `Username sudah digunakan` |

---

#### `POST /api/auth/login`

Authenticates and returns a JWT. Registers or updates the device's FCM push token.

**Request body**

| Field | Required | Description |
|---|---|---|
| `username` | yes | |
| `password` | yes | |
| `deviceId` | yes | Unique device identifier |
| `pushToken` | no | FCM token for push notifications. When omitted on re-login, the previously stored token for this device is kept |

**Success — HTTP 200**

```json
{
  "status": 1,
  "message": "session data is created",
  "data": {
    "username": "alice",
    "accountType": "MANAGED",
    "name": "Alice",
    "email": "alice@example.com",
    "isEmailVerified": false,
    "phone": "+628100000000",
    "isPhoneVerified": false,
    "businessName": "Alice Shop",
    "businessAddress": "Jl. Contoh No. 1",
    "nmid": "ID1026540778978",
    "bankName": "Bank BCA",
    "bankNumber": "1234567890",
    "bankAccountName": "Alice",
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 7776000
  }
}
```

**Errors:** `400 Invalid credentials`, `400 username/deviceId is required`

---

#### `POST /api/auth/logout`

Blacklists the current JWT server-side. The token is rejected on all subsequent requests, even before its natural expiry.

**Success — HTTP 200:** `{ "status": 1, "message": "Logged out successfully" }`

**Errors:** `400 Authorization header tidak valid`

---

### Transactions

#### `GET /api/transactions`

Returns the authenticated user's transaction history, newest first. Soft-deleted transactions are excluded.

**Query parameters**

| Parameter | Format | Description |
|---|---|---|
| `dateStart` | `yyyy-MM-dd` | Filter from this date (inclusive) |
| `dateEnd` | `yyyy-MM-dd` | Filter to this date (inclusive) |

**Success — HTTP 200**

```json
{
  "status": 1,
  "data": [
    {
      "transactionId": "TXN-A1B2C3D4",
      "transactionDate": "2026-06-28T09:14:00",
      "amount": 25000,
      "type": "MANAGED",
      "acquirer": "AstraPay",
      "issuerName": "BNI",
      "merchantPan": "936009XXXXXX0001",
      "customerPan": "401177XXXXXX5678",
      "rrn": "000100000002"
    }
  ],
  "count": 1
}
```

`issuerName`, `merchantPan`, `customerPan`, `rrn` are omitted when null.

---

#### `POST /api/transactions`

Adds a transaction manually or from an external acquirer. The server generates `transactionId`.

**Required header:** `Idempotency-Key: <uuid>` — resubmitting the same key returns the existing `transactionId` without inserting a duplicate.

**Request body**

| Field | Required | Description |
|---|---|---|
| `type` | yes | `MANUAL` or `PARSED` |
| `transactionDate` | yes | ISO 8601 e.g. `2026-06-28T09:14:00` |
| `amount` | yes | Amount in IDR, must be > 0 |
| `acquirer` | PARSED only | Acquirer name; required when `type` is `PARSED` |
| `issuerName` | no | Payment issuer name, max 255 chars |
| `merchantPan` | no | Max 50 chars |
| `customerPan` | no | Max 50 chars |
| `rrn` | no | Max 255 chars |

**Success — HTTP 201**

```json
{
  "status": 1,
  "message": "Transaksi berhasil ditambahkan",
  "data": { "transactionId": "TXN-A1B2C3D4E5F6" }
}
```

**Errors:** `400 Idempotency-Key header wajib diisi`, `400 type harus MANUAL atau PARSED`, `400 acquirer wajib diisi untuk type PARSED`, `400 amount harus lebih dari 0`

---

#### `PATCH /api/transactions/{transactionId}`

Edits a transaction. Partial update — only the fields present in the body are changed; `type` cannot be changed. Only the authenticated user's own (non-deleted) transactions of type `MANUAL` or `PARSED` can be edited — `MANAGED` transactions are pushed by the Bejo server and are immutable.

**Request body** (all fields optional, at least one required)

| Field | Description |
|---|---|
| `transactionDate` | ISO 8601 e.g. `2026-06-28T09:14:00` |
| `amount` | Amount in IDR, must be > 0 |
| `acquirer` | Acquirer name, max 255 chars, not blank |
| `issuerName` | Payment issuer name, max 255 chars |
| `merchantPan` | Max 50 chars |
| `customerPan` | Max 50 chars |
| `rrn` | Max 255 chars |

**Success — HTTP 200**

```json
{
  "status": 1,
  "message": "Transaksi berhasil diubah",
  "data": {
    "transactionId": "TXN-A1B2C3D4E5F6",
    "transactionDate": "2026-06-28T09:14:00",
    "amount": 60000,
    "type": "MANUAL",
    "acquirer": "Cash",
    "issuerName": "OVO",
    "merchantName": "Warung Bejo"
  }
}
```

**Errors:** `400 Tidak ada field yang diubah`, `400 Transaksi bertipe MANAGED tidak dapat diubah`, `400 amount harus lebih dari 0`, `400 acquirer tidak boleh kosong`, `404 Transaction not found` (unknown, deleted, or another user's transaction)

---

#### `DELETE /api/transactions/{transactionId}`

Soft-deletes a transaction: the row is kept in the database with a `deleted_at` timestamp and disappears from history. Deletion cannot be undone via the API. Only the authenticated user's own transactions can be deleted.

**Success — HTTP 200**

```json
{
  "status": 1,
  "message": "Transaksi berhasil dihapus"
}
```

**Errors:** `404 Transaction not found` (unknown, already deleted, or another user's transaction)

---

### Notifications

#### `POST /api/notifications`

Sends an FCM push notification to a registered device **and** inserts a `MANAGED` transaction. All validation runs first — if any check fails, nothing is sent or recorded.

**Request body**

| Field | Required | Description |
|---|---|---|
| `deviceId` | yes | Target device |
| `title` | yes | Notification title |
| `body` | yes | Notification body text |
| `data.transactionId` | yes | Unique transaction ID, max 255 chars |
| `data.transactionDate` | yes | ISO 8601 datetime |
| `data.amount` | yes | Amount > 0 |
| `data.issuerName` | yes | Payment issuer name, max 255 chars |
| `data.merchantPan` | no | Max 50 chars |
| `data.customerPan` | no | Max 50 chars |
| `data.rrn` | no | Max 255 chars |

```json
{
  "deviceId": "da8221950b5d5c15",
  "title": "Uang masuk Rp 50.000",
  "body": "Ada transaksi masuk sejumlah Rp 50.000",
  "data": {
    "transactionId": "TXN-A1B2C3D4",
    "transactionDate": "2026-06-28T09:14:00",
    "amount": 50000,
    "issuerName": "GoPay",
    "rrn": "000100000001"
  }
}
```

**Success — HTTP 200:** `{ "status": 1, "message": "Notification sent" }`

**Errors**

| HTTP | Message | Cause |
|---|---|---|
| 400 | `transactionId wajib diisi` | Missing field |
| 400 | `amount harus lebih dari 0` | Non-positive amount |
| 400 | `transactionId sudah terdaftar: <id>` | Duplicate transaction |
| 404 | `Device not found: <deviceId>` | Unknown device |
| 404 | `No FCM token registered for device: <deviceId>` | No FCM token on device |
| 503 | `Firebase is not configured` | Firebase credentials missing |
| 502 | `Failed to send notification: ...` | FCM delivery error |

---

### Devices

#### `PUT /api/devices/{deviceId}/fcm-token`

Updates the FCM push token for a device. Only devices belonging to the authenticated user can be updated — the `(username, deviceId)` session is created at login.

**Request body:** `{ "pushToken": "fcm-token-string" }`

**Success — HTTP 200**

```json
{
  "status": 1,
  "message": "Push Token data is updated",
  "data": { "deviceId": "device-001", "pushToken": "new-fcm-token" }
}
```

**Errors:** `404 Device ID not found: <deviceId>` — the device doesn't exist or belongs to another user

---

### User profile

All endpoints below require JWT.

#### `PATCH /api/users/me` — Update personal info

| Field | Required | Validation |
|---|---|---|
| `name` | yes | 2–100 chars |
| `email` | yes | Valid email |
| `phone` | yes | International format e.g. `+628123456789` |

**Success — HTTP 200:** `{ "status": 1, "message": "Profile updated successfully" }`

---

#### `PATCH /api/users/me/business` — Update business info

Does not affect the initial values stored at registration.

| Field | Required | Validation |
|---|---|---|
| `businessName` | yes | Max 255 chars |
| `businessAddress` | no | Free text |
| `nmid` | no | Max 50 chars |

**Success — HTTP 200:** `{ "status": 1, "message": "Informasi bisnis berhasil diperbarui" }`

---

#### `POST /api/users/me/business/reset` — Reset business info

Reverts `businessName`, `businessAddress`, and `nmid` to registration values.

**Success — HTTP 200:** `{ "status": 1, "message": "Informasi bisnis berhasil direset ke data awal" }`

---

#### `PATCH /api/users/me/bank` — Update bank info

Does not affect the initial values stored at registration.

| Field | Required | Validation |
|---|---|---|
| `bankName` | yes | Max 100 chars |
| `bankNumber` | yes | Digits only, 6–20 digits |
| `bankAccountName` | yes | 2–100 chars |

**Success — HTTP 200:** `{ "status": 1, "message": "Informasi rekening berhasil diperbarui" }`

---

#### `POST /api/users/me/bank/reset` — Reset bank info

Reverts `bankName`, `bankNumber`, and `bankAccountName` to registration values.

**Success — HTTP 200:** `{ "status": 1, "message": "Informasi rekening berhasil direset ke data awal" }`

---

#### `PATCH /api/users/me/password` — Change password

| Field | Required | Validation |
|---|---|---|
| `oldPassword` | yes | Current password |
| `newPassword` | yes | Min 8 chars, at least 1 letter and 1 digit |

**Errors:** `400 Password saat ini tidak sesuai`, `400 Password baru minimal 8 karakter`, `400 Password baru harus mengandung minimal 1 huruf dan 1 angka`

---

### QRIS

#### `GET /api/qris` — Get QRIS image

Returns the QRIS image and business info. `type` indicates whether the image is Bejo-generated or user-uploaded.

**Success — HTTP 200**

```json
{
  "status": 1,
  "data": {
    "type": "UPLOADED",
    "qrisImage": "<base64-encoded PNG>",
    "businessName": "Alice Shop",
    "businessAddress": "Jl. Contoh No. 1",
    "nmid": "ID1026540778978"
  }
}
```

| `type` | Description |
|---|---|
| `MANAGED` | Auto-generated placeholder image |
| `UPLOADED` | User-uploaded image (overrides MANAGED) |

---

#### `POST /api/qris/upload` — Upload custom QRIS image

Once uploaded, `GET /api/qris` returns this image with `type: UPLOADED`.

**Request body:** `{ "qrisImage": "<base64-encoded image>" }` — max ±5 MB

**Success — HTTP 200:** `{ "status": 1, "message": "QRIS berhasil diupload" }`

**Errors:** `400 qrisImage wajib diisi`, `400 qrisImage bukan format base64 yang valid`, `400 qrisImage terlalu besar (maksimal ±5MB)`

---

#### `POST /api/qris/reset` — Reset QRIS image

Clears the uploaded image and reverts to the initial QRIS image.

**Success — HTTP 200:** `{ "status": 1, "message": "QRIS berhasil direset ke versi generated" }`

---

### QRIS callback

#### `POST /snap/v1.0/qr/qr-mpm-notify`

Inbound QRIS payment notification (SNAP-like QR MPM notify) from the QRIS merchant service (e.g. AstraPay). **Server-to-server — no `X-API-TOKEN` and no JWT.** Records the payment as a `MANAGED` transaction and stores the **raw payload** in the transaction's `additionalInfo` column.

This endpoint does **not** use the standard `{ status, message, data }` envelope — it returns the callback contract's own acknowledgment shape.

**Headers**

| Header | Mandatory | Description |
|---|---|---|
| `Content-Type: application/json` | ✅ | |
| `X-TIMESTAMP` | ⬜ | ISO-8601 transaction time; accepts an offset (`2020-12-23T08:46:11+07:00`), `Z`, or none. Recorded as the transaction date; a missing/invalid value falls back to now. |

**Request body** (unknown fields are ignored)

```json
{
  "originalReferenceNo": "XCBS6Z450S",
  "originalPartnerReferenceNo": "DEV20250826422460",
  "latestTransactionStatus": "00",
  "transactionStatusDesc": "SUCCESS",
  "customerNumber": "9360082230250015792",
  "destinationNumber": "9360082232504524231",
  "destinationAccountName": "Asep & Testing",
  "amount": { "value": "100.00", "currency": "IDR" },
  "additionalInfo": {
    "tip": "0.00", "deviceId": "AstraPay", "terminalId": "5164",
    "merchantPan": "9360082232504524231", "customerPan": "9360082230250015792",
    "issuerName": "93600822", "transactionId": "INV/QRM/4542BR/250826/2G4Y6CCZ"
  }
}
```

`originalReferenceNo`, `originalPartnerReferenceNo`, `latestTransactionStatus`, and `amount.value` are required.

**Behavior**

- **Merchant routing** — the payment is attributed to the Bejo user whose `nmid` equals the payload's `merchantPan` (falling back to `destinationNumber`). If none matches, it falls back to the configured default user (`app.callback.default-username`, default `admin`); if that user doesn't exist either, returns `404`.
- **Idempotency** — keyed on `originalReferenceNo` + `originalPartnerReferenceNo`; a duplicate delivery returns the existing transaction without inserting again.
- **Status mapping** — `00`→SUCCESS, `01`→PENDING, `02`→FAILED, `03`→REFUNDED, `04`→CANCELLED, `05`→EXPIRED, else UNKNOWN.
- The full payload JSON is saved to the transaction's `additionalInfo` (a general-purpose column, added in migration V3).

**Success — HTTP 200** (acknowledgment)

```json
{
  "id": 123,
  "billNumber": "XCBS6Z450S",
  "referenceNumber": "DEV20250826422460",
  "amount": 100.00,
  "tip": 0.00,
  "status": "SUCCESS",
  "createdAt": "2026-07-03T10:30:00"
}
```

**Errors:** `400` invalid/missing required fields, `404` no merchant match and no default user

---

## Error reference

Errors shared across all endpoints:

| HTTP | Message | Cause |
|---|---|---|
| 400 | `Request body tidak valid atau tidak ditemukan` | Malformed or missing JSON body |
| 400 | `Format parameter tidak valid: <param>` | Invalid query parameter type |
| 401 | `Missing or invalid X-API-TOKEN` | Wrong or absent API token |
| 401 | `Unauthorized — include Authorization: Bearer <token> header` | Missing, expired, or logged-out (blacklisted) JWT |
| 404 | `Route not found` | Unknown path |
| 405 | `Method not allowed: <METHOD>` | Wrong HTTP method |
| 409 | `Username sudah digunakan` | Username already registered |
| 409 | `transactionId sudah terdaftar` | Duplicate `transaction_id` |
| 409 | `Permintaan dengan Idempotency-Key ini sedang diproses, coba lagi sebentar` | Concurrent request with the same `Idempotency-Key` |
| 409 | `Data tidak valid atau sudah terdaftar` | Other database constraint violation |
| 500 | `Internal server error` | Unexpected server error |

---

## Observability

| Endpoint | Description |
|---|---|
| `GET /actuator/health` | Liveness / readiness |
| `GET /actuator/metrics` | Micrometer metrics |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |

**Custom metrics**

| Metric | Tags | Description |
|---|---|---|
| `login.attempts_total` | `result=success\|failure` | Login outcomes |
| `transactions.fetched_total` | — | Total transaction records returned |
| `notifications.sent_total` | `result=success\|failure` | FCM notification outcomes |

---

## Configuration

### App environment variables

Used locally via `.env` (Docker Compose) or exported directly (Maven).

| Variable | Required | Description |
|---|---|---|
| `API_TOKEN` | yes | Shared secret for `X-API-TOKEN` header |
| `JWT_SECRET` | yes | Base64-encoded signing key, min 256 bits — `openssl rand -base64 32` |
| `JWT_EXPIRATION_MS` | no | Token TTL in ms. Default `7776000000` (90 days); `0` disables expiry |
| `DB_URL` | yes | JDBC URL e.g. `jdbc:postgresql://localhost:5432/bejo` |
| `DB_USERNAME` | yes | PostgreSQL username |
| `DB_PASSWORD` | yes | PostgreSQL password |
| `FIREBASE_CREDENTIALS_BASE64` | no* | Base64-encoded Firebase service account JSON |
| `FIREBASE_CREDENTIALS_PATH` | no* | Path to Firebase service account JSON file |
| `SEED_PASSWORD` | no | Seed user password — seeding skipped if blank |
| `SEED_USERNAME` | no | Default: `admin` |
| `SEED_NAME` | no | Default: `Admin` |
| `SEED_BUSINESS_NAME` | no | Default: `My Business` |
| `SEED_BUSINESS_ADDRESS` | no | |
| `SEED_NMID` | no | National Merchant ID for seed user |

\* One of the two Firebase options is required to use push notifications.

### GCP deployment

Sensitive values are stored in **Secret Manager** — never passed as plain variables.

**GitHub Actions variables** (Settings → Variables → Actions)

| Variable | Description |
|---|---|
| `GCP_PROJECT_ID` | GCP project ID |
| `GCP_REGION` | Region e.g. `asia-southeast2` |
| `GCP_SERVICE_NAME` | Cloud Run service name |
| `ARTIFACT_REGISTRY_REPO` | Artifact Registry repository name |
| `CLOUD_SQL_INSTANCE_CONNECTION_NAME` | e.g. `project:region:instance` |
| `CLOUD_SQL_DB_NAME` | Database name |
| `CLOUD_SQL_DB_USER` | Database user |
| `CLOUD_RUN_SA` | Cloud Run service account name |
| `CUSTOM_DOMAIN` | `api.bejo.temanproduk.com` |
| `SEED_USERNAME` / `SEED_NAME` / `SEED_BUSINESS_NAME` / `SEED_BUSINESS_ADDRESS` / `SEED_NMID` | Seed user values |

**GitHub Actions secret** (Settings → Secrets → Actions)

| Secret | Description |
|---|---|
| `GCP_SA_KEY` | Base64-encoded service account JSON key — printed by `scripts/init-gcp.sh` |
