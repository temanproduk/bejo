# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Working agreement

**Never `git commit` or `git push` automatically after making changes.** Make the edits, run the tests, then stop and report. Only commit and push when the user explicitly confirms/asks for it. This also gates the `release`-branch deploy (a push to `release` deploys to production), so it must always be an explicit, confirmed action.

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
| `JWT_EXPIRATION_MS` | Token TTL in milliseconds (default `604800000` = 7 days; `0` disables expiry) |
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

`/actuator/health` (Cloud Run probes) and `/snap/**` (inbound QRIS payment callbacks) are exempt from both checks. Other `/actuator/*` endpoints (metrics, prometheus, info) require `X-API-TOKEN` but not a JWT. Responses include HSTS + hardening headers (`SecurityConfig.headers`); `server.forward-headers-strategy: framework` lets Spring see the Cloud Run proxy's HTTPS scheme so HSTS is emitted.

## API endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/snap/v1.0/qr/qr-mpm-notify` | None | Inbound QRIS payment callback (SNAP QR MPM notify); records a MANAGED transaction, stores raw payload in `additional_info` |
| `POST` | `/api/users/register` | Public | Self-service registration; creates a `SELF_SERVICE` user + bank (no QRIS row — QRIS is optional) |
| `POST` | `/api/users/internal/register` | JWT | Internal/system registration; same payload but creates a `MANAGED` user |
| `POST` | `/api/auth/login` | Public | Validate credentials, upsert session, returns JWT |
| `POST` | `/api/auth/logout` | JWT | Blacklist the current token (jti) |
| `GET` | `/api/transactions` | JWT | Transaction history; optional `dateStart`/`dateEnd` (ISO date, inclusive) |
| `POST` | `/api/transactions` | JWT | Add MANUAL/PARSED transaction; requires `Idempotency-Key` header |
| `POST` | `/api/transactions/bulk` | JWT | Bulk-sync MANUAL/PARSED transactions (offline entry); per-item `idempotencyKey`, partial-success result |
| `PATCH` | `/api/transactions/{transactionId}` | JWT | Edit a MANUAL/PARSED transaction (partial update; MANAGED is immutable) |
| `DELETE` | `/api/transactions/{transactionId}` | JWT | Soft-delete a transaction (sets `deleted_at`) |
| `PUT` | `/api/devices/{deviceId}/fcm-token` | JWT | Update FCM push token (scoped to the authenticated user's session) |
| `POST` | `/api/notifications/test` | JWT | **Test:** send an FCM push from the request payload to one device (no DB write) |
| `GET` | `/api/qris` | JWT | QRIS image; `type` = `UPLOADED` (custom) or `MANAGED` (generated) |
| `POST` | `/api/qris/upload` | JWT | Upload base64 QRIS image (max ±5 MB) |
| `POST` | `/api/qris/reset` | JWT | Revert to the managed/generated image |
| `PATCH` | `/api/users/me` | JWT | Update name, email, phone |
| `PATCH` | `/api/users/me/password` | JWT | Change password (requires `oldPassword`) |
| `GET` | `/api/users/me/profile-picture` | JWT | Get profile picture (base64), or `null` when unset |
| `PUT` | `/api/users/me/profile-picture` | JWT | Set profile picture from base64 (compressed, max 1 MB decoded) |
| `DELETE` | `/api/users/me/profile-picture` | JWT | Remove profile picture (sets it back to `null`) |
| `PATCH` | `/api/users/me/bank` | JWT | Update bank info |
| `POST` | `/api/users/me/bank/reset` | JWT | Reset bank info to initial values |
| `PATCH` | `/api/users/me/business` | JWT | Update business name/address/NMID |
| `POST` | `/api/users/me/business/reset` | JWT | Reset business info to initial values |
| `POST` | `/api/categories` | JWT | Create a product category |
| `GET` | `/api/categories` | JWT | List the merchant's categories |
| `DELETE` | `/api/categories/{id}` | JWT | Delete a category (its products become uncategorized) |
| `POST` | `/api/products` | JWT | Create a product (SKU); optional `categoryId`, `photo` (base64 ≤500 KB) |
| `GET` | `/api/products` | JWT | List products; optional `?categoryId=` filter (omits `photo`) |
| `GET` | `/api/products/{id}` | JWT | Get one product (includes `photo`) |
| `PATCH` | `/api/products/{id}` | JWT | Partial update (name, price, cost, stock, category, photo) |
| `DELETE` | `/api/products/{id}` | JWT | Delete a product (hard) |
| `POST` | `/api/purchases` | JWT | Record a purchase order (restock): raises stock of several products at once |
| `GET` | `/api/purchases` | JWT | List purchase history (summary, omits line items) |
| `GET` | `/api/purchases/{id}` | JWT | Get one purchase with its line items |
| `GET` | `/api/app-settings` | JWT | Get the user's app settings (monitored apps + notification keywords); returns defaults if never synced |
| `PUT` | `/api/app-settings/monitored-apps` | JWT | Replace the monitored-apps map (`packageName`→`issuer`) |
| `PUT` | `/api/app-settings/keywords` | JWT | Update notification keyword filters (include/exclude lists + blacklist/whitelist toggles; partial update) |
| `PUT` | `/api/app-settings/qris-with-profile-picture` | JWT | Toggle composing the QRIS image onto the profile picture (`{enabled}`; default false) |
| `PUT` | `/api/app-settings/inventory` | JWT | Toggle inventory management (`{enabled}`; default false) |

All responses follow `{ "status": 1\|0, "message": "...", "data": {...}, "count": N }` — null fields are omitted.

Login success response `data` includes profile + bank fields plus `token`, `tokenType: "Bearer"`, `expiresIn` (seconds).

A Postman collection is at `bejo.postman_collection.json`. The Login request has a test script that auto-saves the token to the `{{token}}` collection variable used by all other requests.

## Architecture

```
controller/   → thin HTTP layer; validation via @Valid
service/      → business logic, metrics via Micrometer Counter
repository/   → Spring Data JPA interfaces
entity/       → JPA entities (User, UserBank, UserQris, Session, Transaction, TokenBlacklist)
dto/          → request records (@NotBlank validated) + response records.
                Every DTO class name ends with `Dto` (e.g. LoginRequestDto,
                TransactionDto); requests and responses are separate types.
config/       → ApiTokenFilter, JwtAuthFilter, SecurityConfig, FirebaseConfig,
                RequestLoggingFilter, DataSeeder
constant/     → canonical domain values (no magic strings): AccountType,
                TransactionType, TransactionStatus, QrisType, Acquirer
converter/    → JPA AttributeConverters storing JSON in TEXT columns
                (StringListJsonConverter, StringMapJsonConverter)
exception/    → GlobalExceptionHandler + ResourceNotFoundException
```

Domain "possible values" live in `constant/` as `public static final String` holders — reference these instead of inline literals: `AccountType` (`MANAGED`, `SELF_SERVICE`), `TransactionType` (`MANUAL`, `PARSED`, `MANAGED`), `TransactionStatus` (`SUCCESS`, `PENDING`, `FAILED`, `REFUNDED`, `CANCELLED`, `EXPIRED`, `UNKNOWN`), `QrisType` (`UPLOADED`, `MANAGED`), `Acquirer` (`CASH`, `ASTRAPAY` defaults).

Gotcha: `SecurityConfig`'s auth entry point distinguishes 401 vs 404/405 by consulting `RequestMappingHandlerMapping`. It must be injected with `@Qualifier("requestMappingHandlerMapping")` (actuator registers a second bean of that type) and the request path must be parsed via `ServletRequestPathUtils.parseAndCache` because the entry point runs before `DispatcherServlet`.

### Login flow

`AuthService.login()` authenticates against the local `users` table:
1. Look up `users` row by `username` — `Invalid credentials` (400) if not found or BCrypt mismatch.
2. Load the `user_banks` row for bank fields in the response.
3. Upsert a row in `cuan_sessions` keyed on `(username, device_id)`; an existing FCM token is preserved when the login request has no `pushToken`.
4. Return profile + bank data + JWT (`expiresIn` seconds).

### Registration & account types

Registration lives in `UserService` (not `AuthService` — auth is only login/logout/session). Two entry points, both create `users` + `user_banks` rows (bank fields optional, default to empty strings) and **no** `user_qris` row (QRIS is optional — added later via upload):

- `POST /api/users/register` (public) → `AccountType.SELF_SERVICE`. For self-registered merchants.
- `POST /api/users/internal/register` (JWT-protected) → `AccountType.MANAGED`. Same payload; for internal/system provisioning.

`DataSeeder` also creates a `MANAGED` seed user at startup when `SEED_PASSWORD` is set — but seed data (including `seed-qris.b64`) is for the seed/test user only and must never back any other feature.

### Bulk transaction sync (offline)

`POST /api/transactions/bulk` (`TransactionService.bulkCreate`) lets the client push a batch of manually-entered transactions accumulated while offline. Key design points:
- **Per-item idempotency** — each item carries its own client-generated `idempotencyKey` (not the request-level `Idempotency-Key` header), so re-sending the whole batch (or a partial batch after a dropped connection) never duplicates.
- **Partial success** — the method is deliberately **not** `@Transactional`; each item is validated individually (via an injected `jakarta.validation.Validator`, since the request DTO does not cascade-validate its list elements) and saved in its own transaction. One malformed/duplicate row can't roll back or block the rest.
- **Per-item result** — returns `BulkTransactionResultResponseDto` with counts + a `results` list of `{idempotencyKey, status (CREATED|DUPLICATE|FAILED), transactionId, error}`. The client matches by `idempotencyKey` to mark local rows synced (CREATED/DUPLICATE) or retry/fix (FAILED). A concurrent unique-constraint hit is caught and reported as DUPLICATE.

### Transaction soft delete

`cuan_history.deleted_at = NULL` → active; non-null → deleted (V2 migration). `DELETE /api/transactions/{transactionId}` sets the timestamp; the row is never removed. History/edit/delete queries filter on `deleted_at IS NULL`, but `existsByTransactionId` and the idempotency-key replay lookup deliberately include deleted rows — the DB unique constraints span all rows, so a deleted `transaction_id`/`idempotency_key` still can't be reused.

### QRIS payment callback

`POST /snap/v1.0/qr/qr-mpm-notify` (`QrisCallbackController` → `QrisCallbackService`) receives SNAP-like QR MPM notifications from the QRIS merchant service (AstraPay), unauthenticated (server-to-server). It resolves the merchant by `User.nmid == payload.merchantPan` (falling back to `destinationNumber`, then to `app.callback.default-username`, default `admin`); is idempotent on `originalReferenceNo|originalPartnerReferenceNo` (stored as the transaction's `idempotencyKey`); maps SNAP status codes (`00`→SUCCESS…); records a `MANAGED`/`AstraPay` transaction; saves the raw payload JSON to `cuan_history.additional_info`; and sends a best-effort FCM push to all the merchant's devices. Returns the callback's own ack shape (`QrMpmNotifyResponse`), not the `ApiResponse` envelope. `findFirstByNmid` is used (not `findBy`) so a duplicate NMID degrades gracefully instead of throwing.

FCM sending lives in `PushNotificationService` (shared): `sendToToken` (one device, propagates errors — used by the test-notification API) and `sendToUser` (all a user's device sessions, best-effort, never throws — used by the callback). `NotificationService` (the `/api/notifications/test` endpoint) only sends a push; it does not persist a transaction.

### QRIS model

QRIS is optional: registration creates no `user_qris` row, so `GET /api/qris` returns `404` until the user uploads one. `POST /api/qris/upload` creates the row on first upload (`qris_code` auto-generated). Once a row exists: `qris_image = NULL` → type `MANAGED` (serves `initial_qris_image`, generated on first access from `qris_code`); non-null → type `UPLOADED`. Reset simply nulls `qris_image`.

### Products & inventory

Basic per-merchant SKU catalog with simple inventory, scoped by `username`:
- `ProductCategoryController` → `ProductCategoryService` — categories (`product_categories`); names unique per merchant. Deleting a category first **un-categorizes** its products (`ProductRepository.clearCategory` sets their `category_id` to null in the service, so behavior matches under both Flyway/Postgres and the H2 test schema) then removes the row — products are never deleted with it.
- `ProductController` → `ProductService` — products (`products`); `sku` unique per merchant. `stock` is a plain column (inventory management = `PATCH` the value; no ledger). A product's `categoryId` must belong to the same merchant (else 400). `photo` is an optional base64 image validated ≤500 KB decoded; **list responses omit the photo** to keep payloads small, the single-product detail includes it. Prices are `BIGINT` IDR; `costPrice` is optional.

### Purchases (restock)

`PurchaseController` → `PurchaseService` records purchase orders that **raise** the stock of several products at once (`purchases` + `purchase_items`). `POST /api/purchases` is atomic and two-pass: pass 1 resolves every line to one of the merchant's own products (unknown product → 400, nothing changes); pass 2 raises each product's `stock`, snapshots the line (`sku`/`name`/`unitPrice`) and persists the purchase + items. Line `unitPrice` is the provided value, else the product's `costPrice`, else 0; `total_amount` = Σ subtotals. `purchase_items.product_id` has no FK and the line snapshots product fields, so history survives a product edit/delete. `GET /api/purchases` returns summaries (items omitted); `GET /api/purchases/{id}` includes the items.

### App settings

`AppSettingsController` → `AppSettingsService` manage a per-user `user_app_settings` row (one per username, created lazily on first update) holding the settings synced from the mobile client:
- **Monitored apps** — a `packageName → issuer` map (mirrors the Android `notifAppList` pref), stored as JSON via `StringMapJsonConverter`.
- **Notification keywords** — `includeKeywords` (whitelist) + `excludeKeywords` (blacklist) string lists (JSON via `StringListJsonConverter`) plus `blacklistEnabled` (default true) / `whitelistEnabled` (default false) toggles.

- **App config toggles** — `qrisWithProfilePicture` (compose the QRIS image onto the profile picture) and `inventoryEnabled` (enable inventory management), both boolean columns defaulting false, each set via its own `PUT` (`{enabled}`).

`GET /api/app-settings` returns defaults (empty lists/map, blacklist on, whitelist off, both toggles off) when no row exists yet — it never 404s. `PUT /api/app-settings/monitored-apps` replaces the whole map; `PUT /api/app-settings/keywords` is a partial update (null field = unchanged, empty list = cleared). The service validates and normalizes input — package names match `[A-Za-z0-9_.]+`, issuers/keywords are trimmed + lowercased, keyword lists deduped, with size caps — throwing `IllegalArgumentException` (400) on bad input.

### Observability

- `/actuator/health` — liveness/readiness
- `/actuator/prometheus` — Micrometer metrics scraped by Prometheus (container: `bejo-prometheus:9090`)
- Custom counters: `login.attempts{result}`, `transactions.fetched`, `notifications.sent{result}`
- `RequestLoggingFilter` logs `METHOD PATH[?query] STATUS DURATIONms` for every request. On a 4xx/5xx it additionally logs the request body (`[BODY] …`) — secrets (`password`/`token`/`pushToken` fields) masked, single-line, capped at 2 KB, and auth endpoints (`/api/auth/**`, register) skipped entirely. Toggle with `app.request-logging.log-body-on-error` (default `true`) / `app.request-logging.max-body-length` (default `2048`).
- The QRIS callback (`/snap/v1.0/qr/qr-mpm-notify`) gets a fuller dump: on any 4xx/5xx, `RequestLoggingFilter` logs `[CALLBACK-REQ]` with **all headers** (credential headers `Authorization`/`X-API-TOKEN`/`Cookie` masked) and the **full body** (capped 8 KB). Its body is buffered up front (`CachedBodyHttpServletRequest`) so it's captured even when validation fails before the body is read (e.g. a missing `X-TIMESTAMP` header).
- `GlobalExceptionHandler` logs every 4xx client error at `WARN` (`[400] METHOD /path — reason`), so requests rejected before reaching a service (validation, missing header, unreadable body) are still debuggable in Cloud Run.
- JSON log format active under `SPRING_PROFILES_ACTIVE=prod`

## Database

Schema is created by Flyway (`src/main/resources/db/migration/`, currently V1 init + V2 `deleted_at` + V3 `additional_info` + V4 `user_app_settings` + V5 profile picture + products/categories + purchases) at app startup; `ddl-auto: none`. Never edit an applied migration in place — add a new versioned one. (V5 was consolidated from three pre-production migrations; V1–V4 are in production and must never change.)

Key tables:
- `users` — credentials + profile + business info (with `initial_*` columns for reset); `account_type` is `MANAGED` (internal/seed) or `SELF_SERVICE` (self-registered); optional `profile_picture` (base64 TEXT, ≤1 MB decoded)
- `user_banks` / `user_qris` — one row per username (unique FK)
- `cuan_sessions` — one row per `(username, device_id)`; holds FCM push token, schedule window
- `cuan_history` — transactions; unique on `transaction_id` and `idempotency_key`; soft delete via `deleted_at`; general-purpose `additional_info` (TEXT, e.g. raw callback JSON)
- `token_blacklist` — logged-out JWT jtis, purged hourly after expiry
- `user_app_settings` — one row per username; monitored-apps map + notification keyword filters (JSON in TEXT columns) + blacklist/whitelist toggles + app config toggles (`qris_with_profile_picture`, `inventory_enabled`)
- `product_categories` — per-merchant product categories; name unique per username
- `products` — per-merchant SKUs; `sku` unique per username; nullable `category_id` FK; `stock` (INTEGER), `price`/`cost_price` (BIGINT IDR), optional base64 `photo` (TEXT, ≤500 KB decoded)
- `purchases` / `purchase_items` — per-merchant restock orders; a purchase raises product stock and records line items (product snapshot + `unit_price`/`quantity`/`subtotal`); `total_amount` is the sum

## Tests

- `src/test/java/com/bejo/service/*Test.java` — Mockito unit tests, no Spring context.
- `src/test/java/com/bejo/web/ApiIntegrationTest.java` — full-stack functional tests via `@SpringBootTest` + MockMvc against in-memory H2 (PostgreSQL mode); Flyway disabled, schema from `ddl-auto: create-drop` (`src/test/resources/application-test.yml`, profile `test`).
