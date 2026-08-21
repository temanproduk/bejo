# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Working agreement

**Never `git commit` or `git push` automatically after making changes.** Make the edits, run the tests, then stop and report. Only commit and push when the user explicitly confirms/asks for it. This also gates the `release`-branch deploy (a push to `release` deploys to production), so it must always be an explicit, confirmed action.

## What this project is

A Java 25 / Spring Boot REST API for QRIS merchants: registration and JWT login (against its own database), transaction history and manual entry, QRIS management, bank/business profile management, FCM token management, and push notification delivery. Originally inspired by the Node.js project at `/Users/anton/Documents/Projects/cuan` but uses local DB authentication instead of scraping an external gateway.

**User ↔ Business:** a `business` (merchant) is first-class and owns all merchant data (bank, QRIS, products, categories, transactions, stock mutations, app settings, the shared business picture). **Many users may belong to one business** (flat model), each with a `business_role`: `SUPER_ADMIN` (the creator — immutable, undeletable, the only one who can add/update/delete users) or `MEMBER`. All business-owned data is scoped by `business_id`; services resolve it from the authenticated username via `BusinessContext`. Only per-user things (credentials, name/email/phone, sessions/FCM) stay keyed on `username`.

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
| `LOGIN_MAX_FAILURES` | Consecutive failed logins per username before lockout (`app.security.login.max-failures`, default `10`) |
| `LOGIN_LOCKOUT_MINUTES` | How long a locked account must wait (`app.security.login.lockout-minutes`, default `15`) |
| `MAX_REQUEST_BYTES` | Max request body size, 413 above it (`app.security.max-request-bytes`, default `8388608` = 8 MB) |
| `BUSINESS_MAX_USERS` | Max users per business, incl. the super admin (`app.business.max-users`, default `10`) |
| `BUSINESS_SIZES` / `BUSINESS_DEFAULT_SIZE` | Allowed business sizes + the default (comma-separated; `app.business.sizes`) |
| `BUSINESS_INDUSTRIES` / `BUSINESS_DEFAULT_INDUSTRY` | Allowed industries + the default (comma-separated; `app.business.industries`) |
| `DB_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/bejo` |
| `DB_USERNAME` / `DB_PASSWORD` | PostgreSQL credentials |
| `FIREBASE_CREDENTIALS_BASE64` | Base64-encoded Firebase service account JSON |
| `FIREBASE_CREDENTIALS_PATH` | Alternative: path to service account JSON file |
| `ADS_GCS_BUCKET` | Cloud Storage bucket for voice-ad audio (`app.ads.gcs-bucket`; created + locked down by `scripts/init-gcp.sh`) |
| `ADS_ADMIN_TOKEN` | Dedicated secret gating the ads admin surface (`X-ADS-ADMIN-TOKEN`; `app.ads.admin-token`, blank = feature off) |
| `ADS_SIGNED_URL_TTL_SECONDS` | Lifetime of the signed audio download URL (`app.ads.signed-url-ttl-seconds`, default `86400` = 24h; GCS max 7d) |
| `ADS_MAX_AUDIO_BYTES` | Max decoded audio size (`app.ads.max-audio-bytes`, default `2097152` = 2 MB) |
| `ADS_MAX_AUDIO_SECONDS` | Max audio duration (`app.ads.max-audio-seconds`, default `10`) |
| `SEED_USERNAME` / `SEED_PASSWORD` / `SEED_NAME` / `SEED_BUSINESS_NAME` | Seed user (skipped when `SEED_PASSWORD` empty) |
| `SEED_NMID` | (Optional) National Merchant ID for the seed user |
| `SEED_MERCHANT_PAN` | (Optional) QRIS merchant PAN for the seed business (callback routing) |
| `PUSH_QUEUE_THREADS` / `PUSH_QUEUE_CAPACITY` | (Optional) FCM delivery worker pool size + queue capacity (`app.push.queue.*`, default `4` / `1000`) |

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

The JWT is returned by the login endpoint in `data.token`. `JwtAuthFilter` accepts a token only when **all four** hold, resolving them from a **single** `JwtService.parse(token)` and a **single** user query (`findPasswordHashByUsername`) — it used to parse the token three times and derive the HMAC key on each parse:
1. signature + expiry valid;
2. `jti` not blacklisted (logged out);
3. the user still exists and isn't soft-deleted (empty query result — a deleted member's still-signed token stops authenticating immediately, and its released username can't be impersonated);
4. the token's **password-binding claim** (`pwb`) still matches the user's current password hash.

Controllers resolve the username via Spring's `Authentication` principal.

**Password binding (token revocation without a schema column).** `JwtService.generateToken` embeds `pwb` = `HMAC-SHA256(jwtSecret, passwordHash)` truncated to 16 hex chars; the filter re-derives it per request. So **changing a password instantly invalidates every token issued before it, on every device** — whether the user changed it themselves (`PATCH /api/users/me/password`) or a super admin reset it (`PATCH /api/users/members/{id}`). It's an HMAC under the signing secret, not a plain digest, so a token holder can't derive it or learn anything about the password from the payload. *Consequence:* a token minted before this claim existed carries no `pwb` and is rejected — deploying it signs everyone out once.

**Login throttle.** `LoginThrottleService` allows `app.security.login.max-failures` (default **10**) *consecutive* failures per username, then locks that account for `app.security.login.lockout-minutes` (default **15**) → **429**. Any success resets the counter, so typos never lock a real user out. It exists because `X-API-TOKEN` ships inside the mobile app and so can't gate the endpoint on its own. State is in-memory per instance (like the push queue) with a `@Scheduled` purge. Login also runs a BCrypt comparison against a dummy hash when the username is unknown, so response time doesn't reveal which usernames exist.

**Request size.** `RequestSizeLimitFilter` rejects bodies over `app.security.max-request-bytes` (default **8 MB**) with **413**, before anything reads or buffers them — Tomcat's `maxPostSize` only covers form encoding, so JSON bodies were unbounded while several endpoints base64-decode images in memory. The cap has plenty of headroom now that QRIS travels as a code rather than a multi-MB base64 image; the largest remaining payloads are the ≤1 MB business picture and ≤2 MB ad audio.

`/actuator/health` (Cloud Run probes) and `/snap/**` (inbound QRIS payment callbacks) are exempt from both checks. Other `/actuator/*` endpoints (metrics, prometheus, info) require `X-API-TOKEN` but not a JWT — so `/actuator/prometheus` is **not** publicly reachable on Cloud Run. The local Prometheus scraper sends the token via `http_headers` (see `docker/prometheus.yml` + the `prometheus_api_token` compose config). Responses include HSTS + hardening headers (`SecurityConfig.headers`); `server.forward-headers-strategy: framework` lets Spring see the Cloud Run proxy's HTTPS scheme so HSTS is emitted.

