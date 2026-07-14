# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Working agreement

**Never `git commit` or `git push` automatically after making changes.** Make the edits, run the tests, then stop and report. Only commit and push when the user explicitly confirms/asks for it. This also gates the `release`-branch deploy (a push to `release` deploys to production), so it must always be an explicit, confirmed action.

## What this project is

A Java 25 / Spring Boot REST API for QRIS merchants: registration and JWT login (against its own database), transaction history and manual entry, QRIS image management, bank/business profile management, FCM token management, and push notification delivery. Originally inspired by the Node.js project at `/Users/anton/Documents/Projects/cuan` but uses local DB authentication instead of scraping an external gateway.

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
| `BUSINESS_MAX_USERS` | Max users per business, incl. the super admin (`app.business.max-users`, default `5`) |
| `BUSINESS_SIZES` / `BUSINESS_DEFAULT_SIZE` | Allowed business sizes + the default (comma-separated; `app.business.sizes`) |
| `BUSINESS_INDUSTRIES` / `BUSINESS_DEFAULT_INDUSTRY` | Allowed industries + the default (comma-separated; `app.business.industries`) |
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

The JWT is returned by the login endpoint in `data.token`. `JwtAuthFilter` validates signature + expiry, rejects blacklisted (logged-out) tokens, **and rejects tokens whose user was soft-deleted** (`existsByUsernameAndDeletedAtIsNull` — a deleted member's still-signed token stops authenticating immediately, and its released username can't be impersonated); controllers resolve the username via Spring's `Authentication` principal.

`/actuator/health` (Cloud Run probes) and `/snap/**` (inbound QRIS payment callbacks) are exempt from both checks. Other `/actuator/*` endpoints (metrics, prometheus, info) require `X-API-TOKEN` but not a JWT — so `/actuator/prometheus` is **not** publicly reachable on Cloud Run. The local Prometheus scraper sends the token via `http_headers` (see `docker/prometheus.yml` + the `prometheus_api_token` compose config). Responses include HSTS + hardening headers (`SecurityConfig.headers`); `server.forward-headers-strategy: framework` lets Spring see the Cloud Run proxy's HTTPS scheme so HSTS is emitted.

