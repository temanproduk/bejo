# Bejo API

REST API built with Java 25 and Spring Boot. Provides user authentication, transaction history, FCM device token management, push notification delivery, and QRIS code display.

## Stack

- **Java 25** + **Spring Boot 3.5**
- **PostgreSQL 17** — persistent storage
- **Firebase Admin SDK** — FCM push notifications
- **JWT (JJWT)** — stateless authentication
- **Prometheus** — metrics scraping via `/actuator/prometheus`

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

Requires a PostgreSQL instance. Apply `docker/init.sql` to create the schema, then:

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
| `Authorization: Bearer <token>` | All endpoints except login | JWT returned by the login endpoint |

Exempt from both checks: `/actuator/**` and `GET /api/qris/image/**`.

**Missing or invalid `X-API-TOKEN`:**
```json
HTTP 401
{ "status": 0, "message": "Missing or invalid X-API-TOKEN" }
```

**Missing or invalid JWT:**
```json
HTTP 401
{ "status": 0, "message": "Unauthorized — include Authorization: Bearer <token> header" }
```

---

## Response envelope

All responses use a consistent envelope:

```json
{
  "status": 1,
  "message": "optional message string",
  "data": { },
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
| `username` | No | Login identifier |
| `name` | Yes | Display name |
| `businessName` | No | Set once at account creation |
| `businessAddress` | No | Set once at account creation |
| `email` | Yes | |
| `phone` | Yes | |

---

## Creating users

Users are not self-registered. Insert directly into the database with a BCrypt-hashed password:

```sql
INSERT INTO users (username, password_hash, name, business_name, business_address, qris_code)
VALUES ('alice', '$2a$10$...', 'Alice', 'Alice Shop', 'Jl. Contoh No. 1', gen_random_uuid()::text);
```

Generate a hash:

```java
System.out.println(new BCryptPasswordEncoder().encode("yourpassword"));
```

Or set `SEED_USERNAME`, `SEED_PASSWORD`, `SEED_NAME`, `SEED_BUSINESS_NAME`, and `SEED_BUSINESS_ADDRESS` in `.env` — the app creates the user on first startup.

---

## Endpoints

### POST /api/auth/login

Authenticates a user and returns a JWT. Also registers or updates the device's FCM push token.

**Headers**

```
X-API-TOKEN: <token>
Content-Type: application/json
```

**Request body**

| Field | Type | Required | Description |
|---|---|---|---|
| `username` | string | yes | |
| `password` | string | yes | |
| `deviceId` | string | yes | Unique identifier for the calling device |
| `pushToken` | string | no | FCM token for push notifications |

```json
{
  "username": "alice",
  "password": "Secret1",
  "deviceId": "device-001",
  "pushToken": "fcm-token-here"
}
```

**Success response — HTTP 200**

| Field | Type | Description |
|---|---|---|
| `username` | string | |
| `name` | string | Display name |
| `businessName` | string | |
| `businessAddress` | string | |
| `token` | string | JWT for subsequent requests |
| `tokenType` | string | Always `"Bearer"` |
| `expiresIn` | number | Token lifetime in seconds (default 86400) |

```json
{
  "status": 1,
  "message": "session data is created",
  "data": {
    "username": "alice",
    "name": "Alice",
    "businessName": "Alice Shop",
    "businessAddress": "Jl. Contoh No. 1",
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400
  }
}
```

**Error responses**

| HTTP | `message` | Cause |
|---|---|---|
| 400 | `username is required` | Missing required field |
| 400 | `Invalid credentials` | Wrong username or password |

---

### GET /api/transactions

Returns the transaction history for the authenticated user, ordered by date descending.

**Headers**

```
X-API-TOKEN: <token>
Authorization: Bearer <jwt>
```

**Success response — HTTP 200**

```json
{
  "status": 1,
  "data": [
    {
      "transactionId": "TRX-001",
      "transactionDate": "2024-08-01T10:30:00",
      "amount": 50000.00,
      "status": "SUCCESS",
      "invoiceId": "INV-001",
      "settlementDate": "2024-08-01T11:00:00",
      "source": "BNI",
      "customerName": "Budi Santoso",
      "rrn": "123456789012",
      "info": "",
      "username": "alice",
      "scrapeDate": "2024-08-01T10:35:00"
    }
  ],
  "count": 1
}
```

---

### PUT /api/devices/{deviceId}/fcm-token

Updates the Firebase Cloud Messaging token for a device.

**Headers**

```
X-API-TOKEN: <token>
Authorization: Bearer <jwt>
Content-Type: application/json
```

**Path parameter**

| Parameter | Description |
|---|---|
| `deviceId` | The device to update |

**Request body**

| Field | Type | Required | Description |
|---|---|---|---|
| `pushToken` | string | yes | New FCM token |

```json
{ "pushToken": "new-fcm-token-here" }
```

**Success response — HTTP 200**

```json
{
  "status": 1,
  "message": "Push Token data is updated",
  "data": {
    "deviceId": "device-001",
    "pushToken": "new-fcm-token-here"
  }
}
```

**Error responses**

| HTTP | `message` | Cause |
|---|---|---|
| 400 | `pushToken is required` | Empty body |
| 404 | `Device ID not found: device-001` | Unknown device |

---

### POST /api/notifications

Sends an FCM push notification to a registered device. Requires Firebase credentials to be configured.

**Headers**

```
X-API-TOKEN: <token>
Authorization: Bearer <jwt>
Content-Type: application/json
```

**Request body**

| Field | Type | Required | Description |
|---|---|---|---|
| `deviceId` | string | yes | Target device |
| `title` | string | yes | Notification title |
| `body` | string | yes | Notification body text |
| `data` | object | no | Arbitrary key-value pairs delivered as data payload |

```json
{
  "deviceId": "device-001",
  "title": "Uang masuk Rp 50.000",
  "body": "Ada transaksi masuk sejumlah Rp 50.000",
  "data": {
    "transactionId": "TRX-001",
    "amount": "50000"
  }
}
```

**Success response — HTTP 200**

```json
{
  "status": 1,
  "message": "Notification sent"
}
```

**Error responses**

| HTTP | `message` | Cause |
|---|---|---|
| 400 | `deviceId is required` | Missing field |
| 404 | `Device not found: device-001` | Unknown device |
| 404 | `No FCM token registered for device: device-001` | Device exists but has no push token |
| 503 | `Firebase is not configured` | Firebase credentials not set |
| 502 | `Failed to send notification: ...` | FCM delivery error |

---

### PATCH /api/users/me

Updates the authenticated user's name, email, and/or phone number. All fields are optional — include only what you want to change. At least one field must be provided.

Business name and business address are not editable via this endpoint.

**Headers**

```
X-API-TOKEN: <token>
Authorization: Bearer <jwt>
Content-Type: application/json
```

**Request body**

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | no | Display name |
| `email` | string | no | Email address |
| `phone` | string | no | Phone number |

```json
{
  "name": "Alice Wonderland",
  "email": "alice@example.com",
  "phone": "+62812345678"
}
```

**Success response — HTTP 200**

```json
{
  "status": 1,
  "message": "Profile updated successfully"
}
```

**Error responses**

| HTTP | `message` | Cause |
|---|---|---|
| 400 | `At least one field (name, email, phone) must be provided` | Empty request body |

---

### PATCH /api/users/me/password

Changes the authenticated user's password. Requires the current password for verification.

**Headers**

```
X-API-TOKEN: <token>
Authorization: Bearer <jwt>
Content-Type: application/json
```

**Request body**

| Field | Type | Required | Description |
|---|---|---|---|
| `oldPassword` | string | yes | Current password |
| `newPassword` | string | yes | Must be ≥ 8 characters, contain at least 1 letter and 1 number |

```json
{
  "oldPassword": "Secret1",
  "newPassword": "NewSecret2"
}
```

**Success response — HTTP 200**

```json
{
  "status": 1,
  "message": "Password changed successfully"
}
```

**Error responses**

| HTTP | `message` | Cause |
|---|---|---|
| 400 | `oldPassword is required` | Missing field |
| 400 | `newPassword must be at least 8 characters` | Password too short |
| 400 | `newPassword must contain at least 1 letter and 1 number` | Password format invalid |
| 400 | `Current password is incorrect` | `oldPassword` does not match |

---

### GET /api/qris

Returns the QRIS code image URL and business info for the authenticated user.

**Headers**

```
X-API-TOKEN: <token>
Authorization: Bearer <jwt>
```

**Success response — HTTP 200**

```json
{
  "status": 1,
  "data": {
    "imageUrl": "http://localhost:8080/api/qris/image/a1b2c3d4-....png",
    "businessName": "Alice Shop",
    "businessAddress": "Jl. Contoh No. 1"
  }
}
```

---

### GET /api/qris/image/{qrisCode}.png

Returns the QRIS placeholder image as a PNG file. This endpoint is **public** — no `X-API-TOKEN` or JWT required. The unique `qrisCode` in the URL serves as the identifier.

**Response** — `image/png`, 300×300 pixels

---

## Common error responses

**Validation error — HTTP 400**

```json
{ "status": 0, "message": "fieldName: error description" }
```

**Not found — HTTP 404**

```json
{ "status": 0, "message": "Route not found" }
```

**Internal server error — HTTP 500**

```json
{ "status": 0, "message": "Internal server error" }
```

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
| `JWT_EXPIRATION_MS` | no | Token lifetime in ms. Default: `86400000` (24 h) |
| `DB_URL` | yes | JDBC URL e.g. `jdbc:postgresql://localhost:5432/bejo` |
| `DB_USERNAME` | yes | |
| `DB_PASSWORD` | yes | |
| `SEED_USERNAME` | no | Default user username. Default: `admin` |
| `SEED_PASSWORD` | no | Default user password. Seeding skipped if blank |
| `SEED_NAME` | no | Default user display name. Default: `Admin` |
| `SEED_BUSINESS_NAME` | no | Default user business name. Default: `My Business` |
| `SEED_BUSINESS_ADDRESS` | no | Default user business address |
| `FIREBASE_CREDENTIALS_BASE64` | no* | Base64-encoded Firebase service account JSON |
| `FIREBASE_CREDENTIALS_PATH` | no* | Path to Firebase service account JSON file |

\* One of the two Firebase options is required to use the notifications endpoint.
