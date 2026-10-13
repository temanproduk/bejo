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
# The build needs JDK 25 (maven-enforcer fails fast otherwise). ./mvnw pins Maven itself.
# On a machine without JDK 25, run any of these in the Maven container instead:
#   podman run --rm -v "$PWD":/app -w /app maven:3.9-eclipse-temurin-25-alpine ./mvnw test

# Build
./mvnw package -DskipTests

# Run all tests (unit + functional on in-memory H2). MigrationPostgresTest is skipped unless
# MIGRATION_TEST_DB_URL points at an EMPTY Postgres (see the test's javadoc); CI always runs it.
./mvnw test

# Run a single test
./mvnw test -Dtest=ClassName#methodName

# Run locally (requires PostgreSQL at localhost:5432)
./mvnw spring-boot:run

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
| `MAX_REQUEST_BYTES` | Max request body size, 413 above it (`app.security.max-request-bytes`, default `8388608` = 8 MB; the largest real payloads are ~3 MB, so it can be lowered) |
| `DB_POOL_SIZE` | JDBC pool size **per instance** (`spring.datasource.hikari.maximum-pool-size`, default `10`). Pool × Cloud Run max-instances (default 2) plus the DB's reserved connections must stay under its `max_connections` — a `db-f1-micro` allows very few |
| `JAVA_OPTS` | JVM flags; **replaces** (does not append to) the image default `-XX:MaxRAMPercentage=75`. Lower the percentage if the platform OOM-kills the container |
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
| `ADMIN_EMAILS` | Comma-separated addresses that may sign in to the admin dashboard with an emailed one-time code (`app.admin.emails`; blank = nobody). Needs `MAIL_*` |
| `ADMIN_DASHBOARD_ORIGINS` | Browser origins allowed to call `/api/admin/**` via CORS (`app.admin.dashboard-origins`, default `https://dashboard.bejo.temanproduk.com`) |
| `MAIL_HOST` / `MAIL_USERNAME` / `MAIL_PASSWORD` | (Optional, all three or none) SMTP for password-reset emails — Zoho Mail (`smtp.zoho.com` for FREE accounts, `smtppro.zoho.com` for PAID — the wrong one fails login with `554 5.7.8 Access Restricted`; `.eu`/`.in`/`.com.au` for other data centres). Unset = the feature is off and `/api/auth/forgot-password` answers 503. `MAIL_FROM` (default: the username; Zoho requires the mailbox or an alias), `MAIL_FROM_NAME`, `MAIL_PORT` (587 + STARTTLS; 465 needs `MAIL_SSL=true MAIL_STARTTLS=false`). On Cloud Run the password is the Secret Manager secret `bejo-mail-password`, mounted only when `MOUNT_MAIL_PASSWORD=true` |
| `CALLBACK_SECRET` | (Optional) Shared secret the payment provider must send as `X-Callback-Secret` on `/snap/**` (`app.callback.secret`); blank = callback unauthenticated (warned at prod startup) |
| `CALLBACK_FALLBACK_TO_DEFAULT` | (Optional) `app.callback.fallback-to-default`, default **`false`**: a callback with a missing/unknown `merchantPan` is rejected (404) instead of recorded against the default account |
| `CLOUD_RUN_CPU_THROTTLING` | (Deploy only) `false` → `--no-cpu-throttling` (CPU always allocated; costlier). Default `true` |
| `PUSH_QUEUE_THREADS` / `PUSH_QUEUE_CAPACITY` | (Optional) FCM delivery worker pool size + queue capacity (`app.push.queue.*`, default `4` / `1000`) |

**Startup guard.** Under `SPRING_PROFILES_ACTIVE=prod` the app refuses to start if `API_TOKEN` or `JWT_SECRET` is blank or still the built-in development default (both defaults are public in `application.yml`) or `JWT_SECRET` isn't base64 ≥256 bits — `ProdSecretsEnvironmentPostProcessor`, an `EnvironmentPostProcessor` registered in `META-INF/spring.factories` (not a bean: `spring.main.lazy-initialization` would never instantiate an unreferenced validator). It also *warns* about a default DB password, expiry disabled, an unauthenticated callback and `CALLBACK_FALLBACK_TO_DEFAULT`. A failed start can't cause an outage: Cloud Run never shifts traffic to an unhealthy revision and `deploy-vm.sh` restores the previous release. Local compose uses the default profile (see `.env.example`).

