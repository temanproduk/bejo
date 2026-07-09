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
- **QRIS management** — display the merchant's managed QRIS or upload their own image (no image is synthesized; accounts without one return `null`).
- **Business & bank profile** — keep merchant name, address, National Merchant ID (NMID), and payout bank details up to date, with one-tap reset back to the officially registered values.

## Account types

| Type | Who it's for | How it's created |
|---|---|---|
| **MANAGED** | Merchants provisioned by Bejo internally | Created by the seed process or the internal registration API (`/api/users/internal/register`). |
| **SELF_SERVICE** | Merchants who sign up themselves | Created through the public registration API (`/api/users/register`) from the app. |

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
  - [Users](#users)
  - [Transactions](#transactions)
  - [Notifications](#notifications)
  - [Devices](#devices)
  - [User profile](#user-profile)
  - [Products & categories](#products--categories)
  - [Stock mutations](#stock-mutations)
  - [App settings](#app-settings)
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

**Runtime scaling (Cloud Run, us-central1):** 1 vCPU / 512 MiB, **0–2 instances** — scales to **zero** when idle so there's no cost between requests, up to 2 under load. CPU is throttled to request time (request-based billing). `spring.main.lazy-initialization` is enabled to keep cold starts (from scale-to-zero) fast. Old images are pruned by an Artifact Registry cleanup policy (keep 5 most recent / delete >30 days).

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

Public (no JWT): `POST /api/users/register`, `POST /api/auth/login`  
Exempt from the API token: `/actuator/health` (for Cloud Run probes) and `/snap/**` (inbound QRIS payment callbacks — server-to-server). All other `/actuator/*` endpoints (metrics, prometheus, info) **require** `X-API-TOKEN`.

Tokens expire after `JWT_EXPIRATION_MS` (default 7 days — the login response's `expiresIn` is the same value in seconds). Logout blacklists the token immediately; blacklist entries are purged automatically once expired.

Responses carry standard security headers: HSTS (`max-age` 1 year, `includeSubDomains`), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and a locked-down `Content-Security-Policy` (`default-src 'none'`).

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
| `accountType` | No | `MANAGED` or `SELF_SERVICE` — see below |
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

Initial values (`initialBusinessName`, `initialBusinessAddress`, `initialNmid`, `initialBankName`, `initialBankNumber`, `initialBankAccountName`) are stored server-side and used by the reset endpoints. The initial values themselves are never returned directly, but each reset endpoint returns the resulting (reverted) current values so the client can repopulate its form without a re-login.

### Account types

| Value | Description |
|---|---|
| `MANAGED` | Created internally by Bejo (seed data or the internal registration API) |
| `SELF_SERVICE` | Self-registered through the public registration API |

### Transaction types

| `type` | `acquirer` | Description |
|---|---|---|
| `MANAGED` | `AstraPay` | Pushed from Bejo server via FCM |
| `MANUAL` | `Cash` | Manually entered from the mobile app |
| `PARSED` | *(from request)* | Received from another acquirer's push notification |

---

## Endpoints

### Auth

> Registration lives under **[Users](#users)** — the Auth endpoints are login/logout only.

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
    "expiresIn": 604800
  }
}
```

**Errors:** `400 Invalid credentials`, `400 username is required`, `400 deviceId is required` (field validation)

---

#### `POST /api/auth/logout`

Blacklists the current JWT server-side. The token is rejected on all subsequent requests, even before its natural expiry.

**Optional body** — pass the device to also clear its stored FCM token (so it stops receiving pushes after logout). The user's *other* devices are untouched. Omitting the body (or `deviceId`) still logs out, just without clearing a token.

```json
{ "deviceId": "device-001" }
```

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
| `page` | integer ≥ 1 | Page number (optional, default `1`) |
| `size` | integer 1–200 | Page size (optional, default `50`) |

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
  "count": 1,
  "page": 1,
  "totalPages": 1,
  "total": 1
}
```

`issuerName`, `merchantPan`, `customerPan`, `rrn` are omitted when null. `count` is the number of rows in this page; `page` is the current (1-based) page, `totalPages` the number of pages, `total` the total matching rows.

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

#### `POST /api/transactions/bulk`

Bulk-creates manually-entered transactions in a single request — built for **offline entry**: the app stores transactions locally while offline, then pushes the whole batch when connectivity returns.

Each item carries its **own** `idempotencyKey` (generated by the client when the record was created offline). Re-sending the same batch — or a partial batch after a dropped connection — is safe: already-stored keys come back as `DUPLICATE`, never re-inserted. Items are processed independently, so one bad row doesn't block the rest (**partial success**).

**Request body**

```json
{
  "transactions": [
    { "idempotencyKey": "off-1", "transaction": { "type": "MANUAL", "transactionDate": "2026-06-28T09:14:00", "amount": 10000 } },
    { "idempotencyKey": "off-2", "transaction": { "type": "PARSED", "transactionDate": "2026-06-28T10:00:00", "amount": 20000, "acquirer": "AstraPay" } }
  ]
}
```

| Field | Required | Description |
|---|---|---|
| `transactions` | yes | 1–500 items |
| `transactions[].idempotencyKey` | yes | Client-generated stable key, max 255 chars |
| `transactions[].transaction` | yes | Same shape as the single `POST /api/transactions` body |

**Success — HTTP 200** — a summary plus a per-item result the client matches back by `idempotencyKey`:

```json
{
  "status": 1,
  "message": "Sinkronisasi transaksi selesai",
  "data": {
    "total": 3,
    "created": 1,
    "duplicate": 1,
    "failed": 1,
    "results": [
      { "idempotencyKey": "off-1", "status": "CREATED", "transactionId": "TXN-A1B2C3D4E5F6" },
      { "idempotencyKey": "off-2", "status": "DUPLICATE", "transactionId": "TXN-99AA88BB77CC" },
      { "idempotencyKey": "off-3", "status": "FAILED", "error": "amount harus lebih dari 0" }
    ]
  }
}
```

| Item `status` | Meaning | Client action |
|---|---|---|
| `CREATED` | Newly stored | Mark local row synced |
| `DUPLICATE` | Key already stored (prior sync) | Mark local row synced |
| `FAILED` | Validation error (see `error`) | Fix and re-sync |

**Errors:** `400 transactions tidak boleh kosong`, `400 maksimal 500 transaksi per batch` (batch-level, reject the whole request). Per-item problems are reported as `FAILED` entries with HTTP 200, not a request-level error.

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

#### `POST /api/notifications/test`

**Test endpoint** — sends an FCM push notification (built from the request payload) to a single registered device, **without** recording anything in the database. Use it to verify push delivery end-to-end. Real payments are recorded (and their pushes sent) by the [QRIS callback](#qris-callback).

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

**Success — HTTP 200:** `{ "status": 1, "message": "Test notification sent" }`

**Errors**

| HTTP | Message | Cause |
|---|---|---|
| 400 | `transactionId wajib diisi` | Missing field |
| 400 | `amount harus lebih dari 0` | Non-positive amount |
| 404 | `Device not found: <deviceId>` | Unknown device |
| 404 | `No FCM token registered for device: <deviceId>` | No FCM token on device |
| 503 | `Firebase is not configured` | Firebase credentials missing |
| 502 | `Failed to send notification: ...` | FCM delivery error |

If FCM reports the token as no longer registered (`502` with `errorCode=UNREGISTERED` — "Requested entity was not found", i.e. the app was uninstalled or the token rotated) or `SENDER_ID_MISMATCH`, the stale token is **automatically cleared** from the device's session, so a subsequent call returns `404 No FCM token registered` until the app re-registers a fresh token.

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

### Users

#### `POST /api/users/register`

Self-service registration. Creates a `SELF_SERVICE` user + bank profile. **No QRIS row is created — QRIS is optional** and can be added later via `POST /api/qris/upload`. `X-API-TOKEN` required, no JWT.

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

#### `POST /api/users/internal/register`

Internal/system registration. Same request body as above, but creates a `MANAGED` account. **Requires JWT** (in addition to `X-API-TOKEN`) — it is not public.

**Responses**

| HTTP | Result |
|---|---|
| 201 | `{ "status": 1, "message": "Registrasi internal berhasil" }` |
| 400 | Validation error / `Username sudah digunakan` |
| 401 | Missing or invalid JWT |

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

Reverts `businessName`, `businessAddress`, and `nmid` to registration values, and returns the reverted values so the client can repopulate the edit form.

**Success — HTTP 200**

```json
{
  "status": 1,
  "message": "Informasi bisnis berhasil direset ke data awal",
  "data": {
    "businessName": "Alice Shop",
    "businessAddress": "Jl. Contoh No. 1",
    "nmid": "ID1026540778978"
  }
}
```

**Errors:** `400 Data bisnis awal tidak tersedia untuk direset` (no initial values stored)

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

Reverts `bankName`, `bankNumber`, and `bankAccountName` to registration values, and returns the reverted values so the client can repopulate the edit form.

**Success — HTTP 200**

```json
{
  "status": 1,
  "message": "Informasi rekening berhasil direset ke data awal",
  "data": {
    "bankName": "Bank BCA",
    "bankNumber": "1234567890",
    "bankAccountName": "Alice"
  }
}
```

**Errors:** `400 Data rekening awal tidak tersedia untuk direset` (no initial values stored)

---

#### `PATCH /api/users/me/password` — Change password

| Field | Required | Validation |
|---|---|---|
| `oldPassword` | yes | Current password |
| `newPassword` | yes | Min 8 chars, at least 1 letter and 1 digit |

**Errors:** `400 Password saat ini tidak sesuai`, `400 Password baru minimal 8 karakter`, `400 Password baru harus mengandung minimal 1 huruf dan 1 angka`

---

#### `GET /api/users/me/profile-picture` — Get profile picture

Returns the user's profile picture as a base64 string. The picture is **optional** — when the user has never set one, `profilePicture` is explicitly **`null`** (no placeholder image).

**Success — HTTP 200**

```json
{
  "status": 1,
  "data": { "profilePicture": "<base64-encoded image>" }
}
```

---

#### `PUT /api/users/me/profile-picture` — Set profile picture

Stores a base64-encoded image as the user's profile picture. The image should be **compressed client-side**; the decoded image must be **≤ 1 MB**. Returns the saved picture.

**Request body:** `{ "profilePicture": "<base64-encoded image>" }`

**Success — HTTP 200:** `{ "status": 1, "message": "Foto profil berhasil disimpan", "data": { "profilePicture": "<base64>" } }`

**Errors:** `400 profilePicture wajib diisi`, `400 profilePicture bukan format base64 yang valid`, `400 profilePicture terlalu besar (maksimal 1MB)`

---

#### `DELETE /api/users/me/profile-picture` — Remove profile picture

Clears the user's profile picture (sets it back to `null`). Idempotent — succeeds whether or not a picture was set.

**Success — HTTP 200:** `{ "status": 1, "message": "Foto profil berhasil dihapus" }`

---

### Products & categories

Basic per-merchant SKU catalog with simple inventory. All endpoints require JWT and are scoped to the authenticated merchant. Categories are an optional grouping; inventory is managed by setting a product's `stock`.

#### `POST /api/categories` — Create category

**Request body:** `{ "name": "Minuman" }` (required, unique per merchant, max 100 chars)

**Success — HTTP 201:** `{ "status": 1, "message": "Kategori berhasil dibuat", "data": { "id": 3, "name": "Minuman", "createdAt": "..." } }`

**Errors:** `400 name wajib diisi`, `400 Kategori sudah ada: <name>`

#### `GET /api/categories` — List categories

Returns the merchant's categories (`data` array + `count`).

#### `DELETE /api/categories/{id}` — Delete category

Deletes the category and **un-categorizes** its products (their `categoryId` becomes null) — products are not deleted.

**Success — HTTP 200:** `{ "status": 1, "message": "Kategori berhasil dihapus" }` · **Errors:** `404 Category not found`

---

#### `POST /api/products` — Create product

**Request body**

| Field | Required | Description |
|---|---|---|
| `sku` | yes | Merchant product code, unique per merchant, max 64 chars |
| `name` | yes | Max 255 chars |
| `shortDescription` | no | Short description, max 255 chars |
| `categoryId` | no | Must be one of the merchant's own categories |
| `price` | no | Selling price in IDR, ≥ 0 (default 0) |
| `costPrice` | no | Purchase cost in IDR, ≥ 0 |
| `stock` | no | Quantity on hand, ≥ 0 (default 0) |
| `photo` | no | Base64 image, **≤ 500 KB decoded** |

**Success — HTTP 201** — returns the product with its resolved `categoryName`:

```json
{
  "status": 1,
  "message": "Produk berhasil dibuat",
  "data": {
    "id": 12, "sku": "KOPI-01", "name": "Kopi Susu", "shortDescription": "Kopi + susu segar",
    "categoryId": 3, "categoryName": "Minuman",
    "price": 18000, "costPrice": 10000, "stock": 25,
    "photo": "<base64>", "createdAt": "...", "updatedAt": "..."
  }
}
```

**Errors:** `400 sku wajib diisi`, `400 SKU sudah digunakan: <sku>`, `400 Kategori tidak ditemukan: <id>`, `400 photo terlalu besar (maksimal 500KB)`, `400 photo bukan format base64 yang valid`

#### `GET /api/products` — List products

Optional `?categoryId=<id>` filter. **The `photo` field is omitted from list items** to keep the payload small — fetch the detail endpoint for the image. Paginated: optional `page` (default `1`) + `size` (default `50`, max `200`); response includes `count` (this page), `page`, `totalPages`, `total`.

#### `GET /api/products/{id}` — Get product

Returns the full product **including `photo`**. `404` if not found / not owned.

#### `PATCH /api/products/{id}` — Update product

Partial update — any subset of `sku`, `name`, `shortDescription`, `categoryId`, `price`, `costPrice`, `stock`, `photo`; a `null`/omitted field is left unchanged. Managing inventory = send `{ "stock": 40 }`. Returns the updated product.

**Errors:** `400 Tidak ada field yang diubah`, `400 SKU sudah digunakan: <sku>`, `400 Kategori tidak ditemukan: <id>`, `404 Product not found`

#### `DELETE /api/products/{id}` — Delete product

Hard-deletes the product. **Success — HTTP 200:** `{ "status": 1, "message": "Produk berhasil dihapus" }`

#### `POST /api/products/stock-adjustments` — Bulk stock adjustment (stock opname)

Sets several products' stock to their **newly counted quantities** at once — for stock opname, when the physical count differs from the recorded stock. **Atomic:** if any line references a product the merchant doesn't own, the whole adjustment is rejected and no stock changes. Returns a **variance report**, and records an `ADJUSTMENT` entry in the [stock-mutation history](#stock-mutations) (with the note, if given).

**Request body**

| Field | Required | Description |
|---|---|---|
| `note` | no | Free text stored on the resulting stock mutation, max 255 chars |
| `items` | yes | 1–500 lines |
| `items[].productId` | yes | One of the merchant's products |
| `items[].stock` | yes | New absolute counted quantity, ≥ 0 |

```json
{
  "note": "opname sore",
  "items": [
    { "productId": 12, "stock": 8 },
    { "productId": 15, "stock": 12 }
  ]
}
```

**Success — HTTP 200** — each product's stock is set, with the variance (`difference = newStock − previousStock`, negative = shrinkage):

```json
{
  "status": 1,
  "message": "Penyesuaian stok berhasil",
  "data": {
    "total": 2,
    "items": [
      { "productId": 12, "sku": "KOPI-01", "name": "Kopi Susu", "previousStock": 10, "newStock": 8,  "difference": -2 },
      { "productId": 15, "sku": "TEH-01",  "name": "Teh Manis",  "previousStock": 5,  "newStock": 12, "difference": 7 }
    ]
  }
}
```

**Errors:** `400 items tidak boleh kosong`, `400 stock tidak boleh negatif`, `400 note maksimal 255 karakter`, `400 Produk tidak ditemukan: <id>`

---

### Stock mutations

The unified **stock-change history**. Every change to a product's stock is recorded as a *mutation* with a `type`:

| `type` | Source | Priced? |
|---|---|---|
| `PURCHASE` | Restock via `POST /api/stock-mutations` | yes |
| `ADJUSTMENT` | Stock opname via `POST /api/products/stock-adjustments` | no |
| `UPDATE` | A `PATCH /api/products/{id}` that changed `stock` | no |

`ADJUSTMENT` and `UPDATE` mutations are created automatically by those product endpoints; this resource is where you **record a purchase** and **read the whole history**. All endpoints require JWT, scoped to the merchant. Each line snapshots the product and the stock before/after (`previousStock` → `newStock`, `quantityChange`).

#### `POST /api/stock-mutations` — Record a purchase (restock)

Buying stock from a supplier: **raises the stock of several products at once** (a `PURCHASE` mutation). **Atomic** — if any line references a product the merchant doesn't own, the whole thing is rejected and no stock changes.

**Request body**

| Field | Required | Description |
|---|---|---|
| `note` | no | Free text, max 255 chars |
| `items` | yes | 1–200 lines |
| `items[].productId` | yes | One of the merchant's products |
| `items[].quantity` | yes | Units added to stock, > 0 |
| `items[].unitPrice` | no | Cost per unit, IDR ≥ 0. Omit to use the product's `costPrice` (0 if it has none) |

```json
{
  "note": "restock pagi",
  "items": [
    { "productId": 12, "quantity": 20 },
    { "productId": 15, "quantity": 5, "unitPrice": 9000 }
  ]
}
```

**Success — HTTP 201** — the saved mutation with line snapshots; each product's `stock` has been raised by its quantity:

```json
{
  "status": 1,
  "message": "Pembelian berhasil dicatat",
  "data": {
    "id": 7, "type": "PURCHASE", "totalAmount": 145000, "note": "restock pagi", "createdAt": "...",
    "items": [
      { "productId": 12, "sku": "KOPI-01", "name": "Kopi Susu", "previousStock": 10, "newStock": 30, "quantityChange": 20, "unitPrice": 5000, "subtotal": 100000 },
      { "productId": 15, "sku": "TEH-01",  "name": "Teh Manis",  "previousStock": 3,  "newStock": 8,  "quantityChange": 5,  "unitPrice": 9000, "subtotal": 45000 }
    ]
  }
}
```

**Errors:** `400 items tidak boleh kosong`, `400 quantity harus lebih dari 0`, `400 Produk tidak ditemukan: <id>`

#### `GET /api/stock-mutations` — Stock-change history

Returns mutations (PURCHASE/ADJUSTMENT/UPDATE) newest-first, each **with its line items** (product `sku`/`name` + `previousStock`/`newStock`/`quantityChange`). For non-purchase mutations `unitPrice`/`subtotal` are absent and `totalAmount` is 0. Paginated: optional `page` (default `1`) + `size` (default `50`, max `200`); response includes `count` (this page), `page`, `totalPages`, `total`.

#### `GET /api/stock-mutations/{id}` — Stock mutation detail

Returns one mutation **including its `items`** (each with `previousStock`/`newStock`/`quantityChange`). `404` if not found / not owned.

---

### App settings

Per-user settings synced from the app: the **monitored apps** (which apps' notifications to watch), the **notification keywords** (blacklist/whitelist filters), and app **config toggles**. All endpoints require JWT. Settings are created on first save; a user who has never synced gets sensible defaults.

#### `GET /api/app-settings` — Get app settings

Returns the user's settings. When nothing has been synced yet, returns defaults: empty `monitoredApps`/keyword lists, `blacklistEnabled: true`, `whitelistEnabled: false`, both config toggles `false`. Empty lists mean the client should fall back to its own bundled defaults.

**Success — HTTP 200**

```json
{
  "status": 1,
  "message": "App settings retrieved",
  "data": {
    "monitoredApps": { "id.dana": "dana", "com.gojek.app": "gopay" },
    "includeKeywords": ["uang masuk", "pembayaran diterima"],
    "excludeKeywords": ["promo", "gagal"],
    "blacklistEnabled": true,
    "whitelistEnabled": false,
    "qrisWithProfilePicture": false,
    "inventoryEnabled": false
  }
}
```

| Config field | Default | Meaning |
|---|---|---|
| `qrisWithProfilePicture` | `false` | Whether the QRIS image should be composed onto the profile picture |
| `inventoryEnabled` | `false` | Whether inventory management is enabled |

---

#### `PUT /api/app-settings/monitored-apps` — Save monitored apps

Replaces the monitored-apps map (`packageName` → `issuer`). Sending `{}` clears the list. Package names must match `[A-Za-z0-9_.]+`; issuers are trimmed and lowercased. Returns the full updated settings.

**Request body**

```json
{
  "apps": {
    "id.dana": "dana",
    "com.gojek.app": "gopay"
  }
}
```

**Success — HTTP 200:** `{ "status": 1, "message": "Daftar aplikasi dipantau berhasil disimpan", "data": { ...full settings... } }`

**Errors:** `400 apps wajib diisi`, `400 Nama package tidak valid: <pkg>`, `400 Nama issuer tidak boleh kosong untuk package <pkg>`, `400 Terlalu banyak aplikasi (maksimal 500)`

---

#### `PUT /api/app-settings/keywords` — Save notification keywords

Updates the keyword filters. **Partial update:** a `null` field is left unchanged; an empty list (`[]`) clears that list. Keywords are trimmed, lowercased, and deduplicated. Returns the full updated settings.

**Request body** (any subset of fields)

| Field | Type | Description |
|---|---|---|
| `includeKeywords` | array | Whitelist keywords (money-in indicators) |
| `excludeKeywords` | array | Blacklist keywords (promos, failures, outgoing) |
| `blacklistEnabled` | boolean | Enable exclude-keyword filtering |
| `whitelistEnabled` | boolean | Enable include-keyword filtering |

```json
{
  "excludeKeywords": ["promo", "diskon"],
  "whitelistEnabled": true
}
```

**Success — HTTP 200:** `{ "status": 1, "message": "Kata kunci notifikasi berhasil disimpan", "data": { ...full settings... } }`

---

#### `PUT /api/app-settings/qris-with-profile-picture` — Toggle QRIS on profile picture

**Request body:** `{ "enabled": true }` (required boolean). Returns the full updated settings.

**Success — HTTP 200:** `{ "status": 1, "message": "Pengaturan QRIS pada foto profil berhasil disimpan", "data": { ...full settings... } }` · **Errors:** `400 enabled wajib diisi`

---

#### `PUT /api/app-settings/inventory` — Toggle inventory management

**Request body:** `{ "enabled": true }` (required boolean). Returns the full updated settings.

**Success — HTTP 200:** `{ "status": 1, "message": "Pengaturan manajemen inventaris berhasil disimpan", "data": { ...full settings... } }` · **Errors:** `400 enabled wajib diisi`

**Errors:** `400 Tidak ada field yang diubah` (all fields null), `400 <field>: kata kunci terlalu panjang: <kw>`, `400 <field>: terlalu banyak kata kunci (maksimal 1000)`

---

### QRIS

#### `GET /api/qris` — Get QRIS image

Returns the QRIS image and business info. `type` indicates whether the image is Bejo-managed or user-uploaded.

**QRIS is optional.** A freshly registered merchant has no QRIS record at all, so `GET /api/qris` returns **`404 QRIS data not found`** until they upload one via `POST /api/qris/upload` (which creates the record on first upload). Once a record exists, `qrisImage` **may still be `null`** — e.g. after an upload is reset, the response is `type: MANAGED` with `qrisImage: null`. The server does **not** synthesize a placeholder; the client renders an empty state and prompts the merchant to upload their QRIS.

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
| `MANAGED` | The account's initial/managed image, or `null` if none has been set |
| `UPLOADED` | User-uploaded image (overrides MANAGED) |

---

#### `POST /api/qris/upload` — Upload custom QRIS image

Once uploaded, `GET /api/qris` returns this image with `type: UPLOADED`.

**Request body:** `{ "qrisImage": "<base64-encoded image>" }` — max ±5 MB

**Success — HTTP 200:** `{ "status": 1, "message": "QRIS berhasil diupload" }`

**Errors:** `400 qrisImage wajib diisi`, `400 qrisImage bukan format base64 yang valid`, `400 qrisImage terlalu besar (maksimal ±5MB)`

---

#### `POST /api/qris/reset` — Reset QRIS image

Clears the uploaded image and reverts to the initial/managed QRIS image (which may be `null` → empty state on the client). No placeholder is generated.

**Success — HTTP 200:** `{ "status": 1, "message": "QRIS berhasil direset ke versi generated" }`

---

### QRIS callback

#### `POST /snap/v1.0/qr/qr-mpm-notify`

Inbound QRIS payment notification (SNAP-like QR MPM notify) from the QRIS merchant service (e.g. AstraPay). **Server-to-server — no `X-API-TOKEN` and no JWT.** Records the payment as a `MANAGED` transaction and stores the **raw payload** in the transaction's `additionalInfo` column.

This endpoint does **not** use the standard `{ status, message, data }` envelope — it returns the callback contract's own acknowledgment shape.

> **⚠️ PoC mode (temporary).** The provider's callback is currently buggy and only sends a valid amount + status (reference numbers, `merchantPan`, `transactionId` all null). So right now the endpoint is **lenient**: all body fields and the `X-TIMESTAMP` header are optional, and it **cannot** route by NMID or dedupe by reference. Instead, **every callback records a new transaction against the seed user** (`app.callback.default-username`) and pushes to *its* devices. NMID routing + idempotency will be restored once the provider fixes the payload.

**Headers**

| Header | Mandatory | Description |
|---|---|---|
| `Content-Type: application/json` | ✅ | |
| `X-TIMESTAMP` | optional | ISO-8601 transaction time (offset / `Z` / none). Recorded as the transaction date; missing or unparseable falls back to now. |

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

All fields are optional in PoC mode; only `amount.value` (when present) and `latestTransactionStatus` are used.

**Behavior (PoC)**

- **Attribution** — every callback is recorded against the seed user (`app.callback.default-username`); if that user doesn't exist, returns `404`.
- **No idempotency** — there's no reference to dedupe on, so each callback creates a new transaction (generated `transactionId`, null idempotency key).
- **Status mapping** — `00`→SUCCESS, `01`→PENDING, `02`→FAILED, `03`→REFUNDED, `04`→CANCELLED, `05`→EXPIRED, else/absent UNKNOWN.
- **Amount** — parsed from `amount.value`; missing/invalid → `0` (never fails the callback).
- **Records a `MANAGED` transaction** and saves the full payload JSON to its `additionalInfo`.
- **Sends an FCM push** to all of the seed user's registered devices (best-effort — a push failure never fails the callback; skipped if Firebase isn't configured).

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

`status` is the **acknowledgment** status (processing succeeded) — always `SUCCESS` on `200`, independent of the payment's own status, which is derived from `latestTransactionStatus` and stored on the transaction.

**Errors:** `404` seed user (`app.callback.default-username`) not found

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

**Request logging**

Every request is logged as `METHOD PATH[?query] STATUS DURATIONms`. Requests that fail with a `4xx` also log the reason (`[400] METHOD /path — <reason>`) and, on any `4xx`/`5xx`, the request body (`[BODY] …`). Body logging is production-safe: secrets (`password`/`token`/`pushToken`) are masked, the body is single-line and capped at 2 KB, and auth endpoints (`/api/auth/**`, register) are never captured. Under `SPRING_PROFILES_ACTIVE=prod` all logs are emitted as JSON (one object per line) for Cloud Logging.

The QRIS payment callback (`/snap/v1.0/qr/qr-mpm-notify`) is logged more verbosely to aid debugging: on any `4xx`/`5xx` it dumps `[CALLBACK-REQ]` with **all request headers** (credential headers masked) and the **full body** (capped 8 KB), captured even when the request is rejected before the body is read. This fuller logging is intentionally scoped to that one endpoint.

---

## Configuration

### App environment variables

Used locally via `.env` (Docker Compose) or exported directly (Maven).

| Variable | Required | Description |
|---|---|---|
| `API_TOKEN` | yes | Shared secret for `X-API-TOKEN` header |
| `JWT_SECRET` | yes | Base64-encoded signing key, min 256 bits — `openssl rand -base64 32` |
| `JWT_EXPIRATION_MS` | no | Token TTL in ms. Default `604800000` (7 days); `0` disables expiry |
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
| `APP_REQUESTLOGGING_LOGBODYONERROR` | no | Log request bodies on 4xx/5xx (redacted, capped). Default `true`; set `false` to disable |
| `APP_REQUESTLOGGING_MAXBODYLENGTH` | no | Max logged body length in chars. Default `2048` |

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
