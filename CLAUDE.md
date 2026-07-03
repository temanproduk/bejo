# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A Java 25 / Spring Boot REST API for QRIS merchants: registration and JWT login (against its own database), transaction history and manual entry, QRIS image management, bank/business profile management, FCM token management, and push notification delivery. Originally inspired by the Node.js project at `/Users/anton/Documents/Projects/cuan` but uses local DB authentication instead of scraping an external gateway.

The container runtime on this machine is **podman** (`podman compose ...`), not docker.

## Commands

```bash
# Build
mvn package -DskipTests

# Run all tests (unit + functional; functional tests use in-memory H2, no DB needed)
mvn test

# Run a single test
mvn test -Dtest=ClassName#methodName

# Run locally (requires PostgreSQL at localhost:5432)
mvn spring-boot:run

# Full stack via compose (podman on this machine)
podman compose up --build

# Rebuild only the app (then restart if the container is already running)
podman compose up --build app -d
podman compose restart app
```

## Required environment variables

| Variable | Description |
|---|---|
| `API_TOKEN` | Shared secret required in `X-API-TOKEN` on every request |
| `JWT_SECRET` | Base64-encoded secret, min 256 bits. Generate: `openssl rand -base64 32` |
| `JWT_EXPIRATION_MS` | Token TTL in milliseconds (default `7776000000` = 90 days; `0` disables expiry) |
| `DB_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/bejo` |
| `DB_USERNAME` / `DB_PASSWORD` | PostgreSQL credentials |
| `FIREBASE_CREDENTIALS_BASE64` | Base64-encoded Firebase service account JSON |
| `FIREBASE_CREDENTIALS_PATH` | Alternative: path to service account JSON file |
| `SEED_USERNAME` / `SEED_PASSWORD` / `SEED_NAME` / `SEED_BUSINESS_NAME` | Seed user (skipped when `SEED_PASSWORD` empty) |
| `SEED_NMID` | (Optional) National Merchant ID for the seed user |

Set via a `.env` file alongside `docker-compose.yml` — compose picks it up automatically. GCP deployment config lives in `.env.gcp` (see `.env.gcp.example`, `scripts/init-gcp.sh`, `.github/workflows/deploy.yml` — deploys on push to the `release` branch).

## Authentication

Every API request (including login) must include:

```
X-API-TOKEN: <value from API_TOKEN in .env>
```

`ApiTokenFilter` validates this first (constant-time comparison). If missing or wrong, returns `401` immediately.

Protected endpoints additionally require:

```
Authorization: Bearer <JWT>
```

The JWT is returned by the login endpoint in `data.token`. `JwtAuthFilter` validates signature + expiry and rejects blacklisted (logged-out) tokens; controllers resolve the username via Spring's `Authentication` principal.

`/actuator/**` and `/snap/**` (inbound QRIS payment callbacks) are exempt from both checks.