## API endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/snap/v1.0/qr/qr-mpm-notify` | None | Inbound QRIS payment callback (SNAP QR MPM notify); records a MANAGED transaction, stores raw payload in `info` |
| `POST` | `/api/users/register` | Public | Self-service registration; creates a **new business** + its first user (the `SUPER_ADMIN`) + bank (no QRIS row — QRIS is optional) |
| `GET` | `/api/users/me` | JWT | The caller's own account bundle (`MeResponseDto`: id + profile + role + shared business/bank/picture/account-settings) — same shape as login minus the token, for refreshing state |
| `GET` | `/api/users/members` | JWT | List the users of the caller's business (any member) |
| `POST` | `/api/users/members` | JWT (super admin) | Add a user to the caller's business as a `MEMBER` |
| `PATCH` | `/api/users/members/{id}` | JWT (super admin) | Update a member (name/email/phone/password; a super admin can't be modified here) |
| `DELETE` | `/api/users/members/{id}` | JWT (super admin) | Soft-delete a member (releases its username); a super admin can't be deleted |
| `POST` | `/api/auth/login` | Public | Validate credentials, upsert session, returns JWT |
| `POST` | `/api/auth/logout` | JWT | Blacklist the current token (jti); optional body `{deviceId}` clears that device's FCM token |
| `GET` | `/api/transactions` | JWT | Transaction history; optional `dateStart`/`dateEnd` (ISO date, inclusive); paginated (`page`/`size`). Each row carries `createdBy`/`updatedBy` (who created/last-edited it) |
| `GET` | `/api/transactions/summary` | JWT | Dashboard totals (summed amount + count) for today / yesterday / this month; optional `date` = client's local "today" (defaults to server date) |
| `GET` | `/api/transactions/{transactionId}` | JWT | One transaction **with its sold-product line items** (the history list omits items) |
| `POST` | `/api/transactions` | JWT | Add MANUAL/PARSED transaction; requires `Idempotency-Key` header. Optional `items` (product line items) — validates + reduces stock, records a SALE stock mutation. A **PARSED** entry also pushes to everyone sharing the QRIS it landed on **except the reporting device**, identified by the optional `X-Device-Id` header (see below) |
| `POST` | `/api/transactions/bulk` | JWT | Bulk-sync MANUAL/PARSED transactions (offline entry); per-item `idempotencyKey`, partial-success result (no line items, no PARSED push) |
| `PATCH` | `/api/transactions/{transactionId}` | JWT | Edit a MANUAL/PARSED transaction (partial update; MANAGED is immutable). Optional `items` fully replaces the line items, reconciling stock by delta + re-deriving amount. Stamps `updatedBy` |
| `DELETE` | `/api/transactions/{transactionId}` | JWT (super admin) | Soft-delete a transaction (sets `deleted_at`); returns any sold line items' stock to their products. **Sub-accounts get 403** |
| `PUT` | `/api/devices/{deviceId}/fcm-token` | JWT | Update FCM push token (scoped to the authenticated user's session) |
| `POST` | `/api/notifications/test` | JWT | **Test:** send an FCM push from the request payload to one device (no DB write) |
| `POST` | `/api/notifications/user` | JWT | **Test/utility:** broadcast an FCM push to **all** the caller's devices (no `deviceId`; via `sendToUser`; no DB write) |
| `GET` | `/api/qris` | JWT | The caller's **effective** QRIS: their own per-user QRIS if assigned, else the shared business QRIS. `type` = `UPLOADED` (custom) or `MANAGED` (generated) |
| `POST` | `/api/qris/upload` | JWT (super admin) | Set the **business** (shared) QRIS **code** (EMV payload string, max 1024 chars); parses merchant name / NMID / acquirer out of it |
| `PUT` | `/api/qris/users/{id}` | JWT (super admin + team mgmt) | Assign a user their own QRIS (`{qrisCode}`); terminal id is parsed from the code and callbacks route by it |
| `GET` | `/api/qris/users` | JWT (super admin) | List the business's per-user QRIS assignments (images omitted) |
| `GET` | `/api/qris/users/{id}` | JWT (super admin) | One user's QRIS assignment (with image) |
| `DELETE` | `/api/qris/users/{id}` | JWT (super admin) | Remove a user's assignment (they revert to the business QRIS) |
| `PATCH` | `/api/users/me` | JWT | Update name, email, phone |
| `PATCH` | `/api/users/me/password` | JWT | Change password (requires `oldPassword`) |
| `GET`/`PUT`/`DELETE` | `/api/business/picture` | JWT (GET: any member · PUT/DELETE: super admin) | Get/set/remove the **business** picture (base64, ≤1 MB, shared by all members). Response field is `profilePicture` |
| `PATCH` | `/api/users/me/bank` | JWT (super admin) | Update bank info |
| `PATCH` | `/api/users/me/business` | JWT (super admin) | Update business name/address (**NMID is not editable — it comes from the QRIS code**) |
| `POST` | `/api/users/me/upgrade` | JWT (super admin) | Upgrade a self-service account to **MANAGED** (requires `merchantPan` + `nmid`; both mandatory); sets the routing credentials + flips `account_type`. Returns the refreshed `/me` bundle |
| `POST` | `/api/categories` | JWT | Create a product category |
| `GET` | `/api/categories` | JWT | List the merchant's categories |
| `DELETE` | `/api/categories/{id}` | JWT | Delete a category (its products become uncategorized) |
| `POST` | `/api/products` | JWT | Create a product (SKU); optional `categoryId`, `photo` (base64 ≤500 KB) |
| `GET` | `/api/products` | JWT | List products; optional `?categoryId=` filter (omits `photo`); paginated (`page`/`size`) |
| `GET` | `/api/products/{id}` | JWT | Get one product (includes `photo`) |
| `PATCH` | `/api/products/{id}` | JWT | Partial update (name, short description, price, cost, stock, category, photo) |
| `DELETE` | `/api/products/{id}` | JWT | Delete a product (hard) |
| `POST` | `/api/stock-mutations/adjustments` | JWT | Bulk stock adjustment (stock opname): set several products' stock to counted values; returns a variance report |
| `POST` | `/api/stock-mutations` | JWT | Record a purchase (restock): raises stock of several products at once (a PURCHASE mutation) |
| `GET` | `/api/stock-mutations` | JWT | Stock-change history — mutations (PURCHASE/ADJUSTMENT/UPDATE) with their line items; paginated (`page`/`size`) |
| `GET` | `/api/stock-mutations/{id}` | JWT | Get one stock mutation with its line items |
| `GET` | `/api/account-settings` | JWT | Get the account's settings (monitored apps + notification keywords + config toggles); returns defaults if never synced |
| `PUT` | `/api/account-settings/monitored-apps` | JWT (super admin) | Replace the monitored-apps map (`packageName`→`issuer`) |
| `PUT` | `/api/account-settings/keywords` | JWT (super admin) | Update notification keyword filters (include/exclude lists + blacklist/whitelist toggles; partial update) |
| `PUT` | `/api/account-settings/toggle` | JWT (super admin) | Generic on/off for one boolean setting: `{setting, enabled}` where `setting` ∈ `qrisWithProfilePicture`\|`inventory`\|`receiveAds`\|`teamManagement` (unknown → 400) |
| `POST` | `/api/presences/clock-in` | JWT (member) | Employee clock-in: `latitude`/`longitude`/`clockDatetime` required, `note` optional; pushes a notification to the super admin |
| `POST` | `/api/presences/clock-out` | JWT (member) | Employee clock-out (same body); pushes a notification to the super admin |
| `POST` | `/api/presences/pings` | JWT (super admin) | Spot-check: records + pushes one PING entry to **every** member (optional `note` = the push body) |
| `POST` | `/api/presences/{id}/respond` | JWT (member) | Acknowledge one's own PING (stamps `responseDatetime`; only once) |
| `GET` | `/api/presences` | JWT | Attendance history; super admin sees the whole business (optional `userId` filter), a member only their own. Optional `year` + `month`; paginated (`page`/`size`) |
| `POST` | `/api/ads/campaigns` | `X-ADS-ADMIN-TOKEN` (no JWT) | Create a voice-ad campaign: upload the audio (base64, **≤2 MB and ≤10s**, both validated before upload) + target one device (`targetUsername`/`targetDeviceId`); records campaign + one delivery (CREATED) |
| `POST` | `/api/ads/campaigns/{id}/prepare` | `X-ADS-ADMIN-TOKEN` | Push a PREPARE (with a fresh signed download URL) → delivery PREPARING |
| `POST` | `/api/ads/campaigns/{id}/play` | `X-ADS-ADMIN-TOKEN` | Requires READY; push a PLAY → delivery STARTING |
| `GET` | `/api/ads/campaigns[/{id}]` | `X-ADS-ADMIN-TOKEN` | List (paginated) / get a campaign with its delivery state |
| `GET` | `/api/ads/history` | JWT (super admin) | The merchant's ad history: every delivery to the business's devices (this account + sub-accounts, **all states**), newest first, paginated; each row flattens the campaign name/brand + status + played time |
| `POST` | `/api/ads/deliveries/{id}/downloaded` | JWT (the target) | App reports the download: `{success, sha256, sizeBytes, error?}` → READY (integrity matches) or ERROR |
| `POST` | `/api/ads/deliveries/{id}/played` | JWT (the target) | App reports it played: `{playedAt, latitude?, longitude?}` → PLAYED, or FINISHED if location included |
| `POST` | `/api/ads/deliveries/{id}/location` | JWT (the target) | The deferred play location (foreground-only): `{latitude, longitude}` → FINISHED |

All responses follow `{ "status": 1\|0, "message": "...", "data": {...}, "count": N }` — null fields are omitted. Paginated list endpoints (`GET /api/transactions`, `/api/products`, `/api/stock-mutations`) additionally return `page` (1-based), `totalPages`, and `total`; `count` is the size of the current page. Pagination is optional — omit `page`/`size` for page 1 of 50 (`size` cap 200). Built via `util/Pagination.of(page,size)` → `ApiResponseDto.page(Page<T>)`.

Login success response `data` includes profile + `businessRole` + business + bank fields plus `token`, `tokenType: "Bearer"`, `expiresIn` (seconds).

A Postman collection is at `bejo.postman_collection.json`, driven by **Postman environments** — import `bejo.local.postman_environment.json` (Bejo — Local) or `bejo.production.postman_environment.json` (Bejo — Production) and select one; each defines `baseUrl`, `apiToken`, and `token`. The Login request's test script auto-saves the JWT into the active environment's `token` (used by all other requests).

## Architecture

**Modular monolith** — code is organized package-**by-module** (`com.bejo.<module>`), not by layer, and the boundaries are enforced by `ArchitectureTest` (ArchUnit) on every build. Allowed dependency direction: `commerce, ads → core → notification, shared`.

```
shared/        cross-cutting library, depends on NO module:
                 util/ (AppTime, Pagination) · exception/ (GlobalExceptionHandler,
                 ResourceNotFoundException, ForbiddenException) · persistence/ (JSON
                 AttributeConverters) · web/ (ApiResponseDto)
notification/  push delivery infrastructure, depends on NO module. Owns the FCM send
                 (PushJobSender), the queue (PushDeliveryQueue/InMemoryPushDeliveryQueue),
                 the fan-out (PushNotificationService), PushType, the payload hierarchy
                 (PushPayload/AbstractPushPayload/SimplePushPayload), and the PORTS core
                 implements: PushRecipientDirectory + StaleTokenHandler (Option B). Only
                 this module touches com.google.firebase.messaging.
core/          users, business, auth, presence, QRIS, sessions, account-settings. The
                 foundation. Sub-packages web/service/domain/repository/dto, plus push/
                 (PresencePushPayload) and notification/ (the port ADAPTERS:
                 SessionPushRecipientDirectory, SessionStaleTokenHandler).
commerce/      transactions, products, categories, stock, the QRIS payment callback.
                 web/service/domain/repository/dto + push/ (TransactionPushPayload).
ads/            voice-ad campaigns. web/service/domain/repository/dto + push/ (AdsPushPayload).
config/        Spring bootstrap / composition root (may wire any module; nothing depends
                 on it): ApiTokenFilter, JwtAuthFilter, SecurityConfig, FirebaseConfig,
                 RequestLoggingFilter, DataSeeder.
```

Inside each module: `web/` (thin HTTP, `@Valid`) · `service/` (logic, Micrometer counters) · `repository/` (Spring Data JPA) · `domain/` (JPA entities + the module's constant holders) · `dto/` (request records `@NotBlank`-validated + response records; every DTO class name ends with `Dto`, requests/responses separate) · `push/` (the module's own `PushPayload`). Adding a cross-module call means depending on the other module's public API, never reaching into its internals — the ArchUnit rules fail the build otherwise.

Domain "possible values" live in each module's `domain/` package (and `PushType` in `notification/`) as `public static final String` holders — reference these instead of inline literals: `AccountType` (`MANAGED`, `SELF_SERVICE`), `BusinessRole` (`SUPER_ADMIN`, `MEMBER`), `TransactionType` (`MANUAL`, `PARSED`, `MANAGED`), `TransactionStatus` (`SUCCESS`, `PENDING`, `FAILED`, `REFUNDED`, `CANCELLED`, `EXPIRED`, `UNKNOWN`), `Acquirer` (`CASH`, `ASTRAPAY` defaults), `StockMutationType` (`PURCHASE`, `ADJUSTMENT`, `UPDATE`, `SALE`), `PresenceType` (`CLOCK_IN`, `CLOCK_OUT`, `PING`), `PushType` (`TRANSACTION`, `PRESENCE`, `ADS` — the `type` field in every push's data payload), `AdCampaignState` (`CREATED`, `PREPARING`, `READY`, `ERROR`, `SCHEDULED`, `STARTING`, `PLAYED`, `FINISHED`), `AdEvent` (`PREPARE`, `PLAY` — the ads push's `adEvent` discriminator).

Gotcha: `SecurityConfig`'s auth entry point distinguishes 401 vs 404/405 by consulting `RequestMappingHandlerMapping`. It must be injected with `@Qualifier("requestMappingHandlerMapping")` (actuator registers a second bean of that type) and the request path must be parsed via `ServletRequestPathUtils.parseAndCache` because the entry point runs before `DispatcherServlet`.

### Login flow

`AuthService.login()` authenticates against the local `users` table:
1. Look up the **active** `users` row by `username` (`findByUsernameAndDeletedAtIsNull`) — `Invalid credentials` (400) if not found or BCrypt mismatch (a soft-deleted user can't log in).
2. Load the `businesses` + `business_banks` rows (by the user's `business_id`) for business + bank fields in the response.
3. Upsert a row in `sessions` keyed on `(username, device_id)`; an existing FCM token is preserved when the login request has no `pushToken`.
4. Return `id` + profile + `businessRole` + **`hasOwnQris`** + business (name/address, **no NMID**) + bank + **`size` + `industry` + `storePhoto` + `profilePicture` (business picture) + `accountSettings`** + JWT (`expiresIn` seconds). The bundled picture/account-settings save the client round trips on the home screen; the individual endpoints (`/api/account-settings`, `/api/business/picture`, `GET /api/users/me`) still exist. `GET /api/users/me` returns the same bundle minus the token **and minus `size`/`industry`/`storePhoto`** — those are immutable (set once at registration), so the client caches them from the login response.

### Registration, businesses & members

Registration/user-management live in `UserService` + `MemberService` (not `AuthService` — auth is only login/logout/session). Scoping is resolved by `BusinessContext.requireBusinessId(username)` (a `@Component` mapping the authenticated username → `business_id`; throws 404 if the user is gone/soft-deleted).

- `POST /api/users/register` (public) → creates a **new `businesses` row + its first `users` row** (`SUPER_ADMIN`, `AccountType.SELF_SERVICE`) + `business_banks` row (bank fields optional, empty by default). **No** `business_qris` row (QRIS optional — added later via upload). Accepts optional **`size`** + **`industry`** (validated + defaulted against the configurable `BusinessOptions` = `app.business.sizes`/`industries`; 400 on an invalid value) and an optional **`storePhoto`** (base64 ≤1 MB, same spec as the picture). These three are **set once at registration and immutable** — `PATCH /api/users/me/business` does **not** touch them, and they're returned only on login (not `/me`).
- `MemberService` (super-admin-gated): `POST /api/users/members` adds a `MEMBER` to the caller's business (rejected once the business hits `app.business.max-users` active users, default 10, super admin included); `PATCH`/`DELETE .../members/{id}` update/soft-delete a member; `GET .../members` lists them (any member). A `SUPER_ADMIN` can never be modified or deleted here. **Delete is a soft delete** (`users.deleted_at` set) that **releases the username**: uniqueness is enforced only among active users (app check + a Postgres partial unique index `WHERE deleted_at IS NULL`), and the member's `sessions` are removed so a reused username starts clean.

`DataSeeder` also creates a seed `businesses` + `SUPER_ADMIN`/`MANAGED` seed user at startup when `SEED_PASSWORD` is set — but seed data (including `seed-qris.b64`) is for the seed/test user only and must never back any other feature.

### Bulk transaction sync (offline)

`POST /api/transactions/bulk` (`TransactionService.bulkCreate`) lets the client push a batch of manually-entered transactions accumulated while offline. Key design points:
- **Per-item idempotency** — each item carries its own client-generated `idempotencyKey` (not the request-level `Idempotency-Key` header), so re-sending the whole batch (or a partial batch after a dropped connection) never duplicates.
- **Partial success** — the method is deliberately **not** `@Transactional`; each item is validated individually (via an injected `jakarta.validation.Validator`, since the request DTO does not cascade-validate its list elements) and saved in its own transaction. One malformed/duplicate row can't roll back or block the rest.
- **Per-item result** — returns `BulkTransactionResultResponseDto` with counts + a `results` list of `{idempotencyKey, status (CREATED|DUPLICATE|FAILED), transactionId, error}`. The client matches by `idempotencyKey` to mark local rows synced (CREATED/DUPLICATE) or retry/fix (FAILED). A concurrent unique-constraint hit is caught and reported as DUPLICATE.

### Transaction line items (products sold)

A MANUAL/PARSED transaction may optionally carry `items` — a list of `(productId, quantity)` tuples for the products sold. `TransactionService.create` handles them atomically (the method is `@Transactional`, so any failure rolls back the whole transaction):
- **Stock arithmetic is done by the database, never read-modify-write.** Every stock change goes through a conditional update — `ProductRepository.decreaseStock` (`UPDATE … SET stock = stock - :qty WHERE id = :id AND stock >= :qty`, 0 rows = refuse) or `increaseStock`. The services used to read the stock, subtract in Java and save, which let two concurrent sales each read the same figure and each write their own answer: one sale vanished and stock could go negative. `StockConcurrencyTest` holds this guarantee (40 concurrent sales against 20 units → exactly 20 succeed, stock lands on 0; against the old code all 40 "succeeded"). Because the loaded entities are stale after such an update, the ledger's before/after values are read back with `findStockByIdIn` (a JPQL projection, so it bypasses the persistence context) rather than taken from memory.
- **Two-pass, validate-then-apply** — pass 1 resolves every line to one of the merchant's own products and checks `stock >= quantity`; an unknown product, a duplicate product line, or **insufficient stock** throws `IllegalArgumentException` (400) and nothing changes. (Pass 1 produces the *friendly* message; the conditional update above is what makes it safe when a concurrent sale takes the stock in between.) Pass 2 reduces each product's `stock`, saves a `transaction_items` row (snapshotting `sku`/`name`/`unitPrice` = the product's current selling price, plus `subtotal`), and records a **SALE** `StockMutation` (via `StockMutationService.recordStockChange`) so the stock ledger stays complete.
- `transaction_items.transaction_id` → `transactions.id`; `product_id` is a plain reference (no FK) so history survives a later product edit/delete. When items are present the transaction **`amount` is derived** from the sum of line subtotals (the client-sent amount is overridden), so the total can never disagree with its items.
- **Reading them back:** the history list (`GET /api/transactions`) omits items to keep the payload small; `GET /api/transactions/{transactionId}` returns the transaction **with** its `items`. `TransactionResponseDto.items` is `@JsonInclude(NON_NULL)`, so it's absent on the list and present on the detail. `PATCH` returns the transaction with its (post-edit) items.
- **Editing items** (`PATCH /api/transactions/{transactionId}` with `items`) is a **full replacement**, handled by `TransactionService.reconcileItems`: it diffs the new items against the existing ones and adjusts stock by the **per-product delta** — an increased/added line draws more stock down (validated, same 400s as create), a reduced/removed line **returns** stock. Atomic two-pass; one **SALE** mutation records the net changes (note `"Sale edit …"`). A non-empty list **re-derives `amount`**; an empty list `[]` clears all items and returns their stock (amount keeps the scalar-updated value); `items: null` leaves the items untouched. A removed line whose product was since deleted is dropped without returning stock.
- Bulk sync (`POST /api/transactions/bulk`) does **not** process items (offline stock validation is out of scope) — items on a bulk item are ignored.

### Transaction soft delete

`transactions.deleted_at = NULL` → active; non-null → deleted. `DELETE /api/transactions/{transactionId}` sets the timestamp; the row is never removed. History/edit/delete queries filter on `deleted_at IS NULL`, but `existsByTransactionId` and the idempotency-key replay lookup deliberately include deleted rows — the DB unique constraints span all rows, so a deleted `transaction_id`/`idempotency_key` still can't be reused.

**Stock return on delete:** if the deleted transaction has line items, `TransactionService.restoreStockFromItems` credits each item's quantity back to its product (best-effort — a product deleted since the sale is skipped) and records one **SALE** reversal `StockMutation` (note `"Sale delete …"`). The `transaction_items` rows are kept (the soft-deleted transaction is the historical record); stock is returned exactly once — a deleted transaction can't be deleted again (the `deleted_at IS NULL` lookup 404s), so there's no double-return.

**Delete is super-admin-only:** `TransactionService.delete` resolves the business via `BusinessContext.requireSuperAdminBusinessId` (not `requireBusinessId`), so a sub-account (MEMBER) trying to delete gets **403** — only the super admin can remove transactions. (Create/edit stay open to any member.)

### Transaction author audit (`created_by` / `updated_by`)

MANUAL/PARSED transactions snapshot **who touched them**: `transactions.created_by` = the username of the account/sub-account that entered it (set in `buildTransaction`, so both single-`create` and `bulkCreate` populate it), and `updated_by` = the username that last edited it (stamped in `update`, null until the first edit). Both are `VARCHAR(50)`, both **null on a MANAGED callback row** (no human author). They ride on `TransactionResponseDto` (`@JsonInclude(NON_NULL)`) so the history list (`GET /api/transactions`) and detail surface `createdBy`/`updatedBy`, with `updatedBy` simply absent until an edit. Username is a snapshot (like `presences`/`ad_deliveries`), so it survives a later rename/soft-delete of the author.

### PARSED transaction push (routed by who detected it)

When a **PARSED** transaction is created (a real-time payment an app detected off a QRIS merchant notification and self-reported), `TransactionService.notifyParsedTransaction` pushes a `TransactionPushPayload` (data-only, Android HIGH).

**The reporting DEVICE is excluded — not the reporting account.** That device announced the payment locally before calling the API, so pushing it back is pure echo; but the same merchant is often signed in on a second phone or a counter tablet, and those have heard nothing. Excluding by username silenced every device they own, which is why the exclusion is per-device.

The device is identified by the **`X-Device-Id`** header on `POST /api/transactions` (the app's `ANDROID_ID`, the same value it sends as `deviceId` at login and which lands in `sessions.device_id`). The header is **optional**: an app build that predates it sends nothing, `excludeDeviceId` is null, and nobody is filtered — i.e. the reporter gets its own echo back, exactly as before. So the server can ship ahead of the app.

Recipients (usernames):

- the business **super admin** — always (merchant oversight of every sale);
- every `BusinessRole.MEMBER` **without** their own `user_qris` (the shared-QRIS cohort), regardless of who reported it. A member **with** their own `user_qris` runs a separate merchant app and sees its own payments there, so they are never in this cohort;
- **the reporter**, whoever they are (`recipients.add(creator)`). "It already played the sound" is true of one *device*, not an account — the merchant's other phones and tablets have announced nothing. Without this a member with their own QRIS was excluded from their own sale, being in neither group above.

Those usernames are then resolved to devices and the reporting one is dropped: `PushNotificationService.sendToUsernames(usernames, payload, excludeDeviceId)` filters `PushRecipient.deviceId()`. The two-arg overload (no exclusion) is what `QrisCallbackService` still uses — a **MANAGED** callback is learned by the server first, so everyone must be told, super admin included.

Recipients are resolved synchronously (fast DB reads) but the send is **deferred to after commit** (`TransactionSynchronization.afterCommit`, the presence/ads pattern) so a rollback never buzzes a phone, and it's **best-effort** — a send failure is logged, never rethrown, and never fails the transaction. **Only the single `POST /api/transactions` path** does this; `bulkCreate` (offline catch-up) deliberately does not — a burst of pushes for stale entries would be wrong.

### QRIS payment callback

`POST /snap/v1.0/qr/qr-mpm-notify` (`QrisCallbackController` → `QrisCallbackService`) receives SNAP-like QR MPM notifications from the QRIS merchant service (AstraPay), unauthenticated (server-to-server).

The real payload puts the useful fields under `additionalInfo`: `terminalId`, `merchantPan` (there's **no NMID**), `customerPan`, `transactionId`. The service:

1. **Routes to a business by `merchantPan`** (`BusinessRepository.findFirstByMerchantPan`, matched against `businesses.merchant_pan`); if it matches nothing it falls back to the configured default account (`app.callback.default-username`, default `admin` → its business). It records a `MANAGED`/`AstraPay` transaction against that business (generated `transactionId`, raw payload → `transactions.info`).
2. **Routes the push by `terminalId`** (`resolveRecipients`): the **super admin** always gets it; a member with **no** per-user QRIS gets it (they use the shared business QRIS); a member **with** a `user_qris` gets it only when their `terminalId` matches the callback's. A callback with no `terminalId` goes to the whole business. The chosen recipients are pushed via `PushNotificationService.sendToUsernames`. Best-effort — a push failure never fails the callback.

**Idempotent on the provider's reference.** The dedupe key is `additionalInfo.transactionId`, falling back to `originalReferenceNo`, stored in `transactions.idempotency_key`. A gateway retries a notification on any timeout or non-2xx, and without this every retry recorded the payment again and inflated the merchant's totals. A re-delivery is looked up first and acknowledged with the original row's response; a *simultaneous* re-delivery can still slip past that lookup and hit the unique constraint, which surfaces as a 409 — the provider's next retry then finds the committed row and gets a normal 200, so a duplicate never becomes a second transaction. A payload carrying neither reference logs a warning and is recorded without dedupe.

**Still PoC where noted:** `parseTimestamp` is commented out so `transaction_date` is the server's `now()` rather than the payload's `X-TIMESTAMP` — a deliberate choice (the server clock is trusted over the provider's). DTO validation stays lenient.

FCM sending lives in `PushNotificationService` (shared). **Every message is data-only** — no FCM `notification` block anywhere, so the app always gets `onMessageReceived` and renders the push itself; `data.type` (`PushType`) says which kind it is.

Transaction push copy renders the amount through `shared/util/Rupiah.format` — Indonesian currency (`Rp 1.000.000`, `.` grouping, `,` for decimals, decimals only when non-zero, never padded to `,00`). It goes in `data.body` only; **no `notification` block is ever added** (see `PushJobSender.buildMessage`, which only calls `putAllData`), since that would stop `onMessageReceived` firing on a backgrounded app and the payment would not be announced aloud. `transactions.amount` is a `BIGINT` of whole rupiah, so the decimal branch is currently unreachable but is implemented + tested.

**Push payloads are polymorphic** (`push/` package): every send takes a `PushPayload` — never a raw map — so each kind owns both its data shape and whether it needs high-priority delivery, and adding a push type is a new class, not another hand-built map at a call site. `PushPayload.toData()` yields the wire map; `PushPayload.urgent()` picks the priority. Implementations: `TransactionPushPayload` + `PresencePushPayload` + `AdsPushPayload` (all urgent → Android HIGH, so the app wakes at once and runs its announcement even backgrounded), `SimplePushPayload` (the ad-hoc test-notification payload). Because priority comes from the payload, the fan-out methods are just addressing: `sendToToken` (one device, propagates errors — `POST /api/notifications/test`), `sendToUser` (all a user's sessions — `POST /api/notifications/user`, presence), `sendToDevice` (one `(username, deviceId)` session — the ads flow), `sendToBusiness` (every device of every active user in a business), and `sendToUsernames` (a computed recipient set — the payment callback, keyed by QRIS terminal). The fan-out methods are best-effort and never throw. `NotificationService` (the `/api/notifications/*` endpoints) only sends a push; it does not persist a transaction.

**Delivery queue (`push/` package) — producer / queue / consumer, swappable for Redis/Kafka later:** the fan-out (producer) resolves *which devices* to notify on the request/callback thread (fast DB reads), then enqueues a **`PushJob`** per device — plain serializable data (`token`, `data` map, `urgent`, `sessionId`, …), **not a closure** — via the **`PushDeliveryQueue`** interface. The **actual FCM send** (the slow network call) runs later on the consumer, **`PushJobSender.deliver(job)`**. The only implementation today is **`InMemoryPushDeliveryQueue`** (a bounded worker pool, `app.push.queue.threads`/`capacity`, default 4 / 1000); because jobs are data and the seam is an interface, a Redis/Kafka implementation drops in by publishing the job + a consumer that calls the same `PushJobSender`. Fan-out methods return the number **queued** (not delivered). Back-pressure is `CallerRunsPolicy` — a full queue runs the send on the caller rather than dropping it; the in-memory queue loses anything still queued at restart (best-effort). Stale-token cleanup runs on the consumer via `PushTokenMaintenance` (a separate `@Transactional` bean so the proxy applies) with a **race-safe conditional update** (`clearPushTokenIfMatches` — only nulls the token if it's still the one FCM rejected, so a concurrent re-login isn't clobbered). `PushNotificationService.sendToToken` (test endpoint) still sends synchronously via `PushJobSender.sendSync`.

**Stale-token auto-cleanup:** when FCM returns `UNREGISTERED` ("Requested entity was not found" — app uninstalled / token rotated) or `SENDER_ID_MISMATCH`, the dead token is cleared from its `sessions` row so it isn't retried. The classifier is `PushNotificationService.isTokenUnregistered(MessagingErrorCode)`; both the fan-out methods (per failing session) and `NotificationService` (the test endpoint, then rethrows) apply it. Transient/payload errors (`UNAVAILABLE`, `INVALID_ARGUMENT`, …) never delete the token.

### QRIS model

**QRIS is a code, not an image.** The app decodes the merchant's QR once and sends the **EMV payload string**; the server stores and serves that, and the app re-renders the QR — a few hundred bytes instead of a multi-MB base64 image on every read.

**Merchant identity comes from the code, not from business info.** `QrisCodeParser` reads four things out of the code at upload — the merchant name (tag `59`), the **NMID** (sub-tag `02` of the merchant-account template whose GUID is `ID.CO.QRIS.WWW` — found by GUID, not by assuming tag 51), and the **acquirer** (the GUID of the *other* merchant-account template, e.g. `ID.CO.ASTRAPAY.WWW`, kept raw since mapping GUIDs to brand names is presentation), and the **terminal id** (sub-tag `07` of tag `62`, the Additional Data template — null when the code carries no terminal label) — and all four are stored on `business_qris` / `user_qris` alongside the code. The app captions the QR with them, so the caption always describes the QR on screen: a sub-account with its own QRIS shows its own identity, not the business's. NMID is therefore **per-QRIS, not per-business** — `PATCH /api/users/me/business` does not accept `nmid` (`businesses.nmid` remains, still set at registration/upgrade because the QRIS callback routes on it, but it is no longer user-editable **and no longer returned by login or `/me`** — the app reads the NMID from `GET /api/qris` so it always matches the QR on screen). Parsing is lenient and never throws, since a code with a failing CRC is still accepted.

**The code is opaque to the server** — stored and served verbatim, never parsed or normalised, so the app's re-encoded QR scans identically to the merchant's original. `QrisService.validateQrisCode` rejects only blank, over-1024-char, or control-character input. It deliberately **allows non-ASCII** (EMVCo tag `64` carries name/city in a local script; the earlier ASCII-only check would have locked out such a merchant). It also verifies the EMV checksum via `crcMatches`/`crc16CcittFalse` — **CRC-16/CCITT-FALSE** over everything up to and including the trailing `6304` — but only **logs** a mismatch rather than rejecting, so an unfamiliar acquirer isn't blocked. `QrisServiceTest` pins all of this against a real AstraPay payload (`…63042C15`).

**`business_qris` and `user_qris` are deliberately identical in shape** — `user_qris` only adds `username` — so the shared QRIS and a per-user one behave the same everywhere. Both hold `qris_code` (the EMV payload), `terminal_id`, and the `merchant_name`/`nmid`/`acquirer` parsed from that code, plus `created_at`/`updated_at`. The column, the Java field and the API field are all called `qrisCode`.

Pre-switch rows held a base64 image in that column; they were converted to codes and had their merchant identity backfilled in one-time passes at rollout, so the server has a single shape to serve and no legacy fallback.

QRIS is optional: registration creates no `business_qris` row, so `GET /api/qris` returns `404` until the user supplies one. `POST /api/qris/upload` creates the row on first upload and re-uploading replaces both the code and the identity parsed from it. There is no reset and no managed/initial fallback — a row always carries a real code, so the response has no `type` discriminator.

**`hasOwnQris` (login + `/me`)** — `QrisService.hasOwnQris(user)`, the single source of truth, tells the app whether this account **monitors a QRIS of its own** (runs a QRIS merchant app and sees its own payment notifications), which is what gates its "Bunyi Uang Masuk dari Aplikasi" toggle. **Super admin:** true once the business has a QRIS at all (`business_qris` exists — registration creates none, so false until the first upload), since the shared business QRIS is theirs to monitor; also true if they were additionally given their own `user_qris`. **Member:** true only with their own `user_qris`; a member sharing the business QRIS is false, because the super admin is the one monitoring it. Note this is *not* the same predicate as the PARSED-push routing above, which asks "is this **member** in `user_qris`?" of each candidate recipient — it no longer asks anything about the creator.

**Per-user QRIS (`user_qris`, `QrisController`/`UserQrisService`):** the **main account** (super admin) can give any user in its business their own QRIS code; its **terminal id is parsed from that code** (tag `62`/`07`), not typed, so it can never disagree with the QR — used so a payment callback routes by terminal (below). Managed via `PUT`/`GET`/`DELETE /api/qris/users/{id}` (setting is super-admin **and** team-management gated; reading/removing is super-admin only). One row per user; `terminal_id` unique within the business (`uq_user_qris_terminal` → 400 `"Terminal ID sudah digunakan"`); a code with no terminal label stores null, and several users may sit at null since they simply are not terminal-routed. **`GET /api/qris` returns the caller's effective QRIS** — their `user_qris` code if assigned, otherwise the shared `business_qris`. A user without a `user_qris` row falls back to the business QRIS and is notified for every callback of the merchant.

### Products & inventory

Basic per-business SKU catalog with simple inventory, scoped by `business_id` (resolved from the authenticated username via `BusinessContext`):
- `ProductCategoryController` → `ProductCategoryService` — categories (`product_categories`); names unique per merchant. Deleting a category first **un-categorizes** its products (`ProductRepository.clearCategory` sets their `category_id` to null in the service, so behavior matches under both Flyway/Postgres and the H2 test schema) then removes the row — products are never deleted with it.
- `ProductController` → `ProductService` — products (`products`); `sku` unique per merchant. Editable fields include an optional `shortDescription`. `stock` is a plain column (inventory management = `PATCH` the value; no ledger). A product's `categoryId` must belong to the same merchant (else 400). `photo` is an optional base64 image validated ≤500 KB decoded; **list responses omit the photo** to keep payloads small, the single-product detail includes it. Prices are `BIGINT` IDR; `costPrice` is optional.
- **Stock opname** — `POST /api/stock-mutations/adjustments` (`ProductService.adjustStock`) bulk-sets several products' stock to their newly counted quantities. Atomic and two-pass (unknown product → 400, nothing changes); returns a per-product variance report (`previousStock`/`newStock`/`difference`). No ledger is persisted.

### Stock mutations (repurposed "purchases")

`StockMutationController` → `StockMutationService` back a unified **stock-change history** in the `stock_mutations` + `stock_mutation_items` tables. Every recorded stock change is a mutation with a `type` (`StockMutationType`): `PURCHASE` (restock), `ADJUSTMENT` (stock opname), `UPDATE` (a product edit that changed stock). Each line snapshots the product (`sku`/`name`) plus `previous_stock`/`new_stock`/`quantity_change`; `unit_price`/`subtotal` are only set for PURCHASE. `stock_mutation_items.product_id` has no FK, so history survives a product edit/delete.

- `POST /api/stock-mutations` — records a **PURCHASE** (restock): atomic two-pass (unknown product → 400, nothing changes), raises each product's `stock`, `total_amount` = Σ subtotals (unit price = provided, else `costPrice`, else 0).
- **ADJUSTMENT** and **UPDATE** mutations are written by `ProductService` after it applies the change: `adjustStock` (stock opname) records one ADJUSTMENT mutation; `update` records an UPDATE mutation only when the edit actually changed `stock`. Both call `StockMutationService.recordStockChange`.
- `GET /api/stock-mutations` lists all mutations (newest first) **with their line items** (batched in one query, no N+1); `GET /api/stock-mutations/{id}` returns a single mutation with its items.

### Account settings

`AccountSettingsController` → `AccountSettingsService` manage a per-**business** `account_settings` row (one per `business_id`, created lazily on first update, shared by all the business's users) holding the settings synced from the mobile client:
- **Monitored apps** — a `packageName → issuer` map (mirrors the Android `notifAppList` pref), stored as JSON via `StringMapJsonConverter`.
- **Notification keywords** — `includeKeywords` (whitelist) + `excludeKeywords` (blacklist) string lists (JSON via `StringListJsonConverter`) plus `blacklistEnabled` (default true) / `whitelistEnabled` (default false) toggles.

- **Config toggles** — `qrisWithProfilePicture` (compose the QRIS image onto the profile picture), `inventoryEnabled` (enable inventory management), `receiveAds` (opt in to voice ads), and `teamManagementEnabled` (activate employee accounts + presence), all boolean columns defaulting false, all set through **one generic endpoint** `PUT /api/account-settings/toggle` (`{setting, enabled}`; unknown setting → 400) rather than a `PUT` per flag. `receiveAds` + `teamManagementEnabled` are bundled into the login + `/me` `accountSettings` so the client knows the account's ad preference and team status (super-admin-set). **`teamManagementEnabled` is effective**: the response/gate read it as the stored flag **OR** the account already has >1 active user, so an existing team is never locked out (`AccountSettingsService.isTeamManagementEnabled`).

**Team-management gate:** when it's off, all the team-feature writes are rejected with **403 `"Fitur manajemen tim belum diaktifkan"`** via `AccountSettingsService.requireTeamManagementEnabled(businessId)` — member **add/update/delete** (`MemberService.requireSuperAdmin`, the single choke point; `list` is not gated) and presence **clock-in/out, ping, respond** (`PresenceService`). So a fresh solo account must first enable `teamManagement` (via the toggle) before it can add its first employee. The presence-write gate is defensive — since the flag is effectively on whenever members exist, it's practically unreachable, but it keeps the rule uniform.

**Authorization:** reading (`GET /api/account-settings`) is open to any member, but **all mutations are super-admin-only** — the three `PUT`s go through `AccountSettingsService.loadOrCreate`, which calls `BusinessContext.requireSuperAdminBusinessId` (403 `ForbiddenException` for a plain member). The same gate protects the QRIS (`POST /api/qris/upload`), the business picture (`PUT`/`DELETE /api/business/picture`), and the shared **business info + bank edits** (`PATCH` on `/api/users/me/business` + `/api/users/me/bank` — all super-admin-only; a plain member gets `403`). Per-user profile edits (name/email/phone, password) stay open to the member themselves.

`GET /api/account-settings` returns defaults (empty lists/map, blacklist on, whitelist off, toggles off) when no row exists yet — it never 404s. `PUT /api/account-settings/monitored-apps` replaces the whole map; `PUT /api/account-settings/keywords` is a partial update (null field = unchanged, empty list = cleared). The service validates and normalizes input — package names match `[A-Za-z0-9_.]+`, issuers/keywords are trimmed + lowercased, keyword lists deduped, with size caps — throwing `IllegalArgumentException` (400) on bad input.

### Employee daily presence

`PresenceController` → `PresenceService` back employee attendance in the `presences` table. Three entry kinds share the table, distinguished by `type` (`PresenceType`): `CLOCK_IN`, `CLOCK_OUT`, `PING`.

- **Clock in/out** (`POST /api/presences/clock-in` · `/clock-out`) — **members only**; a super admin gets 403 (they don't take attendance). Body requires `latitude`/`longitude`/`clockDatetime` (+ optional `note`). The time is **client-supplied** (an offline entry keeps its real time when it syncs) but a `clockDatetime` more than a day ahead is rejected (400) as a wrong/forged device clock.
- **Once per day** — a member may clock in **at most once** and clock out **at most once** per calendar day; a repeat is a 400 (`"Kamu sudah absen masuk hari ini"`). Enforced twice: `PresenceService` checks first (the friendly path), and the partial unique index `uq_presence_clock_daily (user_id, type, (CAST(clock_datetime AS DATE))) WHERE type IN ('CLOCK_IN','CLOCK_OUT')` settles a race between two simultaneous taps (→ 409 via `GlobalExceptionHandler`). PINGs are excluded from the index — the super admin may ping the same member repeatedly.
- **Clock-out requires that day's clock-in** — you can't leave a shift you never started (400 `"Kamu belum absen masuk hari ini"`). Keyed on the **clock-out's own calendar day**, so a shift never spans midnight: a clock-out at 01:00 belongs to the new day and needs a clock-in on it.
- **Ping** (`POST /api/presences/pings`) — **super admin only**; a spot-check that writes one `PING` row per targeted member and pushes it to **all** of that member's devices. Optional **`userId`** (a single id, not a list) picks the target: **omit it to ping the whole team**, or set it to ping just that member. Only users with `BusinessRole.MEMBER` are ever loaded, so a ping never reaches the **super admin's own devices**; an unknown id, another business's staff, or the super admin's own id is a 400 (400 too if the business has no members yet). Push copy is fixed: `"Ping Absen Bejoo!"` / `"Halo Mas/Mbak Bejo Sekalian. Masih pada Semangaat?"`.
- **Ping only reaches members with a device** — `eligiblePingTargets` drops anyone with no session carrying a push token (`SessionRepository.findUsernamesWithPushToken`). Recording a PING for a device-less member would burn their hour-long cooldown on a message nothing could ever deliver. Same shape as the cooldown: **skipped** on a team-wide ping, a **400** on a targeted one.
- **Ping result** — `POST /api/presences/pings` returns `PingResultResponseDto`: `pinged` / `totalMembers` / `skipped[]` (each with `userId`/`username`/`name` + `reason` = `NO_DEVICE` | `COOLDOWN`) / `presences[]`. A partial ping must never look like a full one. The controller sets **no `message`** — the counts are data, and the app writes its own sentence.
- **Ping cooldown** — a member can be pinged at most **once an hour** (`PresenceService.PING_COOLDOWN`, applied by `applyPingCooldown` against `PresenceRepository.findLastPingAtByUserIds`, one grouped query for the whole team — not an N+1). A **targeted** ping inside the hour is a 400 stating the remaining wait (rounded **up**, so a retry at that minute doesn't just bounce); on a **team-wide** ping the cooling-down members are **skipped** (the response's `count`/`data` says who was actually pinged) so one recently-pinged member can't block the rest — 400 only when that leaves nobody.
- **Ping reply** — a PING row is created with **no location and no note**; both belong to the **member's reply**, not to the admin who sent it. `POST /api/presences/{id}/respond` **requires `latitude`/`longitude`** (a ping is a spot-check — the answer must say where the member is) plus an optional `note`, and stores them on the row for the super admin to read in the history. The ping request itself has no such fields. Respond stamps `response_datetime` — only on their own row, only a `PING`, only **within 24 hours of the ping being sent** (`PING_RESPONSE_WINDOW`; 400 `"…sudah kedaluwarsa…"`). A rolling window, not "the same calendar date" — a ping sent at 23:00 stays answerable through the night instead of dying an hour later at midnight, and only once — a member with the app on several devices can answer from any of them, but the second is told it was already answered. "Only once" is a **conditional update** (`PresenceRepository.markResponded` sets `response_datetime` + `note` + `latitude`/`longitude` `WHERE response_datetime IS NULL` and returns the row count; 0 → 400), not a read-then-write, so two simultaneous taps can't both stamp it — and a late one can't overwrite the first reply's location either.
- **History** (`GET /api/presences`) — newest first, paginated. The **super admin sees the whole business** and may filter to one member with `userId`; a **member is always scoped to their own** entries (passing another user's `userId` is a 403). `year` (+ optional `month`) narrows to that calendar period, ranged over the `presence_date` column; **`month` without `year` means that month of the current year** (no 400). Each entry carries `userActive` — false once the member has been removed from the business (their rows stay in the history; one batched query per page resolves it, no N+1).

**No stored date column:** the calendar day is always `CAST(clock_datetime AS DATE)`, so `presences` has no `presence_date` column — a second copy of the same fact that could drift from it. The month/year filter is now a `clock_datetime` range, the daily-clock rule a unique index on the expression, and `presenceDate` in the API response is **derived** (`Presence.getPresenceDate()` is `@Transient`), so clients see no change.

**Ordering:** every list query sorts by `clock_datetime DESC, id DESC`. The id tiebreaker is load-bearing, not decoration — a team-wide ping writes one row per member with the **identical** `clock_datetime`, and ordering by a non-unique column alone lets the DB return tied rows in any order, so a paged read could repeat or skip an entry across a page boundary.

**Push:** presence pushes go out **data-only at Android priority HIGH** (a `PresencePushPayload`, whose `urgent()` is true, via `PushNotificationService.sendToUser`), and are **deferred to after the transaction commits** (`PresenceService.pushAfterCommit` → `TransactionSynchronization.afterCommit`). Pushing inline would fire while the transaction is still open, so a later rollback (the `uq_presence_clock_daily` index catching a racing double tap, or one member's insert failing mid-ping) would leave a phone buzzing about an entry that no longer exists — and a `presenceId` that 404s when tapped. Sends stay best-effort: a failure is logged, never rethrown. Data-only is deliberate: an FCM `notification` block is drawn by the system tray and **never reaches `onMessageReceived` while the app is backgrounded**, so the app would get no code execution and couldn't auto-show its dialog. Data-only hands every delivery to the app; HIGH priority stops FCM holding it until the device next wakes. The cost is that a **force-stopped** app shows nothing — accepted, since the point is for the app to act. A clock-in/out pushes the **super admin** (`title` = `"<username> telah absen masuk!"` / `"… keluar!"`, `body` = `"Pastikan jadwal absen sesuai prosedur. Sukses terus ya!"`); a ping pushes **each targeted member** (`title` = `"Ping Absen Bejoo!"`, `body` = `"Halo Mas/Mbak Bejo Sekalian. Masih pada Semangaat?"`). Every push is best-effort — FCM being down never fails or rolls back the attendance entry.

**Push payload `type`** (`constant/PushType`) is how the app tells the two kinds apart — **every** push is data-only, so it's the client's only reliable discriminator: `TRANSACTION` (payment callback; carries `transactionId`/`amount`/`status`/…) vs `PRESENCE` (carries `presenceType` = CLOCK_IN/CLOCK_OUT/PING, `presenceId`, `userId`, `username`). Both carry `title`/`body` for the app to render.

`username`/`name` are **snapshotted** on the row and `user_id` carries no FK, so the history survives a member rename or soft-delete.

### Voice-ad campaigns

`AdsCampaignController` (admin) + `AdsDeliveryController` (device) → `AdsCampaignService` deliver a short (≤10s) voice ad from the server to a device and track it through its lifecycle. **Content is split from delivery:** an `ad_campaigns` row is the brand's audio (name/brand + the GCS object + integrity facts); an `ad_deliveries` row is one send to one device, carrying the state machine (`AdCampaignState`) + a timestamp per step. A campaign has exactly one delivery today; the future location-based campaign engine will insert many, so the client never sees "deliveries" as a collection concept — it reports by `deliveryId` (from the push). This is the substrate for a later **brand dashboard**.

- **Storage (GCS).** Audio lives in a locked-down bucket (`app.ads.gcs-bucket`, uniform access + public-access-prevention). `AdsAudioStorage` is the seam: `GcsAdsAudioStorage` (`@Profile("!test")`) uploads and mints short-lived **V4 signed download URLs**; the functional tests bind `InMemoryAdsAudioStorage`. Everything runs as the app's ADC (on Cloud Run, `bejo-cloudrun`, which has `objectAdmin` on the bucket + `serviceAccountTokenCreator` on itself so it can sign without a key file, via IAM `signBlob`). `scripts/init-gcp.sh` provisions the bucket + those grants.
- **Auth.** The admin surface (`/api/ads/campaigns/**`) is **JWT-exempt** (server-to-server) but requires the `X-ADS-ADMIN-TOKEN` header — a dedicated secret (`app.ads.admin-token`), so the shared `X-API-TOKEN` embedded in the app can't drive it. `SecurityConfig` `permitAll`s the path; **`AdsAdminTokenFilter`** does the constant-time check (blank token = feature off → 403) **before Spring MVC validates the body**, so an unauthorized caller gets a 403, not a body-validation 400 that would leak the request shape. The device reports (`/api/ads/deliveries/**`) use the normal JWT and the service checks the caller **owns** the delivery (its `targetUsername`) → 403 otherwise.
- **Validation before upload.** `create` rejects (400, nothing uploaded): a content type outside the supported set (`ALLOWED_CONTENT_TYPES` — MP3/M4A/MP4/OGG/WAV, matching what the duration inspector can read), audio over `app.ads.max-audio-bytes` (2 MB) or `app.ads.max-audio-seconds` (10s), or a target device with no registered FCM token (a push would go nowhere). Duration comes from `AdsAudioInspector` (`JAudioTaggerAudioInspector` — jaudiotagger reads the header only, no full decode; an unreadable file is a 400, not a silent pass). All checks run on the in-memory bytes; **the GCS upload happens last, after the rows are saved**, so a validation failure or DB rollback never leaves an orphaned object.
- **Lifecycle.** `create` (validate + hash + upload + save CREATED) → `prepare` (→ PREPARING, push PREPARE with the signed URL) → the app downloads and reports `downloaded` (integrity matches → **READY**, else **ERROR**) → `play` (requires READY → STARTING, push PLAY) → the app reports `played` (**PLAYED**, or straight to **FINISHED** when the location is included) → the deferred `location` call (foreground-only) does **PLAYED → FINISHED** (step 8). `SCHEDULED` is a reserved enum value (no scheduler yet).
- **Idempotency & pushes.** Every device transition is a **conditional update** (`WHERE state = '…'` → rows-affected; the presence `markResponded` pattern), so a duplicate report from a second device or a retry is a no-op that returns the current state (a genuinely premature report — e.g. `downloaded` while still CREATED — is a 400). Both pushes are **data-only, priority HIGH, sent after commit** (`sendUrgentToDevice` to the one target device), so a rolled-back transition never buzzes a phone; best-effort, a send failure never fails the state change. Integrity check on the download report: the app echoes `sha256` + `sizeBytes` and the server compares them to what it computed at upload.
- **Merchant ad history.** `GET /api/ads/history` (`AdsCampaignService.history`) is the **merchant-facing, JWT** read (the admin surface above is server-to-server, `X-ADS-ADMIN-TOKEN`) — a business's super admin sees every delivery to its own devices and its sub-accounts', paginated, newest first. Scope is the **`business_id` snapshotted on each `ad_deliveries` row** at send time (`findByBusinessIdOrderByIdDesc`, backed by `idx_ad_deliveries_business (business_id, id DESC)`); snapshotting (rather than resolving from `target_username` on read) means a delivery to a member **removed since still shows** — same rationale as `presences` snapshotting identity. **All states** are returned (a merchant sees pending/errored as well as played); each `AdHistoryResponseDto` row flattens the campaign's name/brand (batched in one `findAllById`, no N+1) onto the delivery's status + played/finished time + play location. **Who it went to** is identified by both `targetUsername` **and** `targetName` — snapshotted on the delivery so they read correctly even after the member is removed/renamed — plus `userActive`, resolved on read against the **stable snapshotted `target_user_id`** (`findActiveIdsByIdIn`, one batched query, no N+1; keyed on the id, not the username, so a released-and-reused username can't misattribute) and false once that member has left. Super-admin-gated via the normal JWT: `BusinessContext.requireSuperAdminBusinessId` reads the caller's `business_role` from the DB (not a token claim, so a demotion/soft-delete takes effect immediately) → 403 for a plain member.

### Timezone

The whole app is pinned to **WIB (`Asia/Jakarta`, UTC+7)**: `BejoApplication.main` calls `TimeZone.setDefault(AppTime.ZONE)` before Spring starts, so every `LocalDate/LocalDateTime.now()` (transaction/session times, soft-delete stamps, the summary buckets) is Jakarta wall-clock regardless of the container zone (Cloud Run is UTC). `TIMESTAMP` columns are zoneless wall-clock, so this only affects new `now()` values, never reinterprets stored rows. `util/AppTime.ZONE` is the single source of truth — reference it where the zone must be explicit (e.g. `TransactionService.summary` default, `QrisCallbackService` normalizes an offset-bearing `X-TIMESTAMP` to WIB via `atZoneSameInstant`). Jackson also serializes dates in this zone (`spring.jackson.time-zone`).

### Observability

- `/actuator/health` — liveness/readiness
- `/actuator/prometheus` — Micrometer metrics scraped by Prometheus (container: `bejo-prometheus:9090`)
- Custom counters: `login.attempts{result}`, `transactions.fetched`, `notifications.sent{result}`
- `RequestLoggingFilter` logs `METHOD PATH[?query] STATUS DURATIONms` for every request. On a 4xx/5xx it additionally logs the request body (`[BODY] …`) — secrets (`password`/`token`/`pushToken` fields) masked, single-line, capped at 2 KB, and auth endpoints (`/api/auth/**`, register) skipped entirely. Toggle with `app.request-logging.log-body-on-error` (default `true`) / `app.request-logging.max-body-length` (default `2048`).
- The QRIS callback (`/snap/v1.0/qr/qr-mpm-notify`): on **4xx/5xx** `RequestLoggingFilter` logs `[CALLBACK-REQ]` at WARN with **all headers** (credential headers `Authorization`/`X-API-TOKEN`/`Cookie` masked) + the **full body** (capped 8 KB), buffered up front (`CachedBodyHttpServletRequest`) so it's captured even when validation fails before the body is read. A **successful** callback is not dumped — `QrisCallbackService` logs one INFO line with the key fields (`[CALLBACK] Recorded txnId=… amount=… status=… merchantPan=… providerTxnId=…`), and the request/routing details at DEBUG. The real payload puts the routing fields under `additionalInfo`: `terminalId`, `merchantPan` (no NMID — so `businesses.merchant_pan` is the callback's routing key), `customerPan`, `transactionId`.
- **FCM errors** (`FirebaseMessagingException`) are logged at WARN with just the `MessagingErrorCode` + message — **never the exception object** — so a routine `UNREGISTERED` (handled by the stale-token cleanup) doesn't spam the logs with a long Firebase stack trace. Applies in `PushNotificationService`, `NotificationService`, and `GlobalExceptionHandler`.
- `GlobalExceptionHandler` logs every 4xx client error at `WARN` (`[400] METHOD /path — reason`), so requests rejected before reaching a service (validation, missing header, unreadable body) are still debuggable in Cloud Run.
- JSON log format active under `SPRING_PROFILES_ACTIVE=prod`

## Database

Schema is created by Flyway (`src/main/resources/db/migration/`) at app startup; `ddl-auto: none`.

**`V1__init_schema.sql` is the deployed baseline and is now FROZEN.** It was rebuilt from scratch on 2026-07-31 (the database was dropped and recreated, wiping the previous data) to collapse an earlier V2 back into one readable file. That reset is done.

**From here the schema is versioned forward.** Every subsequent change is a **new** migration — `V2__…`, `V3__…` — that is additive, forward-only, and safe to apply to a populated table (`ALTER TABLE … ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`). **Never edit V1 or any other applied migration**, not even a comment: Flyway checksums the file's bytes against the live database, so any change breaks startup with `Validate failed: Migration checksum mismatch` and the only fixes are dropping the database or a `flyway repair`. Production data is preserved from now on.

Keep the JPA entities in lockstep with the migrations. The H2 test DB builds its tables **from the entities** (`ddl-auto: create-drop`, Flyway disabled), so the test suite cannot catch a drift between an entity and the SQL — booting once against a fresh Postgres with `--spring.jpa.hibernate.ddl-auto=validate` proves they agree, and a new migration should be applied to a throwaway Postgres 17 container (podman) before shipping.

**Schema-per-module + no cross-schema foreign keys.** The tables live in **three Postgres schemas** matching the code modules — `core` (businesses, users, banks, QRIS, sessions, account-settings, presences, token-blacklist), `commerce` (transactions, transaction_items, products, product_categories, stock_mutations, stock_mutation_items), `ads` (ad_campaigns, ad_deliveries) — created by `V1` (`CREATE SCHEMA IF NOT EXISTS …`). Entities carry `@Table(schema = "…")`; all queries are JPQL so Hibernate schema-qualifies automatically (no native SQL in main). A commerce/ads table **never FK-references a core table**: `transactions`/`products`/`product_categories`/`stock_mutations` hold `business_id` as a **plain soft reference** to `core.businesses(id)` (like `presences`/`ad_deliveries`), not an FK — every surviving FK is intra-schema (`products.category_id`, `transaction_items.transaction_id`, `stock_mutation_items.mutation_id`, `ad_deliveries.campaign_id`, and core's `*_business` FKs). The invariant is enforced in code — `business_id` is always assigned server-side from `BusinessContext`, never from the client, and businesses are never hard-deleted — with an orphan-detection safety net (`scripts/check-orphans.sql`, exercised on CI by `OrphanDetectionTest`). The H2 test DB pre-creates the schemas via the JDBC `INIT` clause (`application-test.yml`); Flyway keeps its history table in the default schema.

Key tables:
- `businesses` — the merchant; business name/address + **merchant_pan** + `size`/`industry` (NOT NULL, immutable, config-validated) + optional `store_photo` + shared `picture` (base64 TEXT, ≤1 MB decoded); partial-unique `merchant_pan` (`WHERE … IS NOT NULL`). **`merchant_pan`** is the QRIS merchant PAN carried by the payment callback (no NMID in the payload) — it's how a callback routes to a business; set optionally at registration
- `users` — credentials + profile (name/email/phone); `business_id` FK; `business_role` (`SUPER_ADMIN`/`MEMBER`); `account_type` (`MANAGED`/`SELF_SERVICE`); soft delete via `deleted_at` (username unique only among active users — partial index `uq_users_username_active`)
- `business_banks` / `business_qris` / `account_settings` — one row per `business_id` (unique FK); account settings = monitored-apps map + keyword filters (JSON) + blacklist/whitelist + toggles (`qris_with_profile_picture`, `inventory_enabled`, `receive_ads`, `team_management_enabled`)
- `user_qris` — per-user QRIS (main-account-set): `username` + `terminal_id` + `qris_code` + parsed `merchant_name`/`nmid`/`acquirer`, scoped by `business_id`; unique `(business_id, username)` and `(business_id, terminal_id)`; the payment callback routes its push by matching `terminal_id`
- `sessions` — one row per `(username, device_id)`; holds FCM push token, schedule window (no FK — username uniqueness is partial)
- `transactions` — the transaction ledger, scoped by `business_id`; unique on `transaction_id` and `idempotency_key`; soft delete via `deleted_at`; `created_by`/`updated_by` (VARCHAR(50)) snapshot the account/sub-account that created/last-edited a MANUAL/PARSED row (null for MANAGED); general-purpose `info` (TEXT, e.g. raw callback JSON — the provider's `additionalInfo` payload is stored here under the shorter name)
- `transaction_items` — optional line items of a sale; `transaction_id` FK → `transactions(id)`; snapshots `sku`/`name`/`quantity`/`unit_price`/`subtotal`; `product_id` is a plain reference (no FK)
- `token_blacklist` — logged-out JWT jtis, purged hourly after expiry
- `product_categories` — per-business categories; name unique per `business_id`
- `products` — per-business SKUs; `sku` unique per `business_id`; optional `short_description`; nullable `category_id` FK; `stock` (INTEGER), `price`/`cost_price` (BIGINT IDR), optional base64 `photo` (TEXT, ≤500 KB decoded)
- `stock_mutations` / `stock_mutation_items` — per-business stock-change history (type `PURCHASE`/`ADJUSTMENT`/`UPDATE`/`SALE`); each line snapshots the product + `previous_stock`/`new_stock`/`quantity_change`, with `unit_price`/`subtotal` for purchases; `total_amount` is the purchase sum
- `ad_campaigns` / `ad_deliveries` — voice-ad campaigns. A campaign is the brand's audio (GCS `audio_object` + `audio_sha256`/`audio_size_bytes` for the integrity check); a delivery is one send to one device (`target_username`/`target_device_id`, no FK — same rationale as `presences`) plus a snapshot of the target at send time — `business_id` (the merchant ad-history scope), `target_user_id` (stable id → resolves `userActive`), and `target_name` — so the history shows who the ad went to and their business even after the member is removed/renamed, carrying the lifecycle `state` (`AdCampaignState`) + a timestamp per step (`prepared_at`/`ready_at`/`started_at`/`played_at`/`finished_at`/`errored_at`) and the reported play location
- `presences` — employee attendance, scoped by `business_id`; type `CLOCK_IN`/`CLOCK_OUT`/`PING`; `clock_datetime` (the calendar day is derived from it, never stored), `latitude`/`longitude` (where the member clocked in/out — on a PING, **null until they respond**, then where they were when they answered), nullable `response_datetime` (a PING's acknowledgment); `note` = the member's own text — what they typed on a clock-in/out, or their **reply** to a PING (both written by `respond`, not by the admin who sent the ping); `username`/`name` snapshotted and `user_id` unconstrained (no FK) so history survives a member rename/soft-delete; partial unique index `uq_presence_clock_daily` on `(user_id, type, CAST(clock_datetime AS DATE))` caps clock-ins/outs at one per member per day

`username` is **`VARCHAR(50)` everywhere** it's stored (`users`, `sessions`, `presences`) and capped at 50 by the register/add-member DTOs; the entities declare `@Column(length = 50)` so the H2 test schema matches Postgres.

## Tests

- `src/test/java/com/bejo/service/*Test.java` — Mockito unit tests, no Spring context.
- `src/test/java/com/bejo/web/ApiIntegrationTest.java` — full-stack functional tests via `@SpringBootTest` + MockMvc against in-memory H2 (PostgreSQL mode); Flyway disabled, schema from `ddl-auto: create-drop` (`src/test/resources/application-test.yml`, profile `test`).
