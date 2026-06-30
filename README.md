# Bejo API

REST API built with Java 25 and Spring Boot. Provides user registration and authentication, transaction management, FCM push notification delivery, QRIS code management, and business profile management.

## Stack

- **Java 25** + **Spring Boot 3.5**
- **PostgreSQL 17** — persistent storage
- **Firebase Admin SDK** — FCM push notifications
- **JWT (JJWT)** — stateless authentication, no expiry
- **Prometheus** — metrics scraping via `/actuator/prometheus`
- **Timezone** — all datetimes are UTC+7 (Asia/Jakarta)

---

## Running

### Docker Compose (recommended)

```bash
cp .env.example .env
# Edit .env — set API_TOKEN, JWT_SECRET, seed user info, and Firebase credentials
docker compose up --build -d
```

The API is available at `http://localhost:8080`.

### Local development

Requires a PostgreSQL instance. Run Flyway migrations (applied automatically on startup), then:

```bash
export API_TOKEN=dev-token
export JWT_SECRET=YmVqby1kZXYtc2VjcmV0LWtleS1tdXN0LWJlLWNoYW5nZWQtaW4tcHJvZHVjdGlvbi1taW4tMjU2Yml0cw==
export DB_URL=jdbc:postgresql://localhost:5432/bejo
export DB_USERNAME=bejo
export DB_PASSWORD=bejo
mvn spring-boot:run
```

---

## Authentication

Every request must include two headers:

| Header | Required on | Description |
|---|---|---|
| `X-API-TOKEN` | All endpoints | Static shared secret matching `API_TOKEN` in `.env` |
| `Authorization: Bearer <token>` | All protected endpoints | JWT returned by the login endpoint |

Public endpoints (X-API-TOKEN required, no JWT): `/api/auth/login`, `/api/auth/register`.

Exempt from all checks: `/actuator/**`.

**Missing or invalid `X-API-TOKEN`:**
```json
{ "status": 0, "message": "Missing or invalid X-API-TOKEN" }
```

**Missing or invalid JWT:**
```json
{ "status": 0, "message": "Unauthorized — include Authorization: Bearer <token> header" }
```

---

## Response envelope

All responses use a consistent envelope:

```json
{
  "status": 1,
  "message": "optional message string",
  "data": {},
  "count": 0
}
```

- `status` — `1` for success, `0` for error
- `message` — present on success actions and all errors
- `data` — present on success; omitted on errors
- `count` — present on list responses only

Null fields are omitted from the response.

---

## User model

| Field | Editable | Description |
|---|---|---|
| `username` | No | Login identifier, alphanumeric + underscore, 3–50 chars |
| `accountType` | No | `MANAGED` (Bejo-managed QRIS) or `EXTERNAL` (own acquirer) |
| `name` | Yes | Display name |
| `email` | Yes | Email address |
| `isEmailVerified` | System | Always `false` until verification is implemented |
| `phone` | Yes | Phone in international format e.g. `+628123456789` |
| `isPhoneVerified` | System | Always `false` until verification is implemented |
| `businessName` | Yes | Current display business name |
| `businessAddress` | Yes | Current display business address |
| `nmid` | Yes | National Merchant ID (NMID) from QRIS registration |
| `bankName` | Yes | Bank name |
| `bankNumber` | Yes | Bank account number (digits only) |
| `bankAccountName` | Yes | Bank account holder name |

The `initialBusinessName`, `initialBusinessAddress`, `initialNmid`, `initialBankName`, `initialBankNumber`, and `initialBankAccountName` fields are stored server-side as regulatory records set at registration and are used by the reset endpoints. They are not returned in API responses.

---

## Account types

| Value | Description |
|---|---|
| `MANAGED` | QRIS managed by Bejo — full service including push notification and ring notification |
| `EXTERNAL` | QRIS on another acquirer — Bejo only stores and tracks transactions |

---

## Transaction types

| `type` | `acquirer` | Description |
|---|---|---|
| `MANAGED` | `AstraPay` | Pushed from Bejo server via FCM |
| `MANUAL` | `Cash` | Manually entered by the user from the mobile app |
| `PARSED` | *(from request)* | Received from another acquirer's push notification |

---

## Endpoints

### POST /api/auth/register

Creates a new user account. Public endpoint — X-API-TOKEN required, no JWT.

**Request body**