## API endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/snap/v1.0/qr/qr-mpm-notify` | None | Inbound QRIS payment callback (SNAP QR MPM notify); records a MANAGED transaction, stores raw payload in `additional_info` |
| `POST` | `/api/auth/register` | Public | Create user + bank + QRIS rows |
| `POST` | `/api/auth/login` | Public | Validate credentials, upsert session, returns JWT |
| `POST` | `/api/auth/logout` | JWT | Blacklist the current token (jti) |
| `GET` | `/api/transactions` | JWT | Transaction history; optional `dateStart`/`dateEnd` (ISO date, inclusive) |
| `POST` | `/api/transactions` | JWT | Add MANUAL/PARSED transaction; requires `Idempotency-Key` header |
| `PATCH` | `/api/transactions/{transactionId}` | JWT | Edit a MANUAL/PARSED transaction (partial update; MANAGED is immutable) |
| `DELETE` | `/api/transactions/{transactionId}` | JWT | Soft-delete a transaction (sets `deleted_at`) |
| `PUT` | `/api/devices/{deviceId}/fcm-token` | JWT | Update FCM push token (scoped to the authenticated user's session) |
| `POST` | `/api/notifications` | JWT | Send FCM push notification + record the transaction |
| `GET` | `/api/qris` | JWT | QRIS image; `type` = `UPLOADED` (custom) or `MANAGED` (generated) |
| `POST` | `/api/qris/upload` | JWT | Upload base64 QRIS image (max ±5 MB) |
| `POST` | `/api/qris/reset` | JWT | Revert to the managed/generated image |
| `PATCH` | `/api/users/me` | JWT | Update name, email, phone |
| `PATCH` | `/api/users/me/password` | JWT | Change password (requires `oldPassword`) |
| `PATCH` | `/api/users/me/bank` | JWT | Update bank info |
| `POST` | `/api/users/me/bank/reset` | JWT | Reset bank info to initial values |
| `PATCH` | `/api/users/me/business` | JWT | Update business name/address/NMID |
| `POST` | `/api/users/me/business/reset` | JWT | Reset business info to initial values |

All responses follow `{ "status": 1\|0, "message": "...", "data": {...}, "count": N }` — null fields are omitted.

Login success response `data` includes profile + bank fields plus `token`, `tokenType: "Bearer"`, `expiresIn` (seconds).

A Postman collection is at `bejo.postman_collection.json`. The Login request has a test script that auto-saves the token to the `{{token}}` collection variable used by all other requests.

## Architecture

```
controller/   → thin HTTP layer; validation via @Valid
service/      → business logic, metrics via Micrometer Counter
repository/   → Spring Data JPA interfaces
entity/       → JPA entities (User, UserBank, UserQris, Session, Transaction, TokenBlacklist)
dto/          → request records (@NotBlank validated) + response records
config/       → ApiTokenFilter, JwtAuthFilter, SecurityConfig, FirebaseConfig,
                RequestLoggingFilter, DataSeeder
exception/    → GlobalExceptionHandler + ResourceNotFoundException
```

Gotcha: `SecurityConfig`'s auth entry point distinguishes 401 vs 404/405 by consulting `RequestMappingHandlerMapping`. It must be injected with `@Qualifier("requestMappingHandlerMapping")` (actuator registers a second bean of that type) and the request path must be parsed via `ServletRequestPathUtils.parseAndCache` because the entry point runs before `DispatcherServlet`.

### Login flow

`AuthService.login()` authenticates against the local `users` table:
1. Look up `users` row by `username` — `Invalid credentials` (400) if not found or BCrypt mismatch.
2. Load the `user_banks` row for bank fields in the response.
3. Upsert a row in `cuan_sessions` keyed on `(username, device_id)`; an existing FCM token is preserved when the login request has no `pushToken`.
4. Return profile + bank data + JWT (`expiresIn` seconds).

Users are created via `POST /api/auth/register` (creates `users`, `user_banks`, and `user_qris` rows) or by `DataSeeder` at startup when `SEED_PASSWORD` is set.

### Transaction soft delete

`cuan_history.deleted_at = NULL` → active; non-null → deleted (V2 migration). `DELETE /api/transactions/{transactionId}` sets the timestamp; the row is never removed. History/edit/delete queries filter on `deleted_at IS NULL`, but `existsByTransactionId` and the idempotency-key replay lookup deliberately include deleted rows — the DB unique constraints span all rows, so a deleted `transaction_id`/`idempotency_key` still can't be reused.

### QRIS payment callback

`POST /snap/v1.0/qr/qr-mpm-notify` (`QrisCallbackController` → `QrisCallbackService`) receives SNAP-like QR MPM notifications from the QRIS merchant service (AstraPay), unauthenticated (server-to-server). It resolves the merchant by `User.nmid == payload.merchantPan` (falling back to `destinationNumber`, then to `app.callback.default-username`, default `admin`); is idempotent on `originalReferenceNo|originalPartnerReferenceNo` (stored as the transaction's `idempotencyKey`); maps SNAP status codes (`00`→SUCCESS…); records a `MANAGED`/`AstraPay` transaction; and saves the raw payload JSON to `cuan_history.additional_info`. Returns the callback's own ack shape (`QrMpmNotifyResponse`), not the `ApiResponse` envelope. `findFirstByNmid` is used (not `findBy`) so a duplicate NMID degrades gracefully instead of throwing.

### QRIS model

`user_qris.qris_image = NULL` → type `MANAGED` (serves `initial_qris_image`, generated on first access from `qris_code`); non-null → type `UPLOADED`. Reset simply nulls `qris_image`.

### Observability

- `/actuator/health` — liveness/readiness
- `/actuator/prometheus` — Micrometer metrics scraped by Prometheus (container: `bejo-prometheus:9090`)
- Custom counters: `login.attempts{result}`, `transactions.fetched`, `notifications.sent{result}`
- `RequestLoggingFilter` logs `METHOD PATH STATUS DURATIONms` for every request
- JSON log format active under `SPRING_PROFILES_ACTIVE=prod`

## Database

Schema is created by Flyway (`src/main/resources/db/migration/`, currently V1 init + V2 `deleted_at` + V3 `additional_info`) at app startup; `ddl-auto: none`. Never edit an applied migration in place — add a new versioned one.

Key tables:
- `users` — credentials + profile + business info (with `initial_*` columns for reset)
- `user_banks` / `user_qris` — one row per username (unique FK)
- `cuan_sessions` — one row per `(username, device_id)`; holds FCM push token, schedule window
- `cuan_history` — transactions; unique on `transaction_id` and `idempotency_key`; soft delete via `deleted_at`; general-purpose `additional_info` (TEXT, e.g. raw callback JSON)
- `token_blacklist` — logged-out JWT jtis, purged hourly after expiry

## Tests

- `src/test/java/com/bejo/service/*Test.java` — Mockito unit tests, no Spring context.
- `src/test/java/com/bejo/web/ApiIntegrationTest.java` — full-stack functional tests via `@SpringBootTest` + MockMvc against in-memory H2 (PostgreSQL mode); Flyway disabled, schema from `ddl-auto: create-drop` (`src/test/resources/application-test.yml`, profile `test`).