Set via a `.env` file alongside `docker-compose.yml` — compose picks it up automatically. GCP deployment config lives in `.env.gcp` (see `.env.gcp.example`, `scripts/init-gcp.sh`, `.github/workflows/deploy.yml` — deploys on push to the `release` branch). The Cloud Run env vars, secret mounts and sizing are defined **once** in `scripts/deploy-cloud-run.sh`, which both `init-gcp.sh` and `deploy.yml` call; `--set-env-vars` replaces the whole environment, so a new app setting must be added there and (for CI) forwarded in `deploy.yml`'s `env:` block. An alternative **single-VM deploy** (Caddy → app → Postgres via `docker-compose.prod.yml`; the host is a root-login VPS in Jakarta with a web console) lives in `scripts/init-vm.sh` (one-time bootstrap: Docker, an unprivileged `deploy` user with the admin + restricted CI keys, dirs, backup cron), `scripts/harden-vm.sh` (one-time lockdown: ufw, key-only SSH with root login off, fail2ban, unattended-upgrades — guarded by a self-reverting timer so it can't lock you out; the `deploy` user has no sudo, so admin work after it goes through the web console, or `VM_ROOT_LOGIN=prohibit-password`) and `scripts/deploy-vm.sh` (build-on-VM, health-checked, `--rollback`), configured by the gitignored `.env.vm` (see `.env.vm.example`; `VM_*` keys stay local, the rest is written to the VM as `.env`). **Current setting (as of 2026-10-13): the `DEPLOY_TARGET` repo variable is `cloudrun`** (also the default when unset). **Production runs in GCP project `project-68db2e7f-081f-4e2c-b8c`** (number `682489264316`, region `us-central1`), service `bejo` at `https://api.bejo.temanproduk.com` (Cloudflare `CNAME → ghs.googlehosted.com`, DNS-only; the Cloud Run domain mapping was moved here with `--force-override`). Its database started **empty** — the previous project `bejo-prod` (and its data) was abandoned and is pending deletion (purge ≈ 2026-11-12). The repo variables `GCP_PROJECT_ID` and `CLOUD_SQL_INSTANCE_CONNECTION_NAME` point at the new project and `ADS_GCS_BUCKET` is unset (it defaults to `<project>-ads`). **The org forbids downloadable service-account keys** (`iam.disableServiceAccountKeyCreation`), so CI authenticates with **Workload Identity Federation**: `init-gcp.sh` created the pool/provider (locked to this repo's `release` branch) and the variables `GCP_WORKLOAD_IDENTITY_PROVIDER` / `GCP_DEPLOY_SERVICE_ACCOUNT` are set; `deploy.yml` falls back to a `GCP_SA_KEY` secret only if they are empty (`init-gcp.sh --sa-key`). There is no `GCP_SA_KEY` secret now. Known open items: the DB password is still the default `bejo` (the app warns), the payment callback has no `CALLBACK_SECRET`, and the keyless CI login has not yet run (the workflow changes are uncommitted). The VM path stays available (`DEPLOY_TARGET=vm|both`). Verify the live values with `gh variable list`. CI picks the target per run: `deploy.yml` has a `plan` job that reads the `DEPLOY_TARGET` repo variable (`cloudrun` — the default when unset — `vm`, or `both`; `workflow_dispatch` can override it or roll the VM back), then runs the `cloud-run` and/or `vm` job. The `vm` job calls `deploy-vm.sh --keep-env`, so app secrets stay in the VM's `.env` and never pass through GitHub; only `VM_SSH_KEY` (secret) and `VM_HOST`/`VM_USER`/`VM_DOMAIN`/`VM_KNOWN_HOSTS` (variables) are needed.

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

`/actuator/health` (Cloud Run probes) and `/snap/**` (inbound QRIS payment callbacks) are exempt from both checks (the callback has its own optional shared-secret guard — see *QRIS payment callback*). Other `/actuator/*` endpoints (metrics, prometheus, info) require `X-API-TOKEN` but not a JWT — so `/actuator/prometheus` is **not** publicly reachable on Cloud Run. The local Prometheus scraper sends the token via `http_headers` (see `docker/prometheus.yml` + the `prometheus_api_token` compose config). Responses include HSTS + hardening headers (`SecurityConfig.headers`); `server.forward-headers-strategy: framework` lets Spring see the Cloud Run proxy's HTTPS scheme so HSTS is emitted.

## API endpoints

Every endpoint with its auth requirement is in [docs/api-endpoints.md](docs/api-endpoints.md) — the controllers are the source of truth.

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
admin/          the admin dashboard's API (/api/admin/**): emailed-code sign-in, sessions, and the
                 read-only cross-business user + transaction lists. Reads core and commerce
                 entities; no module may depend on it.
config/        Spring bootstrap / composition root (may wire any module; nothing depends
                 on it): ApiTokenFilter, JwtAuthFilter, SecurityConfig, FirebaseConfig,
                 RequestLoggingFilter, DataSeeder.
```

Inside each module: `web/` (thin HTTP, `@Valid`) · `service/` (logic, Micrometer counters) · `repository/` (Spring Data JPA) · `domain/` (JPA entities + the module's constant holders) · `dto/` (request records `@NotBlank`-validated + response records; every DTO class name ends with `Dto`, requests/responses separate) · `push/` (the module's own `PushPayload`). Adding a cross-module call means depending on the other module's public API, never reaching into its internals — the ArchUnit rules fail the build otherwise.

Domain "possible values" live in each module's `domain/` package (and `PushType` in `notification/`) as `public static final String` holders — reference these instead of inline literals: `AccountType` (`MANAGED`, `SELF_SERVICE`), `BusinessRole` (`SUPER_ADMIN`, `MEMBER`), `TransactionType` (`MANUAL`, `PARSED`, `MANAGED`), `TransactionStatus` (`SUCCESS`, `PENDING`, `FAILED`, `REFUNDED`, `CANCELLED`, `EXPIRED`, `UNKNOWN`), `Acquirer` (`CASH`, `ASTRAPAY` defaults), `StockMutationType` (`PURCHASE`, `ADJUSTMENT`, `UPDATE`, `SALE`), `PresenceType` (`CLOCK_IN`, `CLOCK_OUT`, `PING`), `PushType` (`TRANSACTION`, `PRESENCE`, `ADS` — the `type` field in every push's data payload), `AdCampaignState` (`CREATED`, `PREPARING`, `READY`, `ERROR`, `SCHEDULED`, `STARTING`, `PLAYED`, `FINISHED`), `AdEvent` (`PREPARE`, `PLAY` — the ads push's `adEvent` discriminator).

Gotcha: `SecurityConfig`'s auth entry point distinguishes 401 vs 404/405 by consulting `RequestMappingHandlerMapping`. It must be injected with `@Qualifier("requestMappingHandlerMapping")` (actuator registers a second bean of that type) and the request path must be parsed via `ServletRequestPathUtils.parseAndCache` because the entry point runs before `DispatcherServlet`.

### Feature reference

The detail for each area lives under `docs/` (read the one you are touching). The rules below are the
ones that are easy to break without noticing.

- **Accounts** ([accounts](docs/architecture/accounts.md)) — registration creates a *new business* + its `SUPER_ADMIN`; members are soft-deleted and their username is released. Business scope always comes from `BusinessContext` (the authenticated username), never from client input. **Password reset** is an emailed 6-digit code (`PasswordResetService`): the code is stored only as a keyed hash, guesses are spent atomically *before* comparing, `forgot-password` must never reveal whether a user exists (same 200, padded timing), and no code goes to an email changed within 24 h (changing the email needs no password, so a stolen session could otherwise take the account over).
- **Transactions** ([transactions](docs/architecture/transactions.md)) — line items are validated then applied in two passes; **stock changes only through the conditional updates** `decreaseStock`/`increaseStock`, never read-modify-write. Delete is super-admin-only and returns stock. A PARSED push excludes the *reporting device* (`X-Device-Id`), not the account, and goes out `afterCommit`.
- **QRIS** ([qris](docs/architecture/qris.md)) — the payment callback is routed by `merchantPan` (unknown → 404 unless `CALLBACK_FALLBACK_TO_DEFAULT`), can be guarded by `X-Callback-Secret`, is idempotent on the provider's reference, and pushes after commit. A QRIS is stored as its EMV *code*; merchant name/NMID/acquirer/terminal are parsed out of it.
- **Push** ([push](docs/architecture/push.md)) — every message is **data-only** (no FCM `notification` block, or backgrounded apps never run `onMessageReceived`); each kind is a `PushPayload`; fan-out enqueues jobs that a worker sends; dead tokens are cleared.
- **Products & stock** ([inventory](docs/architecture/inventory.md)) — every stock change is recorded in the `stock_mutations` ledger (`PURCHASE`/`ADJUSTMENT`/`UPDATE`/`SALE`).
- **Account settings** ([account-settings](docs/architecture/account-settings.md)) — one row per business; all mutations are super-admin-only; team management is *effectively* on when more than one user exists.
- **Presence** ([presence](docs/architecture/presence.md)) — members only (a super admin gets 403); one clock-in and one clock-out per day, enforced by a partial unique index; pings have an hour's cooldown; pushes go `afterCommit`.
- **Admin dashboard** ([admin](docs/architecture/admin.md)) — `/api/admin/**` for the static site in `../bejo-dashboard`. Admins are the `ADMIN_EMAILS` allow-list, signed in by an emailed 8-digit code → an opaque session token checked by `AdminAuthFilter` (not a user JWT). The lists are deliberately **not** business-scoped. CORS is open for these paths only (`AdminCorsConfig`, first in the filter chain). Its tables are in the `admin` schema (`V3`).
- **Voice ads** ([ads](docs/architecture/ads.md)) — the admin surface is guarded by `X-ADS-ADMIN-TOKEN` in a filter; audio lives in a locked-down GCS bucket behind signed URLs; every device transition is a conditional update.

### Timezone

The whole app is pinned to **WIB (`Asia/Jakarta`, UTC+7)**: `BejoApplication.main` calls `TimeZone.setDefault(AppTime.ZONE)` before Spring starts, so every `LocalDate/LocalDateTime.now()` (transaction/session times, soft-delete stamps, the summary buckets) is Jakarta wall-clock regardless of the container zone (Cloud Run is UTC). `TIMESTAMP` columns are zoneless wall-clock, so this only affects new `now()` values, never reinterprets stored rows. `util/AppTime.ZONE` is the single source of truth — reference it where the zone must be explicit (e.g. `TransactionService.summary` default, `QrisCallbackService` normalizes an offset-bearing `X-TIMESTAMP` to WIB via `atZoneSameInstant`). Jackson also serializes dates in this zone (`spring.jackson.time-zone`).

### Observability

- `/actuator/health` — liveness/readiness
- `/actuator/prometheus` — Micrometer metrics scraped by Prometheus (container: `bejo-prometheus:9090`). The endpoint needs `X-API-TOKEN`, so **on Cloud Run nothing scrapes it** — those metrics are unused in production. What actually watches prod is up to you: set an uptime check on `/actuator/health` and a log-based alert on `[CALLBACK]` errors; the alert that matters most is *"no callback received in N minutes"* (the `qris.callbacks.received` counter, or the `[CALLBACK] Recorded` log line). Tracing is not set up (there is no tracer dependency).
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

Keep the JPA entities in lockstep with the migrations. The H2 test DB builds its tables **from the entities** (`ddl-auto: create-drop`, Flyway disabled), so the H2 tests cannot catch a drift between an entity and the SQL. `MigrationPostgresTest` does: it boots the whole app against a real Postgres with Flyway on and `ddl-auto=validate`, so a migration that doesn't apply, or an entity mapping a column the migrations don't create, fails CI (verified by renaming a column and watching it fail). `EagerMigrationConfig` keeps Flyway out of `spring.main.lazy-initialization`, so migrations run at startup rather than on the first request.

**Schema-per-module + no cross-schema foreign keys.** The tables live in **three Postgres schemas** matching the code modules — `core` (businesses, users, banks, QRIS, sessions, account-settings, presences, token-blacklist), `commerce` (transactions, transaction_items, products, product_categories, stock_mutations, stock_mutation_items), `ads` (ad_campaigns, ad_deliveries) — created by `V1` (`CREATE SCHEMA IF NOT EXISTS …`). Entities carry `@Table(schema = "…")`; all queries are JPQL so Hibernate schema-qualifies automatically (no native SQL in main). A commerce/ads table **never FK-references a core table**: `transactions`/`products`/`product_categories`/`stock_mutations` hold `business_id` as a **plain soft reference** to `core.businesses(id)` (like `presences`/`ad_deliveries`), not an FK — every surviving FK is intra-schema (`products.category_id`, `transaction_items.transaction_id`, `stock_mutation_items.mutation_id`, `ad_deliveries.campaign_id`, and core's `*_business` FKs). The invariant is enforced in code — `business_id` is always assigned server-side from `BusinessContext`, never from the client, and businesses are never hard-deleted — with an orphan-detection safety net (`scripts/check-orphans.sql`, exercised on CI by `OrphanDetectionTest`). The H2 test DB pre-creates the schemas via the JDBC `INIT` clause (`application-test.yml`); Flyway keeps its history table in the default schema.

The key tables and their columns are in [docs/database.md](docs/database.md).

## Tests

Tests mirror the module layout under `src/test/java/com/bejo/`:

- `<module>/service/*Test.java` (`core`, `commerce`, `ads`) and `notification/*Test.java` — Mockito unit tests, no Spring context.
- `web/*ApiIntegrationTest.java` (`ApiIntegrationTest`, `AdsApiIntegrationTest`, `UserQrisApiIntegrationTest`) — full-stack functional tests via `@SpringBootTest` + MockMvc against in-memory H2 (PostgreSQL mode); Flyway disabled, schema from `ddl-auto: create-drop` (`src/test/resources/application-test.yml`, profile `test`).
- `ArchitectureTest` — ArchUnit module-dependency rules; fails the build on a forbidden cross-module reference.
- `OrphanDetectionTest` — exercises `scripts/check-orphans.sql` (the commerce→core soft-reference safety net).
- `commerce/StockConcurrencyTest` — concurrent-sale guarantee for the conditional stock updates.
- `MigrationPostgresTest` — the app against a real Postgres (Flyway + `ddl-auto=validate`); skipped without `MIGRATION_TEST_DB_URL`. `ProdSecretsEnvironmentPostProcessorTest`, `CallbackSecretFilterTest` and the callback tests in `QrisCallbackServiceTest`/`ApiIntegrationTest` cover the prod-secrets guard and the callback hardening.

CI (`.github/workflows/ci.yml`) runs `./mvnw -B test` (with a Postgres service container for `MigrationPostgresTest`) on every push to `main` and on PRs, and is also a reusable workflow (`workflow_call`): `deploy.yml` calls it as its `test` job, and the `cloud-run`/`vm` jobs `needs` it, so **a failing suite blocks a release deploy** (a VM `--rollback` skips the gate so it stays possible when the suite is red). The release image itself is built with `-DskipTests`. A push to `release` is therefore tested once, via `deploy.yml` — `release` is deliberately not a trigger in `ci.yml`. Actions are pinned to commit SHAs and base images to digests; Dependabot (`.github/dependabot.yml`) proposes the updates. The cloud-run job builds with Buildx and the GitHub Actions layer cache, and the Dockerfile produces a *layered* image (dependencies / application) so a normal deploy pushes only the small application layer.