| Field | Type | Required | Validation |
|---|---|---|---|
| `username` | string | yes | 3–50 chars, alphanumeric + underscore, unique |
| `password` | string | yes | Min 8 chars, at least 1 letter and 1 digit |
| `name` | string | yes | 2–100 chars |
| `email` | string | yes | Valid email format |
| `phone` | string | yes | International format e.g. `+628123456789` |
| `businessName` | string | yes | Max 255 chars |
| `businessAddress` | string | no | Free text |
| `nmid` | string | no | National Merchant ID, max 50 chars |
| `bankName` | string | no | Max 100 chars |
| `bankNumber` | string | no | Digits only, 6–20 digits |
| `bankAccountName` | string | no | Max 100 chars |

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

**Success response — HTTP 201**

```json
{ "status": 1, "message": "Registrasi berhasil" }
```

**Error responses**

| HTTP | `message` | Cause |
|---|---|---|
| 400 | `username wajib diisi` | Missing field |
| 400 | `username hanya boleh mengandung huruf, angka, dan underscore` | Invalid characters |
| 400 | `password harus mengandung minimal 1 huruf dan 1 angka` | Weak password |
| 400 | `format email tidak valid` | Invalid email |
| 400 | `format nomor telepon tidak valid` | Invalid phone format |
| 400 | `businessName wajib diisi` | Missing business name |
| 409 | `Username sudah digunakan` | Duplicate username |

---

### POST /api/auth/login

Authenticates a user and returns a permanent JWT. Also registers or updates the device's FCM push token.

**Request body**

| Field | Type | Required | Description |
|---|---|---|---|
| `username` | string | yes | |
| `password` | string | yes | |
| `deviceId` | string | yes | Unique identifier for the calling device |
| `pushToken` | string | no | FCM token for push notifications |

**Success response — HTTP 200**

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
    "tokenType": "Bearer"
  }
}
```

**Error responses**

| HTTP | `message` | Cause |
|---|---|---|
| 400 | `username is required` | Missing field |
| 400 | `deviceId is required` | Missing field |
| 400 | `Invalid credentials` | Wrong username or password |

---

### POST /api/auth/logout

Invalidates the current JWT by adding it to a server-side blacklist.

**Success response — HTTP 200**

```json
{ "status": 1, "message": "Logged out successfully" }
```

---

### GET /api/transactions

Returns the transaction history for the authenticated user, ordered by date descending.

**Query parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `dateStart` | `yyyy-MM-dd` | no | Filter from this date (inclusive) |
| `dateEnd` | `yyyy-MM-dd` | no | Filter to this date (inclusive) |

**Success response — HTTP 200**

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
      "merchantName": "Alice Shop",
      "merchantPan": "936009XXXXXX0001",
      "customerPan": "401177XXXXXX5678",
      "rrn": "000100000002"
    }
  ],
  "count": 1
}
```

`issuerName`, `merchantPan`, `customerPan`, and `rrn` are omitted when null.

---

### POST /api/transactions

Adds a new transaction manually or from an external acquirer's notification. The server generates `transactionId`.

**Headers**

| Header | Required | Description |
|---|---|---|
| `Idempotency-Key` | yes | Client-generated UUID per request attempt. Resubmitting the same key returns the previously created `transactionId` without inserting a duplicate. |

**Request body**

| Field | Type | Required | Description |
|---|---|---|---|
| `type` | string | yes | `MANUAL` or `PARSED` |
| `transactionDate` | datetime | yes | ISO 8601 e.g. `2026-06-28T09:14:00` |
| `amount` | integer | yes | Amount in smallest currency unit, must be > 0 |
| `issuerName` | string | no | Payment issuer name, max 255 chars |
| `merchantPan` | string | no | Max 50 chars |
| `customerPan` | string | no | Max 50 chars |
| `rrn` | string | no | Max 255 chars |
| `acquirer` | string | PARSED only | Name of the acquirer; required when `type` is `PARSED` |

```json
{
  "type": "PARSED",
  "transactionDate": "2026-06-28T09:14:00",
  "amount": 50000,
  "issuerName": "GoPay",
  "acquirer": "Midtrans",
  "rrn": "000100000099"
}
```

**Success response — HTTP 201**

```json
{
  "status": 1,
  "message": "Transaksi berhasil ditambahkan",
  "data": { "transactionId": "TXN-A1B2C3D4E5F6" }
}
```

**Error responses**