## API endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/snap/v1.0/qr/qr-mpm-notify` | None | Inbound QRIS payment callback (SNAP QR MPM notify); records a MANAGED transaction, stores raw payload in `info` |
| `POST` | `/api/users/register` | Public | Self-service registration; creates a **new business** + its first user (the `SUPER_ADMIN`) + bank (no QRIS row — QRIS is optional) |
| `GET` | `/api/users/me` | JWT | The caller's own account bundle (`MeResponseDto`: id + profile + role + shared business/bank/picture/app-settings) — same shape as login minus the token, for refreshing state |
| `GET` | `/api/users/members` | JWT | List the users of the caller's business (any member) |
| `POST` | `/api/users/members` | JWT (super admin) | Add a user to the caller's business as a `MEMBER` |
| `PATCH` | `/api/users/members/{id}` | JWT (super admin) | Update a member (name/email/phone/password; a super admin can't be modified here) |
| `DELETE` | `/api/users/members/{id}` | JWT (super admin) | Soft-delete a member (releases its username); a super admin can't be deleted |
| `POST` | `/api/auth/login` | Public | Validate credentials, upsert session, returns JWT |
| `POST` | `/api/auth/logout` | JWT | Blacklist the current token (jti); optional body `{deviceId}` clears that device's FCM token |
| `GET` | `/api/transactions` | JWT | Transaction history; optional `dateStart`/`dateEnd` (ISO date, inclusive); paginated (`page`/`size`) |
| `GET` | `/api/transactions/summary` | JWT | Dashboard totals (summed amount + count) for today / yesterday / this month; optional `date` = client's local "today" (defaults to server date) |
| `GET` | `/api/transactions/{transactionId}` | JWT | One transaction **with its sold-product line items** (the history list omits items) |
| `POST` | `/api/transactions` | JWT | Add MANUAL/PARSED transaction; requires `Idempotency-Key` header. Optional `items` (product line items) — validates + reduces stock, records a SALE stock mutation |
| `POST` | `/api/transactions/bulk` | JWT | Bulk-sync MANUAL/PARSED transactions (offline entry); per-item `idempotencyKey`, partial-success result |
| `PATCH` | `/api/transactions/{transactionId}` | JWT | Edit a MANUAL/PARSED transaction (partial update; MANAGED is immutable). Optional `items` fully replaces the line items, reconciling stock by delta + re-deriving amount |
| `DELETE` | `/api/transactions/{transactionId}` | JWT | Soft-delete a transaction (sets `deleted_at`); returns any sold line items' stock to their products |
| `PUT` | `/api/devices/{deviceId}/fcm-token` | JWT | Update FCM push token (scoped to the authenticated user's session) |
| `POST` | `/api/notifications/test` | JWT | **Test:** send an FCM push from the request payload to one device (no DB write) |
| `POST` | `/api/notifications/user` | JWT | **Test/utility:** broadcast an FCM push to **all** the caller's devices (no `deviceId`; via `sendToUser`; no DB write) |
| `GET` | `/api/qris` | JWT | QRIS image; `type` = `UPLOADED` (custom) or `MANAGED` (generated) |
| `POST` | `/api/qris/upload` | JWT (super admin) | Upload base64 QRIS image (max ±5 MB) |
| `POST` | `/api/qris/reset` | JWT (super admin) | Revert to the managed/generated image |
| `PATCH` | `/api/users/me` | JWT | Update name, email, phone |
| `PATCH` | `/api/users/me/password` | JWT | Change password (requires `oldPassword`) |
| `GET`/`PUT`/`DELETE` | `/api/business/picture` | JWT (GET: any member · PUT/DELETE: super admin) | Get/set/remove the **business** picture (base64, ≤1 MB, shared by all members). Response field is `profilePicture` |
| `PATCH` | `/api/users/me/bank` | JWT | Update bank info |
| `POST` | `/api/users/me/bank/reset` | JWT | Reset bank info to initial values |
| `PATCH` | `/api/users/me/business` | JWT | Update business name/address/NMID |
| `POST` | `/api/users/me/business/reset` | JWT | Reset business info to initial values |
| `POST` | `/api/categories` | JWT | Create a product category |
| `GET` | `/api/categories` | JWT | List the merchant's categories |
| `DELETE` | `/api/categories/{id}` | JWT | Delete a category (its products become uncategorized) |
| `POST` | `/api/products` | JWT | Create a product (SKU); optional `categoryId`, `photo` (base64 ≤500 KB) |
| `GET` | `/api/products` | JWT | List products; optional `?categoryId=` filter (omits `photo`); paginated (`page`/`size`) |
| `GET` | `/api/products/{id}` | JWT | Get one product (includes `photo`) |
| `PATCH` | `/api/products/{id}` | JWT | Partial update (name, short description, price, cost, stock, category, photo) |
| `DELETE` | `/api/products/{id}` | JWT | Delete a product (hard) |
| `POST` | `/api/products/stock-adjustments` | JWT | Bulk stock adjustment (stock opname): set several products' stock to counted values; returns a variance report |
| `POST` | `/api/stock-mutations` | JWT | Record a purchase (restock): raises stock of several products at once (a PURCHASE mutation) |
| `GET` | `/api/stock-mutations` | JWT | Stock-change history — mutations (PURCHASE/ADJUSTMENT/UPDATE) with their line items; paginated (`page`/`size`) |
| `GET` | `/api/stock-mutations/{id}` | JWT | Get one stock mutation with its line items |
| `GET` | `/api/app-settings` | JWT | Get the user's app settings (monitored apps + notification keywords); returns defaults if never synced |
| `PUT` | `/api/app-settings/monitored-apps` | JWT (super admin) | Replace the monitored-apps map (`packageName`→`issuer`) |
| `PUT` | `/api/app-settings/keywords` | JWT (super admin) | Update notification keyword filters (include/exclude lists + blacklist/whitelist toggles; partial update) |
| `PUT` | `/api/app-settings/qris-with-profile-picture` | JWT (super admin) | Toggle composing the QRIS image onto the profile picture (`{enabled}`; default false) |
| `PUT` | `/api/app-settings/inventory` | JWT (super admin) | Toggle inventory management (`{enabled}`; default false) |
| `POST` | `/api/presences/clock-in` | JWT (member) | Employee clock-in: `latitude`/`longitude`/`clockDatetime` required, `note` optional; pushes a notification to the super admin |
| `POST` | `/api/presences/clock-out` | JWT (member) | Employee clock-out (same body); pushes a notification to the super admin |
| `POST` | `/api/presences/pings` | JWT (super admin) | Spot-check: records + pushes one PING entry to **every** member (optional `note` = the push body) |
| `POST` | `/api/presences/{id}/respond` | JWT (member) | Acknowledge one's own PING (stamps `responseDatetime`; only once) |
| `GET` | `/api/presences` | JWT | Attendance history; super admin sees the whole business (optional `userId` filter), a member only their own. Optional `year` + `month`; paginated (`page`/`size`) |

All responses follow `{ "status": 1\|0, "message": "...", "data": {...}, "count": N }` — null fields are omitted. Paginated list endpoints (`GET /api/transactions`, `/api/products`, `/api/stock-mutations`) additionally return `page` (1-based), `totalPages`, and `total`; `count` is the size of the current page. Pagination is optional — omit `page`/`size` for page 1 of 50 (`size` cap 200). Built via `util/Pagination.of(page,size)` → `ApiResponseDto.page(Page<T>)`.

Login success response `data` includes profile + `businessRole` + business + bank fields plus `token`, `tokenType: "Bearer"`, `expiresIn` (seconds).

A Postman collection is at `bejo.postman_collection.json`, driven by **Postman environments** — import `bejo.local.postman_environment.json` (Bejo — Local) or `bejo.production.postman_environment.json` (Bejo — Production) and select one; each defines `baseUrl`, `apiToken`, and `token`. The Login request's test script auto-saves the JWT into the active environment's `token` (used by all other requests).

## Architecture

```
controller/   → thin HTTP layer; validation via @Valid
service/      → business logic, metrics via Micrometer Counter
repository/   → Spring Data JPA interfaces
entity/       → JPA entities (Business, User, BusinessBank, BusinessQris,
                BusinessAppSettings, Session, Transaction, TransactionItem, Presence,
                TokenBlacklist)
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

Domain "possible values" live in `constant/` as `public static final String` holders — reference these instead of inline literals: `AccountType` (`MANAGED`, `SELF_SERVICE`), `BusinessRole` (`SUPER_ADMIN`, `MEMBER`), `TransactionType` (`MANUAL`, `PARSED`, `MANAGED`), `TransactionStatus` (`SUCCESS`, `PENDING`, `FAILED`, `REFUNDED`, `CANCELLED`, `EXPIRED`, `UNKNOWN`), `QrisType` (`UPLOADED`, `MANAGED`), `Acquirer` (`CASH`, `ASTRAPAY` defaults), `StockMutationType` (`PURCHASE`, `ADJUSTMENT`, `UPDATE`, `SALE`), `PresenceType` (`CLOCK_IN`, `CLOCK_OUT`, `PING`), `PushType` (`TRANSACTION`, `PRESENCE` — the `type` field in every push's data payload).

Gotcha: `SecurityConfig`'s auth entry point distinguishes 401 vs 404/405 by consulting `RequestMappingHandlerMapping`. It must be injected with `@Qualifier("requestMappingHandlerMapping")` (actuator registers a second bean of that type) and the request path must be parsed via `ServletRequestPathUtils.parseAndCache` because the entry point runs before `DispatcherServlet`.

### Login flow

`AuthService.login()` authenticates against the local `users` table:
1. Look up the **active** `users` row by `username` (`findByUsernameAndDeletedAtIsNull`) — `Invalid credentials` (400) if not found or BCrypt mismatch (a soft-deleted user can't log in).
2. Load the `businesses` + `business_banks` rows (by the user's `business_id`) for business + bank fields in the response.
3. Upsert a row in `bejo_sessions` keyed on `(username, device_id)`; an existing FCM token is preserved when the login request has no `pushToken`.
4. Return `id` + profile + `businessRole` + business + bank + **`size` + `industry` + `storePhoto` + `profilePicture` (business picture) + `appSettings`** + JWT (`expiresIn` seconds). The bundled picture/app-settings save the client round trips on the home screen; the individual endpoints (`/api/app-settings`, `/api/business/picture`, `GET /api/users/me`) still exist. `GET /api/users/me` returns the same bundle minus the token **and minus `size`/`industry`/`storePhoto`** — those are immutable (set once at registration), so the client caches them from the login response.

### Registration, businesses & members

Registration/user-management live in `UserService` + `MemberService` (not `AuthService` — auth is only login/logout/session). Scoping is resolved by `BusinessContext.requireBusinessId(username)` (a `@Component` mapping the authenticated username → `business_id`; throws 404 if the user is gone/soft-deleted).

- `POST /api/users/register` (public) → creates a **new `businesses` row + its first `users` row** (`SUPER_ADMIN`, `AccountType.SELF_SERVICE`) + `business_banks` row (bank fields optional, empty by default). **No** `business_qris` row (QRIS optional — added later via upload). Accepts optional **`size`** + **`industry`** (validated + defaulted against the configurable `BusinessOptions` = `app.business.sizes`/`industries`; 400 on an invalid value) and an optional **`storePhoto`** (base64 ≤1 MB, same spec as the picture). These three are **set once at registration and immutable** — `PATCH /api/users/me/business` does **not** touch them, and they're returned only on login (not `/me`).
- `MemberService` (super-admin-gated): `POST /api/users/members` adds a `MEMBER` to the caller's business (rejected once the business hits `app.business.max-users` active users, default 5, super admin included); `PATCH`/`DELETE .../members/{id}` update/soft-delete a member; `GET .../members` lists them (any member). A `SUPER_ADMIN` can never be modified or deleted here. **Delete is a soft delete** (`users.deleted_at` set) that **releases the username**: uniqueness is enforced only among active users (app check + a Postgres partial unique index `WHERE deleted_at IS NULL`), and the member's `bejo_sessions` are removed so a reused username starts clean.

`DataSeeder` also creates a seed `businesses` + `SUPER_ADMIN`/`MANAGED` seed user at startup when `SEED_PASSWORD` is set — but seed data (including `seed-qris.b64`) is for the seed/test user only and must never back any other feature.

### Bulk transaction sync (offline)

`POST /api/transactions/bulk` (`TransactionService.bulkCreate`) lets the client push a batch of manually-entered transactions accumulated while offline. Key design points:
- **Per-item idempotency** — each item carries its own client-generated `idempotencyKey` (not the request-level `Idempotency-Key` header), so re-sending the whole batch (or a partial batch after a dropped connection) never duplicates.
- **Partial success** — the method is deliberately **not** `@Transactional`; each item is validated individually (via an injected `jakarta.validation.Validator`, since the request DTO does not cascade-validate its list elements) and saved in its own transaction. One malformed/duplicate row can't roll back or block the rest.
- **Per-item result** — returns `BulkTransactionResultResponseDto` with counts + a `results` list of `{idempotencyKey, status (CREATED|DUPLICATE|FAILED), transactionId, error}`. The client matches by `idempotencyKey` to mark local rows synced (CREATED/DUPLICATE) or retry/fix (FAILED). A concurrent unique-constraint hit is caught and reported as DUPLICATE.

### Transaction line items (products sold)

A MANUAL/PARSED transaction may optionally carry `items` — a list of `(productId, quantity)` tuples for the products sold. `TransactionService.create` handles them atomically (the method is `@Transactional`, so any failure rolls back the whole transaction):
- **Two-pass, validate-then-apply** — pass 1 resolves every line to one of the merchant's own products and checks `stock >= quantity`; an unknown product, a duplicate product line, or **insufficient stock** throws `IllegalArgumentException` (400) and nothing changes. Pass 2 reduces each product's `stock`, saves a `transaction_items` row (snapshotting `sku`/`name`/`unitPrice` = the product's current selling price, plus `subtotal`), and records a **SALE** `StockMutation` (via `StockMutationService.recordStockChange`) so the stock ledger stays complete.
- `transaction_items.history_id` → `bejo_history.id`; `product_id` is a plain reference (no FK) so history survives a later product edit/delete. When items are present the transaction **`amount` is derived** from the sum of line subtotals (the client-sent amount is overridden), so the total can never disagree with its items.
- **Reading them back:** the history list (`GET /api/transactions`) omits items to keep the payload small; `GET /api/transactions/{transactionId}` returns the transaction **with** its `items`. `TransactionResponseDto.items` is `@JsonInclude(NON_NULL)`, so it's absent on the list and present on the detail. `PATCH` returns the transaction with its (post-edit) items.
- **Editing items** (`PATCH /api/transactions/{transactionId}` with `items`) is a **full replacement**, handled by `TransactionService.reconcileItems`: it diffs the new items against the existing ones and adjusts stock by the **per-product delta** — an increased/added line draws more stock down (validated, same 400s as create), a reduced/removed line **returns** stock. Atomic two-pass; one **SALE** mutation records the net changes (note `"Sale edit …"`). A non-empty list **re-derives `amount`**; an empty list `[]` clears all items and returns their stock (amount keeps the scalar-updated value); `items: null` leaves the items untouched. A removed line whose product was since deleted is dropped without returning stock.
- Bulk sync (`POST /api/transactions/bulk`) does **not** process items (offline stock validation is out of scope) — items on a bulk item are ignored.

### Transaction soft delete

`bejo_history.deleted_at = NULL` → active; non-null → deleted. `DELETE /api/transactions/{transactionId}` sets the timestamp; the row is never removed. History/edit/delete queries filter on `deleted_at IS NULL`, but `existsByTransactionId` and the idempotency-key replay lookup deliberately include deleted rows — the DB unique constraints span all rows, so a deleted `transaction_id`/`idempotency_key` still can't be reused.

**Stock return on delete:** if the deleted transaction has line items, `TransactionService.restoreStockFromItems` credits each item's quantity back to its product (best-effort — a product deleted since the sale is skipped) and records one **SALE** reversal `StockMutation` (note `"Sale delete …"`). The `transaction_items` rows are kept (the soft-deleted transaction is the historical record); stock is returned exactly once — a deleted transaction can't be deleted again (the `deleted_at IS NULL` lookup 404s), so there's no double-return.

### QRIS payment callback

`POST /snap/v1.0/qr/qr-mpm-notify` (`QrisCallbackController` → `QrisCallbackService`) receives SNAP-like QR MPM notifications from the QRIS merchant service (AstraPay), unauthenticated (server-to-server).

**PoC mode (temporary):** the provider's callback is currently buggy — it only sends a valid amount + status (reference numbers, `merchantPan`, `transactionId` all null). So the DTO validation is lenient (all fields optional), `X-TIMESTAMP` is optional, and the service can't route by NMID or dedupe by reference. Instead, **every callback records a `MANAGED`/`AstraPay` transaction against the seed user's business** (`app.callback.default-username`, default `admin` → its `business_id`) with a generated `transactionId`, null `idempotencyKey` (no dedupe — each callback creates a new row), amount/status from the payload; saves the raw payload JSON to `bejo_history.info`; and sends a best-effort FCM push to **every device of every user in that business** (`PushNotificationService.sendToBusiness`). Returns the callback's own ack shape (`QrMpmNotifyResponse`). **Note:** `parseTimestamp` is currently commented out, so `transaction_date` is `now()` (the `X-TIMESTAMP` is ignored). **Restore NMID routing (`BusinessRepository.findFirstByNmid`) + reference-based idempotency + X-TIMESTAMP parsing once the provider fixes the payload.**

FCM sending lives in `PushNotificationService` (shared). **Every message is data-only** — no FCM `notification` block anywhere, so the app always gets `onMessageReceived` and renders the push itself; `data.type` (`PushType`) says which kind it is. `sendToToken` (one device, propagates errors — used by `POST /api/notifications/test`), `sendToUser` (all one user's device sessions — used by `POST /api/notifications/user`), `sendToBusiness` (every device of every active user in a business — used by the callback so a payment rings the whole team), and `sendUrgentToUser` (same as `sendToUser` but at Android priority HIGH — used by the presence flow, which needs the app woken at once). The three fan-out methods are best-effort and never throw. `NotificationService` (the `/api/notifications/*` endpoints) only sends a push; it does not persist a transaction.

**Stale-token auto-cleanup:** when FCM returns `UNREGISTERED` ("Requested entity was not found" — app uninstalled / token rotated) or `SENDER_ID_MISMATCH`, the dead token is cleared from its `bejo_sessions` row so it isn't retried. The classifier is `PushNotificationService.isTokenUnregistered(MessagingErrorCode)`; both `sendToUser` (per failing session) and `NotificationService` (the test endpoint, then rethrows) apply it. Transient/payload errors (`UNAVAILABLE`, `INVALID_ARGUMENT`, …) never delete the token.

### QRIS model

QRIS is optional: registration creates no `business_qris` row, so `GET /api/qris` returns `404` until the user uploads one. `POST /api/qris/upload` creates the row on first upload (`qris_code` auto-generated). Once a row exists: `qris_image = NULL` → type `MANAGED` (serves `initial_qris_image`, generated on first access from `qris_code`); non-null → type `UPLOADED`. Reset simply nulls `qris_image`.

### Products & inventory

Basic per-business SKU catalog with simple inventory, scoped by `business_id` (resolved from the authenticated username via `BusinessContext`):
- `ProductCategoryController` → `ProductCategoryService` — categories (`product_categories`); names unique per merchant. Deleting a category first **un-categorizes** its products (`ProductRepository.clearCategory` sets their `category_id` to null in the service, so behavior matches under both Flyway/Postgres and the H2 test schema) then removes the row — products are never deleted with it.
- `ProductController` → `ProductService` — products (`products`); `sku` unique per merchant. Editable fields include an optional `shortDescription`. `stock` is a plain column (inventory management = `PATCH` the value; no ledger). A product's `categoryId` must belong to the same merchant (else 400). `photo` is an optional base64 image validated ≤500 KB decoded; **list responses omit the photo** to keep payloads small, the single-product detail includes it. Prices are `BIGINT` IDR; `costPrice` is optional.
- **Stock opname** — `POST /api/products/stock-adjustments` (`ProductService.adjustStock`) bulk-sets several products' stock to their newly counted quantities. Atomic and two-pass (unknown product → 400, nothing changes); returns a per-product variance report (`previousStock`/`newStock`/`difference`). No ledger is persisted.

### Stock mutations (repurposed "purchases")

`StockMutationController` → `StockMutationService` back a unified **stock-change history** in the `stock_mutations` + `stock_mutation_items` tables. Every recorded stock change is a mutation with a `type` (`StockMutationType`): `PURCHASE` (restock), `ADJUSTMENT` (stock opname), `UPDATE` (a product edit that changed stock). Each line snapshots the product (`sku`/`name`) plus `previous_stock`/`new_stock`/`quantity_change`; `unit_price`/`subtotal` are only set for PURCHASE. `stock_mutation_items.product_id` has no FK, so history survives a product edit/delete.

- `POST /api/stock-mutations` — records a **PURCHASE** (restock): atomic two-pass (unknown product → 400, nothing changes), raises each product's `stock`, `total_amount` = Σ subtotals (unit price = provided, else `costPrice`, else 0).
- **ADJUSTMENT** and **UPDATE** mutations are written by `ProductService` after it applies the change: `adjustStock` (stock opname) records one ADJUSTMENT mutation; `update` records an UPDATE mutation only when the edit actually changed `stock`. Both call `StockMutationService.recordStockChange`.
- `GET /api/stock-mutations` lists all mutations (newest first) **with their line items** (batched in one query, no N+1); `GET /api/stock-mutations/{id}` returns a single mutation with its items.

### App settings

`AppSettingsController` → `AppSettingsService` manage a per-**business** `business_app_settings` row (one per `business_id`, created lazily on first update, shared by all the business's users) holding the settings synced from the mobile client:
- **Monitored apps** — a `packageName → issuer` map (mirrors the Android `notifAppList` pref), stored as JSON via `StringMapJsonConverter`.
- **Notification keywords** — `includeKeywords` (whitelist) + `excludeKeywords` (blacklist) string lists (JSON via `StringListJsonConverter`) plus `blacklistEnabled` (default true) / `whitelistEnabled` (default false) toggles.

- **App config toggles** — `qrisWithProfilePicture` (compose the QRIS image onto the profile picture) and `inventoryEnabled` (enable inventory management), both boolean columns defaulting false, each set via its own `PUT` (`{enabled}`).

**Authorization:** reading (`GET /api/app-settings`) is open to any member, but **all mutations are super-admin-only** — the four `PUT`s go through `AppSettingsService.loadOrCreate`, which calls `BusinessContext.requireSuperAdminBusinessId` (403 `ForbiddenException` for a plain member). The same gate protects the QRIS image (`POST /api/qris/upload` + `/reset`) and the business picture (`PUT`/`DELETE /api/business/picture`). Business info + bank edits are **not** gated (any member).

`GET /api/app-settings` returns defaults (empty lists/map, blacklist on, whitelist off, both toggles off) when no row exists yet — it never 404s. `PUT /api/app-settings/monitored-apps` replaces the whole map; `PUT /api/app-settings/keywords` is a partial update (null field = unchanged, empty list = cleared). The service validates and normalizes input — package names match `[A-Za-z0-9_.]+`, issuers/keywords are trimmed + lowercased, keyword lists deduped, with size caps — throwing `IllegalArgumentException` (400) on bad input.

### Employee daily presence

`PresenceController` → `PresenceService` back employee attendance in the `presences` table. Three entry kinds share the table, distinguished by `type` (`PresenceType`): `CLOCK_IN`, `CLOCK_OUT`, `PING`.

- **Clock in/out** (`POST /api/presences/clock-in` · `/clock-out`) — **members only**; a super admin gets 403 (they don't take attendance). Body requires `latitude`/`longitude`/`clockDatetime` (+ optional `note`). The time is **client-supplied** (an offline entry keeps its real time when it syncs) but a `clockDatetime` more than a day ahead is rejected (400) as a wrong/forged device clock.
- **Once per day** — a member may clock in **at most once** and clock out **at most once** per calendar day (`presence_date`); a repeat is a 400 (`"Kamu sudah absen masuk hari ini"`). Enforced twice: `PresenceService` checks first (the friendly path), and the partial unique index `uq_presence_clock_daily (user_id, type, presence_date) WHERE type IN ('CLOCK_IN','CLOCK_OUT')` settles a race between two simultaneous taps (→ 409 via `GlobalExceptionHandler`). PINGs are excluded from the index — the super admin may ping the same member repeatedly.
- **Ping** (`POST /api/presences/pings`) — **super admin only**; a spot-check that writes one `PING` row per targeted member and pushes it to each. Optional **`userId`** (a single id, not a list) picks the target: **omit it to ping the whole team**, or set it to ping just that member. `PresenceService.resolvePingTargets` only ever loads users with `BusinessRole.MEMBER`, so a ping never reaches the **super admin's own devices**; the id must be an active `MEMBER` of the caller's own business — an unknown id, another business's staff, or the super admin's own id is a 400 and nothing is sent (400 too if the business has no members yet). Push copy is fixed: `"Ping Absen Bejoo!"` / `"Halo Mas/Mbak Bejo Sekalian. Masih pada Semangaat?"`.
- **Ping reply** — a PING's `note` belongs to the **member's reply**, not to the admin who sent it: the ping request has no `note` field, and `POST /api/presences/{id}/respond` takes an optional `{note}` which is stored on the row for the super admin to read in the history. Respond stamps `response_datetime` — only on their own row, only a `PING`, only once. "Only once" is a **conditional update** (`PresenceRepository.markResponded` sets `response_datetime` + `note` `WHERE response_datetime IS NULL` and returns the row count; 0 → 400), not a read-then-write, so two simultaneous taps can't both stamp it.
- **History** (`GET /api/presences`) — newest first, paginated. The **super admin sees the whole business** and may filter to one member with `userId`; a **member is always scoped to their own** entries (passing another user's `userId` is a 403). `year` (+ optional `month`) narrows to that calendar period, ranged over the `presence_date` column; **`month` without `year` means that month of the current year** (no 400). Each entry carries `userActive` — false once the member has been removed from the business (their rows stay in the history; one batched query per page resolves it, no N+1).

**Push:** presence pushes go out **data-only at Android priority HIGH** (`PushNotificationService.sendUrgentToUser`). Data-only is deliberate: an FCM `notification` block is drawn by the system tray and **never reaches `onMessageReceived` while the app is backgrounded**, so the app would get no code execution and couldn't auto-show its dialog. Data-only hands every delivery to the app; HIGH priority stops FCM holding it until the device next wakes. The cost is that a **force-stopped** app shows nothing — accepted, since the point is for the app to act. A clock-in/out pushes the **super admin** (`title` = `"<username> telah absen masuk!"` / `"… keluar!"`, `body` = `"Pastikan jadwal absen sesuai prosedur. Sukses terus ya!"`); a ping pushes **each targeted member** (`title` = `"Ping Absen Bejoo!"`, `body` = `"Halo Mas/Mbak Bejo Sekalian. Masih pada Semangaat?"`). Every push is best-effort — FCM being down never fails or rolls back the attendance entry.

**Push payload `type`** (`constant/PushType`) is how the app tells the two kinds apart — **every** push is data-only, so it's the client's only reliable discriminator: `TRANSACTION` (payment callback; carries `transactionId`/`amount`/`status`/…) vs `PRESENCE` (carries `presenceType` = CLOCK_IN/CLOCK_OUT/PING, `presenceId`, `userId`, `username`). Both carry `title`/`body` for the app to render.

`username`/`name` are **snapshotted** on the row and `user_id` carries no FK, so the history survives a member rename or soft-delete.

### Timezone

The whole app is pinned to **WIB (`Asia/Jakarta`, UTC+7)**: `BejoApplication.main` calls `TimeZone.setDefault(AppTime.ZONE)` before Spring starts, so every `LocalDate/LocalDateTime.now()` (transaction/session times, soft-delete stamps, the summary buckets) is Jakarta wall-clock regardless of the container zone (Cloud Run is UTC). `TIMESTAMP` columns are zoneless wall-clock, so this only affects new `now()` values, never reinterprets stored rows. `util/AppTime.ZONE` is the single source of truth — reference it where the zone must be explicit (e.g. `TransactionService.summary` default, `QrisCallbackService` normalizes an offset-bearing `X-TIMESTAMP` to WIB via `atZoneSameInstant`). Jackson also serializes dates in this zone (`spring.jackson.time-zone`).

### Observability

- `/actuator/health` — liveness/readiness
- `/actuator/prometheus` — Micrometer metrics scraped by Prometheus (container: `bejo-prometheus:9090`)
- Custom counters: `login.attempts{result}`, `transactions.fetched`, `notifications.sent{result}`
- `RequestLoggingFilter` logs `METHOD PATH[?query] STATUS DURATIONms` for every request. On a 4xx/5xx it additionally logs the request body (`[BODY] …`) — secrets (`password`/`token`/`pushToken` fields) masked, single-line, capped at 2 KB, and auth endpoints (`/api/auth/**`, register) skipped entirely. Toggle with `app.request-logging.log-body-on-error` (default `true`) / `app.request-logging.max-body-length` (default `2048`).
- The QRIS callback (`/snap/v1.0/qr/qr-mpm-notify`) gets a fuller dump: on any 4xx/5xx, `RequestLoggingFilter` logs `[CALLBACK-REQ]` with **all headers** (credential headers `Authorization`/`X-API-TOKEN`/`Cookie` masked) and the **full body** (capped 8 KB). Its body is buffered up front (`CachedBodyHttpServletRequest`) so it's captured even when validation fails before the body is read (e.g. a missing `X-TIMESTAMP` header).
- `GlobalExceptionHandler` logs every 4xx client error at `WARN` (`[400] METHOD /path — reason`), so requests rejected before reaching a service (validation, missing header, unreadable body) are still debuggable in Cloud Run.
- JSON log format active under `SPRING_PROFILES_ACTIVE=prod`

## Database

Schema is created by Flyway (`src/main/resources/db/migration/`) at app startup; `ddl-auto: none`. `V1__init_schema.sql` is the single consolidated baseline — it was **rewritten** for the user↔business decoupling (there were no production users to preserve), so the DB must be **dropped & recreated** rather than migrated (`podman compose down -v` to drop the volume). Schema changes since then are additive versioned migrations (`V2__presence.sql` is the first) — never edit an applied migration in place (Flyway validates checksums against the live DB).

Key tables:
- `businesses` — the merchant; business name/address/nmid (+ `initial_*` for reset) + `size`/`industry` (NOT NULL, immutable, config-validated) + optional `store_photo` + shared `picture` (base64 TEXT, ≤1 MB decoded); partial-unique `nmid WHERE nmid IS NOT NULL` (callback routing)
- `users` — credentials + profile (name/email/phone); `business_id` FK; `business_role` (`SUPER_ADMIN`/`MEMBER`); `account_type` (`MANAGED`/`SELF_SERVICE`); soft delete via `deleted_at` (username unique only among active users — partial index `uq_users_username_active`)
- `business_banks` / `business_qris` / `business_app_settings` — one row per `business_id` (unique FK); app settings = monitored-apps map + keyword filters (JSON) + blacklist/whitelist + toggles (`qris_with_profile_picture`, `inventory_enabled`)
- `bejo_sessions` — one row per `(username, device_id)`; holds FCM push token, schedule window (no FK — username uniqueness is partial)
- `bejo_history` — transactions, scoped by `business_id`; unique on `transaction_id` and `idempotency_key`; soft delete via `deleted_at`; general-purpose `info` (TEXT, e.g. raw callback JSON — the provider's `additionalInfo` payload is stored here under the shorter name)
- `transaction_items` — optional line items of a sale; `history_id` FK → `bejo_history(id)`; snapshots `sku`/`name`/`quantity`/`unit_price`/`subtotal`; `product_id` is a plain reference (no FK)
- `token_blacklist` — logged-out JWT jtis, purged hourly after expiry
- `product_categories` — per-business categories; name unique per `business_id`
- `products` — per-business SKUs; `sku` unique per `business_id`; optional `short_description`; nullable `category_id` FK; `stock` (INTEGER), `price`/`cost_price` (BIGINT IDR), optional base64 `photo` (TEXT, ≤500 KB decoded)
- `stock_mutations` / `stock_mutation_items` — per-business stock-change history (type `PURCHASE`/`ADJUSTMENT`/`UPDATE`/`SALE`); each line snapshots the product + `previous_stock`/`new_stock`/`quantity_change`, with `unit_price`/`subtotal` for purchases; `total_amount` is the purchase sum
- `presences` — employee attendance (`V2__presence.sql`), scoped by `business_id`; type `CLOCK_IN`/`CLOCK_OUT`/`PING`; `clock_datetime` + `presence_date` (the month-year filter's column), `latitude`/`longitude` (null on a PING), nullable `response_datetime` (a PING's acknowledgment); `note` = the member's own text — what they typed on a clock-in/out, or their **reply** to a PING (written by `respond`, not by the admin who sent the ping); `username`/`name` snapshotted and `user_id` unconstrained (no FK) so history survives a member rename/soft-delete; partial unique index `uq_presence_clock_daily` caps clock-ins/outs at one per member per day

`username` is **`VARCHAR(50)` everywhere** it's stored (`users`, `bejo_sessions`, `presences`) and capped at 50 by the register/add-member DTOs; the entities declare `@Column(length = 50)` so the H2 test schema matches Postgres.

## Tests

- `src/test/java/com/bejo/service/*Test.java` — Mockito unit tests, no Spring context.
- `src/test/java/com/bejo/web/ApiIntegrationTest.java` — full-stack functional tests via `@SpringBootTest` + MockMvc against in-memory H2 (PostgreSQL mode); Flyway disabled, schema from `ddl-auto: create-drop` (`src/test/resources/application-test.yml`, profile `test`).