| HTTP | `message` | Cause |
|---|---|---|
| 400 | `Idempotency-Key header wajib diisi` | Missing `Idempotency-Key` header |
| 400 | `type harus MANUAL atau PARSED` | Invalid type value |
| 400 | `acquirer wajib diisi untuk type PARSED` | Missing acquirer for PARSED |
| 400 | `amount harus lebih dari 0` | Non-positive amount |

---

### POST /api/notifications

Sends an FCM push notification to a registered device **and** inserts a `MANAGED` transaction record. All validation runs before the notification is sent — if any check fails, neither the notification nor the transaction insertion occurs.

**Request body**

| Field | Type | Required | Description |
|---|---|---|---|
| `deviceId` | string | yes | Target device |
| `title` | string | yes | Notification title |
| `body` | string | yes | Notification body text |
| `data.transactionId` | string | yes | Unique transaction ID, max 255 chars |
| `data.transactionDate` | datetime | yes | ISO 8601 e.g. `2026-06-28T09:14:00` |
| `data.amount` | integer | yes | Amount > 0 |
| `data.issuerName` | string | yes | Payment issuer name, max 255 chars |
| `data.merchantPan` | string | no | Max 50 chars |
| `data.customerPan` | string | no | Max 50 chars |
| `data.rrn` | string | no | Max 255 chars |

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

**Success response — HTTP 200**

```json
{ "status": 1, "message": "Notification sent" }
```

**Error responses**

| HTTP | `message` | Cause |
|---|---|---|
| 400 | `transactionId wajib diisi` | Missing field |
| 400 | `amount harus lebih dari 0` | Non-positive amount |
| 400 | `transactionId sudah terdaftar: <id>` | Duplicate transaction ID |
| 404 | `Device not found: <deviceId>` | Unknown device |
| 404 | `No FCM token registered for device: <deviceId>` | Device has no FCM token |
| 503 | `Firebase is not configured` | Firebase credentials not set |
| 502 | `Failed to send notification: ...` | FCM delivery error |

---

### PUT /api/devices/{deviceId}/fcm-token

Updates the Firebase Cloud Messaging token for a device.

**Path parameter** — `deviceId`: the device to update

**Request body**

| Field | Type | Required |
|---|---|---|
| `pushToken` | string | yes |

**Success response — HTTP 200**

```json
{
  "status": 1,
  "message": "Push Token data is updated",
  "data": { "deviceId": "device-001", "pushToken": "new-fcm-token" }
}
```

---

### PATCH /api/users/me

Updates the authenticated user's personal info. All fields are required.

**Request body**

| Field | Type | Required | Validation |
|---|---|---|---|
| `name` | string | yes | 2–100 chars |
| `email` | string | yes | Valid email format |
| `phone` | string | yes | International format e.g. `+628123456789` |

**Success response — HTTP 200**

```json
{ "status": 1, "message": "Profile updated successfully" }
```

---

### PATCH /api/users/me/business

Updates the authenticated user's business display info. Does not affect the regulatory initial values.

**Request body**

| Field | Type | Required | Validation |
|---|---|---|---|
| `businessName` | string | yes | Max 255 chars |
| `businessAddress` | string | no | Free text |
| `nmid` | string | no | Max 50 chars |

**Success response — HTTP 200**

```json
{ "status": 1, "message": "Informasi bisnis berhasil diperbarui" }
```

---

### POST /api/users/me/business/reset

Reverts `businessName`, `businessAddress`, and `nmid` to the values recorded at registration.

**Success response — HTTP 200**

```json
{ "status": 1, "message": "Informasi bisnis berhasil direset ke data awal" }
```

---

### PATCH /api/users/me/bank

Updates the authenticated user's bank account information. Does not affect the regulatory initial values.

**Request body**

| Field | Type | Required | Validation |
|---|---|---|---|
| `bankName` | string | yes | Max 100 chars |
| `bankNumber` | string | yes | Digits only, 6–20 digits |
| `bankAccountName` | string | yes | 2–100 chars |

**Success response — HTTP 200**

```json
{ "status": 1, "message": "Informasi rekening berhasil diperbarui" }
```

---

### POST /api/users/me/bank/reset

Reverts `bankName`, `bankNumber`, and `bankAccountName` to the values recorded at registration.

**Success response — HTTP 200**

```json
{ "status": 1, "message": "Informasi rekening berhasil direset ke data awal" }
```

---

### PATCH /api/users/me/password

Changes the authenticated user's password.

**Request body**

| Field | Type | Required | Validation |
|---|---|---|---|
| `oldPassword` | string | yes | Current password |
| `newPassword` | string | yes | Min 8 chars, at least 1 letter and 1 digit |

**Error responses**

| HTTP | `message` | Cause |
|---|---|---|
| 400 | `Password baru minimal 8 karakter` | New password too short |
| 400 | `Password baru harus mengandung minimal 1 huruf dan 1 angka` | Weak new password |
| 400 | `Password saat ini tidak sesuai` | Wrong current password |

---

### GET /api/qris

Returns the QRIS image (base64) and business info for the authenticated user. If no image has been generated yet, one is created and stored on the first call.

**Success response — HTTP 200**

```json
{
  "status": 1,
  "data": {
    "type": "MANAGED",
    "qrisImage": "<base64-encoded PNG>",
    "businessName": "Alice Shop",
    "businessAddress": "Jl. Contoh No. 1",
    "nmid": "ID1026540778978"
  }
}
```

**`type` values:**

| Value | Description |
|---|---|
| `MANAGED` | Bejo-generated QRIS image |
| `UPLOADED` | User-uploaded QRIS image (overrides MANAGED) |

---

### POST /api/qris/upload

Uploads a custom QRIS image. Once uploaded, `GET /api/qris` returns this image instead of the generated one.

**Request body**

| Field | Type | Required | Description |
|---|---|---|---|
| `qrisImage` | string | yes | Base64-encoded image |

**Success response — HTTP 200**

```json
{ "status": 1, "message": "QRIS berhasil diupload" }
```

**Error responses**

| HTTP | `message` | Cause |
|---|---|---|
| 400 | `qrisImage wajib diisi` | Missing or blank field |
| 400 | `qrisImage bukan format base64 yang valid` | Invalid base64 |

---

### POST /api/qris/reset

Clears the uploaded QRIS image and reverts to the Bejo-generated one.

**Success response — HTTP 200**

```json
{ "status": 1, "message": "QRIS berhasil direset ke versi generated" }
```

---

## Common error responses

| HTTP | `message` | Cause |
|---|---|---|
| 400 | `Request body tidak valid atau tidak ditemukan` | Malformed or missing JSON body |
| 400 | `Format parameter tidak valid: <param>` | Invalid query parameter type |
| 401 | `Missing or invalid X-API-TOKEN` | Wrong or absent API token |
| 401 | `Unauthorized — include Authorization: Bearer <token> header` | Missing or invalid JWT |
| 404 | `Route not found` | Unknown path |
| 405 | `Method not allowed: <METHOD>` | Wrong HTTP method |
| 409 | `Data tidak valid atau sudah terdaftar` | Database constraint violation |
| 500 | `Internal server error` | Unexpected server error |

---

## Observability

| Endpoint | Description |
|---|---|
| `GET /actuator/health` | Application health |
| `GET /actuator/metrics` | Micrometer metrics |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |

Prometheus is available at `http://localhost:9090` when running via Docker Compose.

**Custom metrics**

| Metric | Tags | Description |
|---|---|---|
| `login.attempts_total` | `result=success\|failure` | Login attempt outcomes |
| `transactions.fetched_total` | — | Total transaction records returned |
| `notifications.sent_total` | `result=success\|failure` | FCM notification outcomes |

---

## Environment variables

| Variable | Required | Description |
|---|---|---|
| `API_TOKEN` | yes | Shared secret for `X-API-TOKEN` header |
| `JWT_SECRET` | yes | Base64-encoded signing key (min 256 bits). Generate: `openssl rand -base64 32` |
| `DB_URL` | yes | JDBC URL e.g. `jdbc:postgresql://localhost:5432/bejo` |
| `DB_USERNAME` | yes | PostgreSQL username |
| `DB_PASSWORD` | yes | PostgreSQL password |
| `SEED_USERNAME` | no | Default user username. Default: `admin` |
| `SEED_PASSWORD` | no | Default user password. Seeding skipped if blank |
| `SEED_NAME` | no | Default user display name. Default: `Admin` |
| `SEED_BUSINESS_NAME` | no | Default user business name. Default: `My Business` |
| `SEED_BUSINESS_ADDRESS` | no | Default user business address |
| `SEED_NMID` | no | Default user National Merchant ID (NMID) |
| `FIREBASE_CREDENTIALS_BASE64` | no* | Base64-encoded Firebase service account JSON |
| `FIREBASE_CREDENTIALS_PATH` | no* | Path to Firebase service account JSON file |

\* One of the two Firebase options is required to use the notifications endpoint.
