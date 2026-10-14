# Bejo

**Real-time payment tracking for Indonesian QRIS merchants.**

Bejo is the backend service behind the Bejo merchant mobile app. It turns a static QRIS code into a live point of sale: the moment a customer pays, the merchant's phone rings with a "payment received" notification, and every transaction is stored, searchable, and reconciled in one place.

## The problem

QRIS ([Quick Response Code Indonesian Standard](https://www.bi.go.id/QRIS)) is Bank Indonesia's unified QR payment standard — a single code accepts money from any Indonesian e-wallet or mobile bank (GoPay, OVO, DANA, ShopeePay, bank apps, and more). But a merchant with a printed QRIS sticker has no idea when a payment actually arrives. They have to open their banking app and check, which is slow, error-prone, and easy to game with fake "payment success" screenshots.

Bejo closes that gap.

> The sections below are the **product overview**. Each feature links into the [technical reference](#contents) further down for the exact API and behaviour.

## What merchants get

- **Instant payment alerts** — a push notification with an audible chime the moment money lands, so staff never have to trust a customer's screenshot. It's delivered to **every device of every user** in the business. → triggered by the [QRIS callback](#qris-callback); see the [notification APIs](#notifications).
- **Transaction history** — every payment recorded and filterable by date, plus dashboard totals for today / yesterday / this month. → [Transactions](#transactions).
- **Manual & external entries** — record cash sales or log transactions from another acquirer alongside QRIS payments; entries can be edited or removed, and pushed in bulk after offline entry. → [adding & editing transactions](#transactions).
- **Inventory & catalog** — a per-business product catalog with categories, stock levels, restocks and stock-opname; a sale can carry **line items** that draw the sold products' stock down automatically. → [Products & categories](#products--categories) and the [stock-change history](#stock-mutations).
- **QRIS management** — display the merchant's managed QRIS or upload their own image (no image is synthesized; accounts without one return `null`). → [QRIS](#qris).
- **Business & bank profile** — keep the business name, address and payout bank details up to date. NMID belongs to the QRIS and is read from `GET /api/qris`. → [User profile](#user-profile).
- **Team access** — a business can have several staff users sharing the same data; the creator is the **super admin** who manages access, up to a configurable cap (default 5). → [Businesses & users](#businesses--users) below, and the [member APIs](#business-members).
- **Staff attendance** — staff clock in and out from the app with their GPS location, and the owner's phone rings each time ("Budi telah absen masuk!"). The owner can also ping the team for a spot-check, which staff acknowledge with one tap, and review everyone's attendance by member and by month. → [Employee presence](#employee-presence).

## Businesses & users

A **business** (merchant) owns everything — [bank & profile](#user-profile), [QRIS](#qris), [products & inventory](#products--categories), [transactions](#transactions), [app settings](#account-settings), and a shared business picture. **Many users can belong to one business**, with a flat [role model](#account-types):

| Role | Who | Can |
|---|---|---|
| **SUPER_ADMIN** | The user who registered the business (its creator) | Everything — plus [manage the business's other users](#business-members) and change business-wide settings ([app config](#account-settings), [picture & QRIS](#qris)). Immutable & undeletable. |
| **MEMBER** | Staff added by the super admin | Everything except managing users and changing business-wide settings (they can still read them). |

Registration ([`POST /api/users/register`](#users)) creates a new business + its super admin; the super admin adds staff via the [member APIs](#business-members), and deleting a member frees their username for reuse. Every request authenticates with a [JWT + shared API token](#authentication).

## How a payment flows

1. A customer scans the merchant's [QRIS](#qris) and pays from any Indonesian e-wallet or bank app.
2. The payment gateway calls Bejo's [QRIS callback](#qris-callback), which records the [transaction](#transactions) and sends an [FCM push](#notifications) (with a ring sound) to **every device of every user in that business** — so the whole team is alerted.
3. The transaction appears in the shared [transaction history](#transactions), available for filtering, reconciliation, and [manual correction](#transactions).

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

REST API built with **Java 25 + Spring Boot 3.5**: local-DB authentication (JWT), transaction history and manual entry, QRIS management, bank/business profile management, FCM token management, and push notification delivery.

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
    - [Push payloads](#push-payloads)
  - [Devices](#devices)
  - [User profile](#user-profile)
  - [Products & categories](#products--categories)
  - [Stock mutations](#stock-mutations)
  - [Employee presence](#employee-presence)
  - [Account settings](#account-settings)
  - [QRIS](#qris)
  - [QRIS callback](#qris-callback)
  - [Voice ads](#voice-ads)
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

Deployments trigger automatically on push to the `release` branch via GitHub Actions, **after the test suite passes** — a failing suite blocks the deploy. By default that deploys to Cloud Run; set the `DEPLOY_TARGET` repository variable to `vm` or `both` to deploy to the [VM](#production--single-vm-tencent-cloud-docker-compose) instead or as well. The Cloud Run job builds the Docker image (the Dockerfile compiles the app), pushes it to Artifact Registry, and deploys it to Cloud Run with `scripts/deploy-cloud-run.sh`, the same script `init-gcp.sh` uses for the first deploy.

**First-time infrastructure setup:**

```bash
cp .env.gcp.example .env.gcp   # fill in all values
bash scripts/init-gcp.sh
```

Provisions Cloud SQL, Artifact Registry, Secret Manager, service accounts, and maps `api.bejo.temanproduk.com`. **Idempotent — safe to re-run any time**, including after an interrupted run: it converges on what `.env.gcp` declares and changes only what differs. Existing resources are skipped (a Cloud SQL instance still being created is waited for; one that is `SUSPENDED` stops the script with a billing hint), IAM bindings are written only when missing, a secret gets a new version only when its value in `.env.gcp` changed, a soft-deleted Workload Identity pool/provider is restored, the provider's repo/branch condition follows `GITHUB_REPO`/`GITHUB_DEPLOY_REF`, and the database user's password is re-synced to `.env.gcp`. The one thing every run does is build + deploy (it ships the files on disk, uncommitted changes included); `--skip-deploy` provisions the infrastructure, secrets and domain without touching the running service. The custom-domain step is idempotent too: an existing mapping is detected and left alone (no error), and `--take-over-domain` maps the domain to this project even if another one — say, an abandoned project — still holds it (it uses Cloud Run's `--force-override`). Requires `gcloud auth login` and domain verified in [Google Search Console](https://search.google.com/search-console).

**Database backups (Cloud SQL):** `init-gcp.sh` enables automated daily backups (window `CLOUD_SQL_BACKUP_START_TIME`, default 19:00 UTC; the last 7 are kept) and re-running it turns them on for an instance that was created without them. Point-in-time recovery is deliberately off: it archives WAL on the instance's own 10 GB disk (auto-increase is off), and a full disk takes the database down — enable PITR and storage auto-increase together if you want it.

**Image & build:** on a project that has never used Cloud Build, `init-gcp.sh` first grants the builder role to the service account Cloud Build runs as (the Compute Engine default one on projects created since April 2024) — otherwise the first build fails with `… does not have storage.objects.get access`. The Dockerfile is a pinned (by digest), multi-stage build that produces a *layered* image, so a normal deploy pushes only the small application layer; CI builds with Buildx and the GitHub Actions layer cache. Pinned images and Actions are kept current by Dependabot (`.github/dependabot.yml`). Request/abuse protection is deliberately **not** done in the app: a per-IP limiter needs the real client IP, which behind Cloud Run's proxy can't be determined reliably without testing against it, and a wrong guess would throttle all users together — put rate limits at the edge (Cloud Armor in front of Cloud Run; a Caddy rate-limit module on the VM). The app's own login throttle is per username.

**Runtime scaling (Cloud Run, us-central1):** 1 vCPU / 512 MiB, **0–2 instances** — scales to **zero** when idle so there's no cost between requests, up to 2 under load. CPU is throttled to request time (request-based billing). `spring.main.lazy-initialization` is enabled to keep cold starts (from scale-to-zero) fast. Old images are pruned by an Artifact Registry cleanup policy (keep 5 most recent / delete >30 days).

### Production — single VM (Docker Compose)

An alternative to Cloud Run: one VM running **Caddy** (automatic HTTPS) → the **app** → **Postgres**, all as containers from `docker-compose.prod.yml`. Only Caddy publishes ports (80/443); the app and the database are reachable inside the compose network only. The image is built on the VM, so no container registry is needed.

```bash
cp .env.vm.example .env.vm      # VM address (VM_USER=root at first) + the same secrets/settings as .env.gcp; gitignored
ssh-copy-id root@<host>         # so the scripts use key login instead of prompting for the root password
bash scripts/init-vm.sh         # once: Docker, swap, an unprivileged `deploy` user + your key, directories, nightly DB backup
bash scripts/harden-vm.sh       # once: firewall, key-only SSH, no root login, fail2ban, auto security updates
# now set VM_USER=deploy in .env.vm
bash scripts/deploy-vm.sh       # every deploy: ships the current commit, builds, restarts, health-checks
bash scripts/deploy-vm.sh --rollback   # back to the previous release
```

Before the first deploy, point a DNS A record for `DOMAIN` at the VM so Caddy can get its certificate, and — if your hosting panel has its own firewall — allow inbound TCP 80/443 there (`harden-vm.sh` sets up the host firewall itself). **Choose a region outside mainland China** (e.g. Jakarta, Singapore, Hong Kong): FCM push isn't reachable from the mainland, and serving a domain on 80/443 there needs an ICP filing.

- **Hardening (`harden-vm.sh`)**: ufw allows only SSH, 80 and 443; SSH is key-only with root login off and only the `deploy` user allowed (that account has no password and no sudo); fail2ban bans IPs after repeated failed logins; `unattended-upgrades` installs OS security updates (it never reboots — reboot yourself when a kernel update asks). It can't lock you out: it first checks `deploy` can log in with your key, keeps an admin connection open, arms a timer on the VM that reverts the SSH change after 5 minutes, tests a brand-new `deploy` login, and only then disarms the timer. After it, root can't SSH in, so admin work goes through the hosting panel's web console — or set `VM_ROOT_LOGIN=prohibit-password` (key-only root login) before running it. If you're ever locked out, in the web console: `rm /etc/ssh/sshd_config.d/00-bejo-hardening.conf && systemctl reload ssh`. Docker-group access is effectively root, so treat the `deploy` key (and the CI key) as a root credential. On Docker: containers run with `no-new-privileges`, and the app with all capabilities dropped.
- `deploy-vm.sh` refuses uncommitted changes unless `--allow-dirty`. A failed health check puts the previous release back automatically. A rollback restarts the old *code* — Flyway migrations are forward-only and are never reverted.
- The app is down for the ~30–60 s the new container takes to start (single VM, no blue/green).
- Database backups: `scripts/vm-backup.sh` runs nightly via cron (7 days kept, in `~/bejo/backups`). On their own they die with the VM and are unencrypted, so create `~/bejo/backup.env` **on the VM** (never in git) with `BACKUP_PASSPHRASE_FILE` (AES-256 encrypt; keep a copy of the passphrase off the VM) and `BACKUP_RCLONE_REMOTE` (copy off the VM with rclone, e.g. to object storage) — see the script header for the restore commands. With a passphrase configured the script refuses to write a plaintext backup. `DB_PASSWORD` only takes effect when the data volume is first created.
- Voice ads need Google Cloud Storage credentials the VM doesn't have, so leave `ADS_ADMIN_TOKEN` blank there.
- **Deploying from GitHub Actions.** The `release` push can deploy here too. Set these in Settings → Secrets and variables → Actions, then `DEPLOY_TARGET` to `vm` (or `both`):

  | Name | Kind | Description |
  |---|---|---|
  | `DEPLOY_TARGET` | variable | `cloudrun` (default when unset), `vm`, or `both` |
  | `VM_HOST` / `VM_USER` | variables | The VM's address and the SSH user — the unprivileged deploy user (`deploy`), **not** root |
  | `VM_SSH_PORT` / `VM_APP_DIR` | variables, optional | Default `22` / `bejo` |
  | `VM_DOMAIN` | variable | Public domain; the deploy checks `https://VM_DOMAIN/actuator/health`. Optional — skipped if unset |
  | `VM_KNOWN_HOSTS` | variable | The VM's SSH host key, so CI only talks to that machine: `ssh-keyscan -p 22 <host>` |
  | `VM_SSH_KEY` | **secret** | Private key of a dedicated CI key (`ssh-keygen -t ed25519`). Put its public half at `VM_CI_PUBKEY` in `.env.vm` before `init-vm.sh`: it is authorized for the deploy user with `restrict` (commands only — no terminal, no port forwarding) |

  **Switching target:** `DEPLOY_TARGET=vm` means a push to `release` no longer updates Cloud Run — the existing Cloud Run service (and its Cloud SQL data) keeps running as it is, untouched, so nothing is moved or shut down by the switch; copy the data across yourself when you cut over. Until the `VM_*` variables and `VM_SSH_KEY` exist and the first deploy has been done from your machine, the `vm` job fails and **nothing** gets deployed on a release push. Set `DEPLOY_TARGET` back to `cloudrun` (or `both`) to resume Cloud Run deploys.

  CI runs `deploy-vm.sh --keep-env`: it ships the code and restarts, but the app's secrets stay in the `.env` already on the VM and never pass through GitHub. So do the **first deploy from your machine** (it creates that `.env`), and to change app config later, run a normal `deploy-vm.sh` locally. From the Actions tab, *Run workflow* lets you pick the target for one run or roll the VM back.
- Moving existing data from Cloud SQL: `pg_dump` it and restore it into the `bejo-postgres` container before pointing DNS at the VM.

### Local — Docker Compose

```bash
cp .env.example .env            # fill in API_TOKEN, JWT_SECRET, Firebase creds
docker compose up --build -d
```

Available at `http://localhost:8080`. Prometheus at `http://localhost:9090`.

### Local — Maven

Requires JDK 25 and a running PostgreSQL instance. Flyway migrations run on startup. Use the bundled wrapper (`./mvnw`), which pins the Maven version; the build stops with a clear message on an older JDK. No JDK 25? Run it in the Maven container: `podman run --rm -v "$PWD":/app -w /app maven:3.9-eclipse-temurin-25-alpine ./mvnw test`.

> **Schema changes.** `V1__init_schema.sql` is the deployed baseline and is **frozen** — never edit it (or any applied migration), since Flyway checksums the file against the live database and a mismatch stops the app booting. Add a **new** migration instead (`V2__…`, `V3__…`): additive, forward-only, and safe against a populated table. Keep the JPA entities in step — the H2 tests build their tables from the entities, so they cannot catch entity/migration drift; `MigrationPostgresTest` can (see *Testing*), and CI runs it against a real Postgres on every push.

```bash
export API_TOKEN=dev-token
export JWT_SECRET=YmVqby1kZXYtc2VjcmV0LWtleS1tdXN0LWJlLWNoYW5nZWQtaW4tcHJvZHVjdGlvbi1taW4tMjU2Yml0cw==
export DB_URL=jdbc:postgresql://localhost:5432/bejo
export DB_USERNAME=bejo
export DB_PASSWORD=bejo
./mvnw spring-boot:run
```

---

## Testing

```bash
./mvnw test                              # everything
./mvnw test -Dtest=JwtServiceTest        # one class
./mvnw test -Dtest=ApiIntegrationTest#logoutBlacklistsToken   # one method
```

Most tests run on in-memory H2. **`MigrationPostgresTest`** boots the whole app against a *real* Postgres with Flyway on and Hibernate's `ddl-auto=validate`, so a migration that doesn't apply — or an entity that maps a column the migrations don't create — fails the build. It is skipped unless `MIGRATION_TEST_DB_URL` is set (CI sets it via a Postgres service container). To run it locally against an empty database:

```bash
podman run -d --name bejo-it-pg -p 5432:5432 -e POSTGRES_DB=bejo -e POSTGRES_USER=bejo -e POSTGRES_PASSWORD=bejo postgres:17-alpine
MIGRATION_TEST_DB_URL=jdbc:postgresql://localhost:5432/bejo ./mvnw test -Dtest=MigrationPostgresTest
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

Public (no JWT): `POST /api/users/register`, `POST /api/auth/login`, `POST /api/auth/verify-email`, `POST /api/auth/resend-verification`, `POST /api/auth/forgot-password`, `POST /api/auth/reset-password`  
Exempt from the API token: `/actuator/health` (for Cloud Run probes) and `/snap/**` (inbound QRIS payment callbacks — server-to-server; optionally guarded by a shared secret, see [QRIS callback](#qris-callback)). All other `/actuator/*` endpoints (metrics, prometheus, info) **require** `X-API-TOKEN`.

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
| `username` | No | 3–50 chars, alphanumeric + underscore. Unique among active users; a deleted user's username is released |
| `accountType` | No | `MANAGED` or `SELF_SERVICE` — see below |
| `businessRole` | No | `SUPER_ADMIN` (business creator) or `MEMBER` |
| `name` | Yes (self / super admin) | Display name |
| `email` | Yes | Email address |
| `isEmailVerified` | System | Always `false` (verification not yet implemented) |
| `phone` | Yes | International format e.g. `+628123456789` |
| `isPhoneVerified` | System | Always `false` (verification not yet implemented) |

The following are **business-level** (shared by all of the business's users); any member may read them, but **only the `SUPER_ADMIN` may edit them** (a plain member gets `403`):

| Field | Description |
|---|---|
| `businessName` | Current business name |
| `businessAddress` | Current business address |
| `bankName` / `bankNumber` / `bankAccountName` | Payout bank details |
| business picture | Shared image (see `/api/business/picture`) |
| `size` / `industry` / `storePhoto` | **Immutable** — set once at registration, returned only on login |

> `nmid` is **not** here: it belongs to the QRIS, is parsed from the QRIS code on upload, and is read from `GET /api/qris`. It is neither editable via business info nor returned by login / `/me`.


### Account types

`account_type` records **how the QRIS merchant account was provisioned** — a separate axis from `businessRole` (which is the user's power *within* its business).

| Value | Description |
|---|---|
| `MANAGED` | Bejo registered this merchant with the QRIS payment provider, so it **receives inbound QRIS payment callbacks** (seed data / internal provisioning) |
| `SELF_SERVICE` | Self-registered through the public API; no provider registration, so no inbound callbacks by default (QRIS optional) |

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

> **`hasOwnQris`** tells the app whether this account monitors a QRIS of its own — i.e. whether it runs a QRIS merchant app and sees its own incoming-payment notifications. It is returned on **login** and **`GET /api/users/me`**:
> - **super admin** — `true` once the business has a QRIS at all (registration creates none, so it is `false` until the first [upload](#post-apiqrisupload--upload-business-qris-super-admin)); the shared business QRIS is theirs to monitor.
> - **sub-account (`MEMBER`)** — `true` only when the super admin assigned them their **own** [per-user QRIS](#per-user-qris). A member who shares the business QRIS gets `false`, because it is the super admin who monitors it.
>
> **Rate limited.** After **10 consecutive failed attempts** for the same username the account is locked for **15 minutes** and further attempts return **`429 Terlalu banyak percobaan masuk. Coba lagi dalam N menit.`** — including attempts with the correct password. Any successful login resets the counter, so ordinary typos never lock a user out. Configurable via `LOGIN_MAX_FAILURES` / `LOGIN_LOCKOUT_MINUTES`.

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
    "id": 1,
    "isEmailVerified": false,
    "phone": "+628100000000",
    "isPhoneVerified": false,
    "businessRole": "SUPER_ADMIN",
    "hasOwnQris": true,
    "businessName": "Alice Shop",
    "businessAddress": "Jl. Contoh No. 1",
    "size": "Mikro",
    "industry": "Makanan & Minuman",
    "storePhoto": null,
    "bankName": "Bank BCA",
    "bankNumber": "1234567890",
    "bankAccountName": "Alice",
    "profilePicture": null,
    "accountSettings": { "monitoredApps": {}, "includeKeywords": [], "excludeKeywords": [], "blacklistEnabled": true, "whitelistEnabled": false, "qrisWithProfilePicture": false, "inventoryEnabled": false, "receiveAds": false, "teamManagementEnabled": false },
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 604800
  }
}
```

`accountSettings` and the business `profilePicture` are **bundled** so the client can render its home screen in one round trip; the individual endpoints ([app settings](#account-settings), [business picture](#user-profile)) and `GET /api/users/me` still return them on their own. `profilePicture` is `null` when unset (omitted from the envelope's non-null fields). `id` is the user's own id (use it to identify yourself in the [members](#business-members) list). **`size`, `industry`, and `storePhoto` are immutable (set once at registration) and appear only here — not on `/me` — so cache them from this login response.**

**Errors:** `400 Invalid credentials`, `400 username is required`, `400 deviceId is required` (field validation)

**`403` — email not verified.** Checked only **after the password was right** (so it never reveals which usernames exist), and nothing is created for the attempt — no session, push token or JWT:

```json
{ "status": 0, "message": "Email belum diverifikasi. Masukkan kode verifikasi yang dikirim ke email Anda",
  "data": { "reason": "EMAIL_NOT_VERIFIED", "email": "a***@example.com" } }
```

The client reads `data.reason` and moves the user to the verification step ([`verify-email` / `resend-verification`](#email-verification--post-apiauthverify-email-and-post-apiauthresend-verification)). Who it applies to: **owners** (self-service registrations, and an owner who changes their own email). **Sub-accounts (`MEMBER`) never need to verify** — the owner who adds them vouches for them — so a member can always log in, including members created before verification existed; the seed admin is created verified.

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

#### Forgotten password — `POST /api/auth/forgot-password` and `POST /api/auth/reset-password`

Two public calls (no login — that is the point) that still need `X-API-TOKEN`. The app asks for a code, the user reads the 6-digit code from their email, and the app sends code + new password.

**1. Request a code** — `POST /api/auth/forgot-password`

```json
{ "username": "budi" }
```

**Always HTTP 200** with `{ "status": 1, "message": "Jika akun terdaftar, kode reset telah dikirim ke email Anda" }` — the same answer whether or not the username exists, has an email, or was rate-limited, so the endpoint cannot be used to discover accounts. The app should therefore always show *"if the account exists, we sent a code to your email"* and a **60-second resend countdown**. The code goes to the email registered on the account; it is valid for **15 minutes**, works **once**, and a new request replaces the previous code.

Behind the scenes a request sends nothing when the user has already requested a code in the last **60 seconds** or **5 times in the past hour**, or when the account's **email was changed in the last 24 hours** (so a stolen login cannot swap in its own address and take the account over). Responses take about 1.5 s on purpose, for the same reason.

**Errors:** `503` — email is not configured on the server (the feature is off); `400` — `username` missing.

**2. Set the new password** — `POST /api/auth/reset-password`

```json
{ "username": "budi", "code": "482913", "newPassword": "NewPass123" }
```

`code` is exactly 6 digits; `newPassword` follows the registration rules (8+ characters, at least one letter and one digit).

**Success — HTTP 200:** `{ "status": 1, "message": "Kata sandi berhasil diubah. Silakan masuk dengan kata sandi baru" }`. The password is changed, **every existing login session on every device is ended** (the app must send the user back to the login screen), any login lockout is lifted, and the email is marked verified.

**Errors:** `400 Kode tidak valid atau sudah kedaluwarsa` — the **single message for every failure** (wrong code, expired, already used, too many attempts, unknown user). A code allows **5 guesses**; after that the user must request a new one. Field-validation errors (not 6 digits, weak password) are also `400`.

---

#### Email verification — `POST /api/auth/verify-email` and `POST /api/auth/resend-verification`

Two public calls (they come *before* a session exists) that need only `X-API-TOKEN`; the 6-digit code is the credential. A code is emailed at registration (and when an owner changes their email); the owner's login is refused until it is confirmed. Sub-accounts are exempt.

**1. Confirm the code** — `POST /api/auth/verify-email`

```json
{ "username": "alice", "code": "482913" }
```

**Success — HTTP 200:** `{ "status": 1, "message": "Email berhasil diverifikasi. Silakan masuk" }`; the user then logs in normally (verification does **not** log them in).

**Errors:** `400 Kode tidak valid atau sudah kedaluwarsa` — the **single message for every failure** (wrong code, expired, already used, too many attempts, unknown user, already verified). A code allows **5 guesses** and lives **15 minutes**; the hash covers the address, so a code sent to an old address never confirms a newer one. Field validation: `code` must be exactly 6 digits.

**2. Ask for another code** — `POST /api/auth/resend-verification`

```json
{ "username": "alice" }
```

**Always HTTP 200** with the same message whether or not the username exists or is already verified (no account enumeration; the response is padded to ~1.5 s). A code is sent only to an existing, unverified account that has not asked within the last **60 seconds** or **5 times in the past hour**; a new code replaces the previous one. A code that could not be delivered does not count against those limits. **Errors:** `503` — email is not configured.

When an **owner** changes their email with `PATCH /api/users/me`, the account becomes unverified and a code goes to the **new** address at once (the cooldown does not apply, the hourly cap does), so their next login needs it. A sub-account that changes its email stays verified and is sent nothing. Resetting a forgotten password also verifies the email (the user proved they read that inbox).

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
      "status": "SUCCESS",
      "acquirer": "AstraPay",
      "issuerName": "BNI",
      "merchantPan": "936009XXXXXX0001",
      "customerPan": "401177XXXXXX5678",
      "rrn": "000100000002",
      "createdBy": "budi_kasir",
      "updatedBy": "budi_kasir"
    }
  ],
  "count": 1,
  "page": 1,
  "totalPages": 1,
  "total": 1
}
```

`issuerName`, `merchantPan`, `customerPan`, `rrn` are omitted when null. `count` is the number of rows in this page; `page` is the current (1-based) page, `totalPages` the number of pages, `total` the total matching rows.

`status` is `SUCCESS` for a paid transaction, `AWAITING_PAYMENT` for a [QRIS sale that has not been paid yet](#awaiting-payment-qris-sales) and `CANCELLED` for one that was still unpaid when its day ended (the rest — `PENDING`, `FAILED`, `REFUNDED`, … — come from the QRIS callback). `paidAt` is present only on a sale that waited for payment and has since been paid. `autoCancelAt` is present only on a sale still `AWAITING_PAYMENT`: when it will be cancelled if nobody pays (the coming midnight WIB, or the end of its own day if it is dated ahead).

`createdBy` / `updatedBy` identify **which account or sub-account** created and last edited the transaction (username). They are present on MANUAL/PARSED transactions and **omitted** on MANAGED (server-recorded callback) rows; `updatedBy` is omitted until the transaction is first edited.

---

#### `GET /api/transactions/summary`

Aggregated totals for the merchant dashboard — the summed `amount` and transaction `count` for **today**, **yesterday**, and the **current calendar month**. Introduced because the history endpoint is now paginated, so the client can no longer sum a full page. Soft-deleted transactions are excluded, and so are sales still [awaiting payment](#awaiting-payment-qris-sales) and `CANCELLED` ones — neither is income.

**Query parameters**

| Parameter | Format | Description |
|---|---|---|
| `date` | `yyyy-MM-dd` | The client's local "today" (optional). Pass it so the day/month buckets match the merchant's device; defaults to the current date in the app timezone (**WIB, UTC+7**) when omitted. |

**Success — HTTP 200**

```json
{
  "status": 1,
  "data": {
    "date": "2026-07-15",
    "today": { "totalAmount": 25000, "count": 2 },
    "yesterday": { "totalAmount": 7000, "count": 1 },
    "month": { "totalAmount": 37000, "count": 4 }
  }
}
```

`month` covers the whole calendar month that `date` falls in (and includes `today`/`yesterday` when they're in that month). Every period is always present; empty ranges return `totalAmount: 0, count: 0`.

---

#### `POST /api/transactions`

Adds a transaction manually or from an external acquirer. The server generates `transactionId`.

**Required header:** `Idempotency-Key: <uuid>` — resubmitting the same key returns the existing `transactionId` without inserting a duplicate.

**Optional header:** `X-Device-Id: <device id>` — the device recording the transaction (the app's `ANDROID_ID`, the same value it sends as `deviceId` at login). Used only to keep that one device out of the `PARSED` push below. Omit it and nothing is filtered.

**Request body**

| Field | Required | Description |
|---|---|---|
| `type` | yes | `MANUAL` or `PARSED` |
| `transactionDate` | yes | ISO 8601 e.g. `2026-06-28T09:14:00` |
| `amount` | yes | Amount in IDR, must be > 0. **When `items` are present it is overridden** by the sum of line subtotals |
| `acquirer` | PARSED only | Acquirer name; required when `type` is `PARSED` |
| `issuerName` | no | Payment issuer name, max 255 chars. For a `MANUAL` entry this is the **Sumber Dana** (the app offers `Tunai`, `Transfer`, `Kartu Kredit`, `Kartu Debit`, `QRIS`, `Lainnya`); **`QRIS` starts the sale as `AWAITING_PAYMENT`** — see [below](#awaiting-payment-qris-sales). Any other value, or none, is recorded as paid |
| `merchantPan` | no | Max 50 chars |
| `customerPan` | no | Max 50 chars |
| `rrn` | no | Max 255 chars |
| `items` | no | Products sold: array of `{ productId, quantity }` (max 200). Validates + reduces stock, records a `SALE` [stock mutation](#stock-mutations), and derives `amount` from the line subtotals |

When `items` are supplied the sale is atomic: an unknown product, a duplicate product line, or **insufficient stock** rejects the whole transaction (`400`, nothing changes). Each line's unit price is snapshotted from the product's current selling price. Read them back on `GET /api/transactions/{transactionId}` (the history list omits items).

The creating account/sub-account is recorded as the transaction's `createdBy` (returned by the list/detail endpoints).

> **A `PARSED` payment can settle a sale that is waiting for payment** instead of being recorded as a new transaction — then the response carries *that sale's* `transactionId`. See [Awaiting payment](#awaiting-payment-qris-sales).

> **PARSED transactions skip the DEVICE that reported them — not the account.** A `PARSED` transaction is a real-time payment an app detected off a QRIS merchant notification. That device already announced it locally before calling the API, so pushing it back is an echo. But a merchant is often signed in on a second phone or a counter tablet, and those have heard nothing — so only the reporting device is dropped, identified by `X-Device-Id`.
>
> Who receives it:
> - the **super admin** — always (oversight of every sale);
> - every sub-account **without** its own [per-user QRIS](#per-user-qris), whoever reported it. A sub-account with its own QRIS runs a separate merchant app and sees its own payments there, so it is never in that cohort;
> - **the account that reported it**, whoever they are — so their *other* devices still announce the sale. Only the one device that already played it is dropped.
>
> Send `X-Device-Id` and that device is filtered out at delivery; omit it (older app builds) and the reporter receives its own echo, as before. A solo super admin on a single device therefore has nobody left to notify and no push goes out.
>
> This differs from a **MANAGED** transaction recorded by the [QRIS callback](#qris-callback): there the server hears about the payment first, nothing has announced locally, so everyone — super admin included, every device — is pushed.
>
> The push is best-effort (a delivery failure never fails the transaction) and only fires for the single `POST /api/transactions` path — **not** bulk sync.

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

#### `GET /api/transactions/{transactionId}`

Returns a single transaction **with its sold-product line items** (`data.items`). The [history list](#get-apitransactions) omits items to keep its payload small; this detail endpoint includes them. `items` is absent when the transaction has none.

```json
{
  "status": 1,
  "data": {
    "transactionId": "TXN-A1B2C3D4E5F6", "transactionDate": "2026-06-28T09:14:00", "amount": 61000,
    "type": "MANUAL", "acquirer": "Cash", "merchantName": "Warung Bejo",
    "items": [
      { "productId": 12, "sku": "KOPI-01", "name": "Kopi Susu", "quantity": 3, "unitPrice": 15000, "subtotal": 45000 },
      { "productId": 15, "sku": "TEH-01",  "name": "Teh Manis",  "quantity": 2, "unitPrice": 8000,  "subtotal": 16000 }
    ]
  }
}
```

**Errors:** `404 Transaction not found` (unknown, deleted, or another business's transaction)

---

#### `PATCH /api/transactions/{transactionId}`

Edits a transaction. Partial update — only the fields present in the body are changed; `type` cannot be changed. Only the authenticated user's own (non-deleted) transactions of type `MANUAL` or `PARSED` can be edited — `MANAGED` transactions are pushed by the Bejo server and are immutable.

**Request body** (all fields optional, at least one required)

| Field | Description |
|---|---|
| `transactionDate` | ISO 8601 e.g. `2026-06-28T09:14:00` |
| `amount` | Amount in IDR, must be > 0 |
| `acquirer` | Acquirer name, max 255 chars, not blank |
| `issuerName` | Payment issuer name (Sumber Dana), max 255 chars. On a sale that is `AWAITING_PAYMENT`, changing it to anything other than `QRIS` marks the sale **paid** (it was settled another way). A paid transaction never goes back to waiting |
| `merchantPan` | Max 50 chars |
| `customerPan` | Max 50 chars |
| `rrn` | Max 255 chars |
| `items` | **Full replacement** of the sold-product line items (array of `{ productId, quantity }`, max 200). See below. |

**Editing `items`** — providing `items` replaces the transaction's line items and **reconciles stock by the per-product delta** against the existing ones: a line whose quantity goes **up** (or a newly added product) draws that much **more** stock down (validated — an unknown product, a duplicate line, or insufficient stock for the increase returns `400` and nothing changes); a line whose quantity goes **down** or is **removed** **returns** stock. It's atomic, records one `SALE` [stock mutation](#stock-mutations) for the net change, and — for a non-empty list — **re-derives `amount`** from the line subtotals. An **empty list** (`[]`) clears all items and returns their stock (amount keeps its scalar value); omitting `items` (null) leaves them unchanged. The response includes the post-edit `items`.

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

**Errors:** `400 Tidak ada field yang diubah`, `400 Transaksi bertipe MANAGED tidak dapat diubah`, `400 amount harus lebih dari 0`, `400 acquirer tidak boleh kosong`, `400 Stok tidak cukup untuk … (tersedia N, diminta tambahan M)`, `400 Produk tidak ditemukan: <id>`, `400 Produk duplikat dalam satu transaksi: <id>` (item edits), `404 Transaction not found` (unknown, deleted, or another user's transaction)

---

#### `POST /api/transactions/{transactionId}/mark-paid`

Marks a sale that is **waiting for payment** as paid ("Sudah Terbayar"), by hand — for when the payment was not detected by the app or came another way. Any user of the business may do it (like editing). No body.

Sets `status` to `SUCCESS` and `paidAt` to now, stamps `updatedBy`, and from then on the amount counts in the [summary](#get-apitransactionssummary). Repeating it on a sale that was already paid just returns it.

**Success — HTTP 200** — the updated transaction, same shape as `GET /api/transactions/{transactionId}`.

**Errors:** `400 Transaksi ini tidak menunggu pembayaran` (it was never awaiting payment), `400 Transaksi ini sudah dibatalkan` (it was cancelled at the end of its day), `404 Transaction not found` (unknown, deleted, or another business's transaction)

---

#### Awaiting payment (QRIS sales)

A merchant often rings up a QRIS sale before the customer has scanned. Recording it as `QRIS` ("Sumber Dana") therefore makes it **`AWAITING_PAYMENT`** ("Menunggu Pembayaran") rather than income:

- It appears in the history list with that status but is **left out of the [summary](#get-apitransactionssummary)** totals.
- It becomes **`SUCCESS`** ("Sudah Terbayar") and gets a **`paidAt`** ("Tanggal Pembayaran") in one of three ways:
  1. **A matching payment is detected.** A `PARSED` `POST /api/transactions` for the **exact same amount** settles the **oldest** waiting sale of that amount in the same business, and is **merged into it** — no second transaction is created (that would count the money twice), so the response returns the waiting sale's `transactionId`. Each payment settles at most one sale. Retrying the same request (same `Idempotency-Key`) answers with the same sale and settles nothing else. If nothing is waiting for that amount, the payment is recorded as a new `PARSED` transaction, as always.
  2. **Someone marks it paid** — [`POST /api/transactions/{transactionId}/mark-paid`](#post-apitransactionstransactionidmark-paid).
  3. **Its Sumber Dana is edited to something other than `QRIS`** (the customer paid cash instead).
- **A sale still waiting when its day ends is cancelled automatically.** Just after midnight (WIB) every active `AWAITING_PAYMENT` transaction dated before the new day becomes **`CANCELLED`**, and its sold products go back to stock (a `SALE` [stock mutation](#stock-mutations) with note `Sale cancel …`). A cancelled sale is not income, is never matched to a payment, cannot be marked paid or edited (`400`), and can still be deleted by the super admin (its stock is not returned a second time). The same sweep also runs on the first history request of each day, so a late scheduler never shows yesterday's sale as still waiting.
- Only `MANUAL` sales wait. Stock is taken when the sale is recorded, as for any sale; deleting a waiting sale returns it.

---

#### `DELETE /api/transactions/{transactionId}` (super admin)

Soft-deletes a transaction: the row is kept in the database with a `deleted_at` timestamp and disappears from history. Deletion cannot be undone via the API.

**Super-admin-only** — a sub-account (`MEMBER`) gets `403`, even for a transaction it created itself. (Creating and editing transactions stay open to any member; only deletion is restricted.)

**Stock return:** if the transaction has sold-product [line items](#post-apitransactions), deleting it **returns each item's quantity to its product's stock** and records a `SALE` reversal [stock mutation](#stock-mutations). (A product deleted since the sale is skipped.) The line-item records are kept; stock is returned exactly once.

**Success — HTTP 200**

```json
{
  "status": 1,
  "message": "Transaksi berhasil dihapus"
}
```

**Errors:** `403 Hanya super admin yang dapat mengubah pengaturan bisnis` (a sub-account), `404 Transaction not found` (unknown, already deleted, or another business's transaction)

---

### Voice input

#### `POST /api/voice/amount`

"Say the amount": a few seconds of the merchant speaking, in; what was heard and the rupiah amount it comes to, out.
Used by the microphone on the **Nominal** field of *Catat Transaksi Manual* and *Buat QRIS dengan Nominal*. The app
only fills the field with the answer. Any signed-in user. See [docs/architecture/voice.md](docs/architecture/voice.md).

**Request body**

| Field | Required | Description |
|---|---|---|
| `audio` | yes | Base64 of raw **16-bit little-endian mono PCM** — no container, no header. Between 0.3 s and 10 s. **End the recording with about a second of silence**: the recogniser drops a last word that ends the audio ("dua puluh lima ribu" is heard as "25") |
| `sampleRateHertz` | no | 8000–48000, default `16000` |

**Success — HTTP 200**

```json
{
  "status": 1,
  "message": "Nominal terdengar",
  "data": { "transcript": "dua puluh lima ribu", "amount": 25000, "confidence": 0.92 }
}
```

`amount` is absent when speech was heard but held no usable number, or the recogniser's confidence was below `VOICE_MIN_CONFIDENCE` (0.5) (`message`: *Nominal tidak terdengar jelas*; the words are still returned);
`transcript` and `confidence` are absent when nothing was heard (*Suara tidak terdengar*). Words, digits, abbreviations
and slang are understood: `25000`, `25.000`, `2,5 juta`, `20rb`, `dua setengah juta`, `seribu lima ratus`, `goceng` (5.000),
`ceban` (10.000), `noban` (20.000), `gocap` (50.000), `dua ceban` (20.000) … (`cepek` is not understood: it means Rp100 or Rp100.000 depending on who you ask)

**Errors:** `400 Suara terlalu pendek` / `Suara terlalu panjang (maksimal 10 detik)` / `audio bukan base64 yang valid` /
`audio harus PCM 16-bit`, `429` more than 12 requests in a minute, `503 Input suara belum tersedia. Ketik nominalnya dulu ya.`
(speech recognition unavailable — the field can still be typed)

The audio is held in memory for the call and is **not stored or logged**; neither is the transcript.

---

### Notifications

#### Push payloads

**Every push Bejo sends is a data-only FCM message** — there is no `notification` block on the wire, so FCM displays nothing by itself and the **app renders each push**. That's deliberate: a notification-block message is drawn by the system tray and never reaches `onMessageReceived` while the app is backgrounded, which would leave the app no way to react (auto-show a dialog, choose how to alert). Data-only hands every delivery to the app. The tradeoff: a **force-stopped** app shows nothing at all.

Since nothing is pre-rendered, **`data.type` is how the app tells the kinds apart**, and every payload carries `title` + `body` for the app to display:

| `type` | Sent when | Also carries |
|---|---|---|
| `TRANSACTION` | A QRIS payment lands ([callback](#qris-callback)) — to **every device of every user** in the business | `transactionId`, `transactionDate`, `amount`, `status`, `issuerName`, `merchantName`, `merchantPan`, `customerPan` |
| `PRESENCE` | An [attendance](#employee-presence) event — a clock-in/out to the **super admin**, a ping to the **member** | `presenceType` (`CLOCK_IN` / `CLOCK_OUT` / `PING`), `presenceId`, `userId`, `username`, `clockDatetime` |
| `ADS` | A [voice-ad](#voice-ads) step — to the **one target device** | `adEvent` (`PREPARE` / `PLAY`), `campaignId`, `deliveryId`, `campaignName`; PREPARE also carries `audioUrl`, `sha256`, `sizeBytes`, `contentType` |

Transaction, presence and ads pushes all go out at **Android priority `high`**, so FCM delivers them at once and wakes the app even in Doze — the app runs its announcement/dialog on arrival — rather than batching them until the device next wakes.

`TRANSACTION` pushes carry the amount **already formatted for display** in `body` — Indonesian currency, `.` grouping thousands and `,` for decimals, with decimals shown only when non-zero (never padded to `,00`). The raw integer is still in `data.amount` if the app needs to compute with it.

```json
// TRANSACTION — the app announces body as-is; amount stays raw for any local formatting
{ "type": "TRANSACTION", "transactionId": "TXN-A1B2C3D4",
  "title": "Pembayaran Diterima", "body": "Pembayaran Rp 1.000.000 diterima",
  "amount": "1000000", "status": "SUCCESS", "issuerName": "AstraPay",
  "merchantName": "Warung Alice", "transactionDate": "2026-06-28T09:14:00" }
```

```json
// PRESENCE ping — the app can open the response dialog for presenceId 13
{ "type": "PRESENCE", "presenceType": "PING", "presenceId": "13",
  "title": "Ping Absen Bejoo!", "body": "Halo Mas/Mbak Bejo Sekalian. Masih pada Semangaat?",
  "userId": "4", "username": "budi", "clockDatetime": "2026-03-10T14:00" }
```

```json
// ADS prepare — the app downloads audioUrl, verifies sha256+sizeBytes, then reports to deliveryId 7
{ "type": "ADS", "adEvent": "PREPARE", "campaignId": "3", "deliveryId": "7",
  "campaignName": "Promo Kopi", "audioUrl": "https://storage.googleapis.com/…(24h signed)",
  "sha256": "9f86d081…", "sizeBytes": "84044", "contentType": "audio/mpeg" }
```

**What the app can and can't do on arrival:** with the app in the **foreground** it can show a dialog immediately. **Backgrounded**, Android (10+) forbids starting an Activity from a push — the app cannot auto-open itself; the realistic behaviour is a high-importance heads-up notification the app posts itself, which opens the dialog when tapped. (A full-screen intent does auto-launch on a locked screen, but Android 14+ and Play policy restrict that permission to calling/alarm apps.)

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

#### `POST /api/notifications/user`

**Test/utility** — broadcasts an FCM push to **all** of the caller's devices at once (no `deviceId`). Best-effort: a dead token never fails the call (and is auto-cleaned), and delivery to zero devices still returns `200`. Does **not** record a transaction.

**Request body** — same as the test endpoint but **without** `deviceId`:

| Field | Required | Description |
|---|---|---|
| `title` | yes | Notification title |
| `body` | yes | Notification body text |
| `data` | yes | Same object as `POST /api/notifications/test` (`transactionId`, `transactionDate`, `amount`, `issuerName`, optional `merchantPan`/`customerPan`/`rrn`) |

**Success — HTTP 200:** `{ "status": 1, "message": "Notification sent to N device(s)" }`

**Errors:** `400` field validation, `503 Firebase is not configured`

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

Self-service registration. Creates a **new business** and its first user as the `SUPER_ADMIN` (`SELF_SERVICE`) + bank profile. **No QRIS row is created — QRIS is optional** and can be added later via `POST /api/qris/upload`. `X-API-TOKEN` required, no JWT.

> **The account cannot log in until its email is verified.** Registration never verifies it: it emails a 6-digit code to the address given, and the user confirms it with [`POST /api/auth/verify-email`](#email-verification--post-apiauthverify-email-and-post-apiauthresend-verification) before the first login. **There is no phone number at registration** — the user adds one later from `PATCH /api/users/me` (a `phone` sent here by an older client is ignored). **An email address belongs to one active user** (case-insensitive). If the server has no email configured, registration answers `503` and creates nothing, because the account could never be verified.

**Request body**

| Field | Required | Validation |
|---|---|---|
| `username` | yes | 3–50 chars, alphanumeric + underscore, unique |
| `password` | yes | Min 8 chars, at least 1 letter and 1 digit |
| `name` | yes | 2–100 chars |
| `email` | yes | Valid email format; unique among active users. The verification code is sent here |
| `businessName` | yes | Max 255 chars |
| `businessAddress` | no | Free text |
| `merchantPan` | no | Max 50 chars — QRIS merchant PAN; the payment callback carries this (not the NMID), so it's how a callback routes to this business |
| `bankName` | no | Max 100 chars |
| `bankNumber` | no | Digits only, 6–20 digits |
| `bankAccountName` | no | Max 100 chars |
| `size` | no | Business size — one of the configured options (default `Mikro`): `Mikro`, `Kecil`, `Menengah`, `Besar`, `Non-Profit` |
| `industry` | no | Business industry — one of the configured options (default `Makanan & Minuman`): `Makanan & Minuman`, `Otomotif`, `Event & Hiburan`, `Alat Tulis/Kantor`, `Peralatan Rumah Tangga`, `Pakaian & Aksesoris`, `Kesehatan & Kecantikan`, `Layanan Keuangan`, `SPBU`, `Pendidikan`, `Asuransi` |
| `storePhoto` | no | Base64 store photo, **≤ 1 MB decoded** (same spec as the business picture) |

> **`size`, `industry`, and `storePhoto` are set once at registration and are immutable** — they are **not** editable via `PATCH /api/users/me/business`, and are returned **only on login** (not on `/me`), so the client caches them from the login response. The `size`/`industry` option lists (and defaults) are server-configurable via `app.business.*` — an invalid value returns `400 size tidak valid …` / `industry tidak valid …`.

```json
{
  "username": "alice",
  "password": "Secret1",
  "name": "Alice",
  "email": "alice@example.com",
  "businessName": "Alice Shop",
  "businessAddress": "Jl. Contoh No. 1",
  "size": "Mikro",
  "industry": "Makanan & Minuman"
}
```

**Responses**

| HTTP | Result |
|---|---|
| 201 | `{ "status": 1, "message": "Registrasi berhasil. Masukkan kode verifikasi yang dikirim ke email Anda", "data": { "emailVerificationRequired": true, "emailSent": true, "email": "a***@example.com" } }` — `email` is masked. `emailSent` is `false` (and the message says the code has not been sent) when the mail server was unreachable: the account exists, and the client offers "resend" |
| 400 | Validation error — `username wajib diisi`, `password harus mengandung minimal 1 huruf dan 1 angka`, `format email tidak valid`, etc. |
| 400 | `Username sudah digunakan` / `Email sudah digunakan` |
| 503 | `Verifikasi email belum tersedia. Coba lagi nanti.` — email is not configured; nothing was created |

---

#### Business members

Managing the users of a business. All require JWT. **Only the `SUPER_ADMIN` may add, update, or delete users**; any member may list them. A super admin can never be modified or deleted here.

##### `GET /api/users/members` — List members

Returns the business's active users (`data` array + `count`), the super admin first.

```json
{
  "status": 1,
  "count": 2,
  "data": [
    { "id": 1, "username": "alice", "name": "Alice", "email": "alice@example.com", "phone": "+628100000000", "businessRole": "SUPER_ADMIN", "createdAt": "..." },
    { "id": 2, "username": "member1", "name": "Member One", "email": "member1@example.com", "phone": "+628100000001", "businessRole": "MEMBER", "createdAt": "..." }
  ]
}
```

##### `POST /api/users/members` — Add member (super admin only)

Adds a user to the caller's business as a `MEMBER`. Same identity fields as registration, **without** the business/bank fields (they belong to the existing business).

| Field | Required | Validation |
|---|---|---|
| `username` | yes | 3–50 chars, alphanumeric + underscore, unique among active users |
| `password` | yes | Min 8 chars, at least 1 letter and 1 digit |
| `name` | yes | 2–100 chars |
| `email` | yes | Valid email |
| `phone` | yes | International format e.g. `+628123456789` |

| HTTP | Result |
|---|---|
| 201 | `{ "status": 1, "message": "Pengguna berhasil ditambahkan", "data": { ...member... } }` |
| 400 | `Username sudah digunakan` / `Jumlah pengguna bisnis sudah mencapai batas maksimal (5)` / validation error |
| 403 | `Hanya super admin yang dapat mengelola pengguna` (caller is a plain member) |

##### `PATCH /api/users/members/{id}` — Update member (super admin only)

Partial update of a member: any subset of `name`, `email`, `phone`, `password` (a `password` resets it). A super admin can't be modified here.

| HTTP | Result |
|---|---|
| 200 | `{ "status": 1, "message": "Pengguna berhasil diperbarui", "data": { ...member... } }` |
| 400 | `Tidak ada field yang diubah` / validation error |
| 403 | Caller not a super admin, or target is a super admin (`Super admin tidak dapat diubah`) |
| 404 | `User not found: <id>` (unknown or another business's user) |

##### `DELETE /api/users/members/{id}` — Delete member (super admin only)

**Soft-deletes** the member — the row is kept but the account is removed, its **username is released** for reuse, and its device sessions are cleared.

| HTTP | Result |
|---|---|
| 200 | `{ "status": 1, "message": "Pengguna berhasil dihapus" }` |
| 403 | Caller not a super admin, or target is a super admin (`Super admin tidak dapat dihapus`) |
| 404 | `User not found: <id>` |

---

### User profile

All endpoints below require JWT.

#### `GET /api/users/me` — Get own account bundle

Returns the caller's own account: `id`, profile, `businessRole`, and the shared business/bank/`profilePicture`/`accountSettings` — the **same shape as the login `data` minus the token fields**, so the client can refresh its full state without re-authenticating. `id` also lets the client identify itself in the [members](#business-members) list.

**Success — HTTP 200:** `{ "status": 1, "data": { "id": 1, "username": "alice", "businessRole": "SUPER_ADMIN", "hasOwnQris": true, ...business/bank/profilePicture/accountSettings... } }`

---

#### `PATCH /api/users/me` — Update personal info

| Field | Required | Validation |
|---|---|---|
| `name` | yes | 2–100 chars |
| `email` | yes | Valid email |
| `phone` | yes | International format e.g. `+628123456789` |

**Success — HTTP 200:** `{ "status": 1, "message": "Profile updated successfully" }`

---

#### `PATCH /api/users/me/business` — Update business info (super admin)

Super-admin-only (the business info is shared) — a plain member gets `403 Hanya super admin yang dapat mengubah pengaturan bisnis`. Does not affect the initial values stored at registration.

| Field | Required | Validation |
|---|---|---|
| `businessName` | yes | Max 255 chars |
| `businessAddress` | no | Free text |

**Success — HTTP 200:** `{ "status": 1, "message": "Informasi bisnis berhasil diperbarui" }`

---


#### `POST /api/users/me/upgrade` — Upgrade to a Bejo-managed account (super admin)

Upgrades a **self-service** account to **managed by Bejo**: sets the business's `merchantPan` + `nmid` (the QRIS-callback routing credentials, **both required** — a missing/blank value is `400`) and flips the account's `account_type` to `MANAGED`. Super admin only. Returns the refreshed `/me` bundle.

**Request body:** `{ "merchantPan": "9360082232606290012", "nmid": "ID1026540778978" }`

**Errors:** `400 merchantPan wajib diisi` / `400 nmid wajib diisi`, `400 Akun sudah dikelola Bejo` (already managed), `409 NMID sudah digunakan oleh bisnis lain` / `409 Merchant PAN sudah digunakan oleh bisnis lain`.

**Errors:** `400 Data bisnis awal tidak tersedia untuk direset` (no initial values stored)

---

#### `PATCH /api/users/me/bank` — Update bank info (super admin)

Super-admin-only (the bank/rekening info is shared) — a plain member gets `403 Hanya super admin yang dapat mengubah pengaturan bisnis`. Does not affect the initial values stored at registration.

| Field | Required | Validation |
|---|---|---|
| `bankName` | yes | Max 100 chars |
| `bankNumber` | yes | Digits only, 6–20 digits |
| `bankAccountName` | yes | 2–100 chars |

**Success — HTTP 200:** `{ "status": 1, "message": "Informasi rekening berhasil diperbarui" }`

---


---

#### `PATCH /api/users/me/password` — Change password

| Field | Required | Validation |
|---|---|---|
| `oldPassword` | yes | Current password |
| `newPassword` | yes | Min 8 chars, at least 1 letter and 1 digit |

**Errors:** `400 Password saat ini tidak sesuai`, `400 Password baru minimal 8 karakter`, `400 Password baru harus mengandung minimal 1 huruf dan 1 angka`

> **Changing the password signs the account out everywhere.** Every JWT issued before the change stops working immediately — on this device and every other one — so the client must log in again with the new password to get a fresh token. The same applies when a super admin resets a member's password via `PATCH /api/users/members/{id}`: that member's tokens are revoked at once.

---

> The picture is **business-level** — a single image shared by all of the business's users. **Any member can read it (`GET`), but only the super admin can change it (`PUT`/`DELETE`) — a member gets `403`.** The response/request field is named `profilePicture`.

#### `GET /api/business/picture` — Get business picture

Returns the business picture as a base64 string. The picture is **optional** — when it has never been set, `profilePicture` is explicitly **`null`** (no placeholder image).

**Success — HTTP 200**

```json
{
  "status": 1,
  "data": { "profilePicture": "<base64-encoded image>" }
}
```

---

#### `PUT /api/business/picture` — Set business picture

Stores a base64-encoded image as the business picture (shared by all members). The image should be **compressed client-side**; the decoded image must be **≤ 1 MB**. Returns the saved picture.

**Request body:** `{ "profilePicture": "<base64-encoded image>" }`

**Success — HTTP 200:** `{ "status": 1, "message": "Foto bisnis berhasil disimpan", "data": { "profilePicture": "<base64>" } }`

**Errors:** `400 profilePicture wajib diisi`, `400 profilePicture bukan format base64 yang valid`, `400 profilePicture terlalu besar (maksimal 1MB)`

---

#### `DELETE /api/business/picture` — Remove business picture

Clears the business picture (sets it back to `null`). Idempotent — succeeds whether or not a picture was set.

**Success — HTTP 200:** `{ "status": 1, "message": "Foto bisnis berhasil dihapus" }`

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

---

### Stock mutations

The unified **stock-change history**. Every change to a product's stock is recorded as a *mutation* with a `type`:

| `type` | Source | Priced? |
|---|---|---|
| `PURCHASE` | Restock via `POST /api/stock-mutations` | yes |
| `ADJUSTMENT` | Stock opname via `POST /api/stock-mutations/adjustments` | no |
| `UPDATE` | A `PATCH /api/products/{id}` that changed `stock` | no |

`UPDATE` mutations are created automatically by a product edit (`PATCH /api/products/{id}` that changes `stock`); `PURCHASE` (restock) and `ADJUSTMENT` (stock opname) are **recorded through this resource**, which also **reads the whole history**. All endpoints require JWT, scoped to the merchant. Each line snapshots the product and the stock before/after (`previousStock` → `newStock`, `quantityChange`).

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

#### `POST /api/stock-mutations/adjustments` — Bulk stock adjustment (stock opname)

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

#### `GET /api/stock-mutations` — Stock-change history

Returns mutations (PURCHASE/ADJUSTMENT/UPDATE) newest-first, each **with its line items** (product `sku`/`name` + `previousStock`/`newStock`/`quantityChange`). For non-purchase mutations `unitPrice`/`subtotal` are absent and `totalAmount` is 0. Paginated: optional `page` (default `1`) + `size` (default `50`, max `200`); response includes `count` (this page), `page`, `totalPages`, `total`.

#### `GET /api/stock-mutations/{id}` — Stock mutation detail

Returns one mutation **including its `items`** (each with `previousStock`/`newStock`/`quantityChange`). `404` if not found / not owned.

---

### Employee presence

Staff attendance for the business: members clock in and out with their location, the super admin can ping the team for a spot-check, and both read the history. All endpoints require JWT. Every entry — clock-in, clock-out, and ping — is one row in the same table, distinguished by `type` (`CLOCK_IN` | `CLOCK_OUT` | `PING`).

**Who may do what:** only a **member** clocks in/out (a super admin gets `403` — they don't take attendance); only the **super admin** sends pings; a member responds only to **their own** ping. On the history the super admin sees the whole business, a member only their own entries.

**Once per day:** a member may clock in **at most once** and clock out **at most once** per calendar day. A repeat is rejected with `400` (`"Kamu sudah absen masuk hari ini"`); the next day resets. Clocking out on the same day as clocking in is of course fine — the limit is one of *each* type.

**A clock-out needs that day's clock-in:** you can't leave a shift you never started (`400` — `"Kamu belum absen masuk hari ini"`). It's keyed on the clock-out's own calendar day, so **a shift never spans midnight** — a clock-out at 01:00 belongs to the new day and needs a clock-in on it.

#### `POST /api/presences/clock-in` — Clock in (member)

```json
{
  "latitude": -6.2088,
  "longitude": 106.8456,
  "clockDatetime": "2026-03-10T08:01:00",
  "note": "Tiba di toko"
}
```

`latitude`, `longitude` and `clockDatetime` are required; `note` (≤255 chars) is optional. The time is **client-supplied**, so an entry made offline keeps its real time when it syncs — but a `clockDatetime` more than a day in the future is rejected (`400`) as a wrong device clock.

`201 Created`:

```json
{
  "status": 1,
  "message": "Absen masuk berhasil dicatat",
  "data": {
    "id": 12,
    "userId": 4,
    "username": "budi",
    "name": "Budi",
    "type": "CLOCK_IN",
    "clockDatetime": "2026-03-10T08:01:00",
    "presenceDate": "2026-03-10",
    "latitude": -6.2088,
    "longitude": 106.8456,
    "note": "Tiba di toko",
    "userActive": true,
    "createdAt": "2026-03-10T08:01:03"
  }
}
```

The business's **super admin** gets a push: title `"budi telah absen masuk!"`, body `"Pastikan jadwal absen sesuai prosedur. Sukses terus ya!"` — see [push payloads](#push-payloads) for the exact data and why. The push is best-effort — it never fails the attendance entry.

#### `POST /api/presences/clock-out` — Clock out (member)

Same body and response shape, with `type: "CLOCK_OUT"`. The super admin's push title becomes `"budi telah absen keluar!"`.

#### `POST /api/presences/pings` — Ping the team, or one member (super admin only)

```json
{ "userId": 4 }
```

The body (and `userId` in it) is optional: **omit it to ping the whole team**, or set it to ping just that one member. "The whole team" means the business's **members** — a ping never goes to the super admin's own devices.

Records one `PING` entry per targeted member and pushes **all of that member's devices**:

> **Ping Absen Bejoo!**
> Halo Mas/Mbak Bejo Sekalian. Masih pada Semangaat?

The ping carries **no note** — the `note` on a PING entry is reserved for the member's reply (see [respond](#post-apipresencesidrespond--acknowledge-a-ping-member)).

**A ping only goes to members that can receive it.** A member with no registered device is left out entirely: writing a PING for them would start their hour-long cooldown for a message nothing could ever deliver.

The response says **how many of the team were actually reached**, so a partial ping never looks like a full one (the app composes whatever sentence it wants from these numbers):

```json
{
  "status": 1,
  "data": {
    "pinged": 2,
    "totalMembers": 4,
    "skipped": [
      { "userId": 5, "username": "siti", "name": "Siti", "reason": "COOLDOWN" },
      { "userId": 6, "username": "andi", "name": "Andi", "reason": "NO_DEVICE" }
    ],
    "presences": [ { "id": 13, "userId": 4, "username": "budi", "type": "PING", "responseDatetime": null } ]
  }
}
```

`reason` is `NO_DEVICE` (no device registered for push) or `COOLDOWN` (pinged within the last hour).

**Cooldown: a member can only be pinged once an hour.** It's a spot-check, not a nag.

| | Behaviour |
|---|---|
| **Targeted** ping to a member pinged < 1h ago (or with no device) | `400` — `"Budi baru saja di-ping. Coba lagi dalam 50 menit"`. You asked for that person; you're told it didn't happen. |
| **Team-wide** ping | Members pinged < 1h ago (or with no device) are **skipped**; everyone else is pinged, and `pinged`/`skipped` shows exactly who. One recently-pinged person can't block the whole team. |
| **Team-wide**, nobody eligible | `400` — nobody is left to reach, and a silent no-op would be worse. |

`400` if `userId` isn't an active member of the caller's own business (an unknown id, another business's staff, or the super admin's own id) — and nothing is sent. `400` if the business has no members yet (team-wide ping); `403` for a non–super-admin.

`201 Created` — the created entries (one per member; `latitude`/`longitude` are null since the admin sends no location, and `responseDatetime` is null until the member taps):

```json
{
  "status": 1,
  "count": 2,
  "data": [
    { "id": 13, "userId": 4, "username": "budi", "name": "Budi", "type": "PING",
      "clockDatetime": "2026-03-10T14:00:00", "presenceDate": "2026-03-10",
      "note": "Masih di toko?", "responseDatetime": null, "createdAt": "2026-03-10T14:00:00" }
  ]
}
```

#### `POST /api/presences/{id}/respond` — Acknowledge a ping (member)

The member taps the ping in the app and replies **with their location**:

```json
{ "latitude": -6.1751, "longitude": 106.8650, "note": "Lagi antar pesanan" }
```

`latitude` + `longitude` are **required** — a ping is a spot-check, so the answer is only worth anything if it says where the member actually is. `note` is optional (free text: where they are, what they're doing).

Stamps `responseDatetime`, the coordinates, and the `note` on the entry, and returns it. **A PING is created with no location and no note — this is the only thing that writes them.** The super admin who sent the ping sets neither; they read the member's reply in the [history](#get-apipresences--attendance-history), next to the response time.

**Within 24 hours.** A ping expires 24 hours after it was sent (`400` — `"Ping ini sudah kedaluwarsa — hanya dapat direspons dalam 24 jam"`). It's a rolling window rather than a calendar date, so a ping sent at 23:00 stays answerable through the night instead of dying an hour later at midnight.

**Once only.** The member may have the app on several devices and can answer from any of them, but only the **first** reply is accepted; a second gets `400` (`"Ping ini sudah direspons"`).

`403` if the ping belongs to someone else, `400` if the location is missing / the entry isn't a `PING` / it is more than 24 hours old / it was already answered, `404` if it isn't in the caller's business.

#### `GET /api/presences` — Attendance history

Newest first. Query params (all optional):

| Param | Meaning |
|---|---|
| `userId` | Filter to one team member. **Super admin only** — a member passing anyone else's id gets `403` (they are always scoped to their own entries). |
| `year` | Calendar year to filter by (2000–2100). Without `month`, covers the whole year. |
| `month` | Month `1`–`12`. **`month` alone means that month of the current year** — `year` is only needed to look at a past one. |
| `page` / `size` | Pagination (default page `1`, size `50`, max `200`). |

Omit both `year` and `month` for the full history. Each entry carries its `type`, `clockDatetime`, `presenceDate`, location, `note`, `responseDatetime` (pings only), and `userActive`, plus `count`/`page`/`totalPages`/`total`.

**Removed staff:** the member's `username`/`name` are snapshotted on each entry, so a removed member's attendance stays in the history rather than vanishing — but it comes back with **`userActive: false`**, so the app can mark it as ex-staff. Entries of current staff are `userActive: true`.

---

### Account settings

Per-**business** settings synced from the app (shared by all of the business's users): the **monitored apps** (which apps' notifications to watch), the **notification keywords** (blacklist/whitelist filters), and app **config toggles**. All endpoints require JWT. **Reading is open to any member; every change (the four `PUT`s) is super-admin-only — a plain member gets `403`.** Settings are created on first save; a business that has never synced gets sensible defaults.

#### `GET /api/account-settings` — Get account settings

Returns the account's settings. When nothing has been synced yet, returns defaults: empty `monitoredApps`/keyword lists, `blacklistEnabled: true`, `whitelistEnabled: false`, all config toggles `false`. Empty lists mean the client should fall back to its own bundled defaults.

**Success — HTTP 200**

```json
{
  "status": 1,
  "message": "Pengaturan berhasil dimuat",
  "data": {
    "monitoredApps": { "id.dana": "dana", "com.gojek.app": "gopay" },
    "includeKeywords": ["uang masuk", "pembayaran diterima"],
    "excludeKeywords": ["promo", "gagal"],
    "blacklistEnabled": true,
    "whitelistEnabled": false,
    "qrisWithProfilePicture": false,
    "inventoryEnabled": true,
    "receiveAds": false,
    "teamManagementEnabled": true
  }
}
```

| Config field | Default | Meaning |
|---|---|---|
| `qrisWithProfilePicture` | `false` | Whether the QRIS image should be composed onto the profile picture |
| `inventoryEnabled` | `true` | Always `true`: product and stock management is on for every business (the stored flag is kept but ignored) |
| `receiveAds` | `false` | Whether the account opted in to receive [voice ads](#voice-ads) |
| `teamManagementEnabled` | `true` | Always `true`: team management (employee accounts + presence) is on for every business (the stored flag is kept but ignored) |

> **Inventory and team management are always on.** The app has no toggles for them; the team-feature writes ([member](#business-members) add/update/delete, [presence](#employee-presence) clock-in/out, ping, respond, per-user QRIS) are never refused, and both flags are always returned as `true`. The settings and the `toggle` endpoint below still exist for older app builds, but the stored values no longer change behaviour.

---

#### `PUT /api/account-settings/monitored-apps` — Save monitored apps

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

#### `PUT /api/account-settings/keywords` — Save notification keywords

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

#### `PUT /api/account-settings/toggle` — Flip a boolean setting

One generic endpoint for all the on/off settings (replaces the former per-setting `PUT`s). **Super admin only** (a member gets `403`). Returns the full updated settings.

**Request body**

```json
{ "setting": "receiveAds", "enabled": true }
```

| `setting` | Meaning |
|---|---|
| `qrisWithProfilePicture` | Compose the QRIS image onto the profile picture |
| `inventory` | Inventory management — **always on**; the value is stored but ignored |
| `receiveAds` | Opt in to receive [voice ads](#voice-ads) (default off) |
| `teamManagement` | Team management (employee accounts + presence) — **always on**; the value is stored but ignored, and login/`/me` always report `teamManagementEnabled: true` |

**Success — HTTP 200:** `{ "status": 1, "message": "Pengaturan disimpan", "data": { ...full settings... } }`

**Errors:** `400 setting wajib diisi`, `400 enabled wajib diisi`, `400 Pengaturan tidak dikenal: <setting>`

For the keyword endpoint: `400 Tidak ada data yang diubah` (all fields null), `400 <field>: kata kunci terlalu panjang: <kw>`, `400 <field>: terlalu banyak kata kunci (maksimal 1000)`

---

### QRIS

> Any member can read the QRIS (`GET /api/qris`), but **only the super admin changes it** — the shared business QRIS (`POST /api/qris/upload`) and each user's own QRIS (`/api/qris/users/*`); a member gets `403`.

#### `GET /api/qris` — Get the caller's effective QRIS

Returns the caller's **effective** QRIS: if the main account [assigned this user their own QRIS](#put-apiqrisusersusername--assign-a-user-their-own-qris-super-admin), that image (with its terminal); otherwise the shared business QRIS. `type` indicates whether the image is Bejo-managed or user-uploaded.

> **Compatibility contract with the app.** The server treats the code as **opaque**: it is stored verbatim and served verbatim, never parsed, normalised or re-ordered. So a payload the app decodes, sends, reads back and re-encodes produces a QR that scans identically to the merchant's original. What the server enforces is only:
> - non-blank, **max 1024 characters**;
> - **no control characters** (a sign of corruption);
> - **non-ASCII is allowed** — EMVCo tag `64` carries merchant name/city in a local script, so a valid payload may contain it.
>
> The server additionally checks the EMV checksum and **logs a warning without rejecting** if it fails, so an unfamiliar acquirer is never blocked. To mirror it, the algorithm is **CRC-16/CCITT-FALSE** (polynomial `0x1021`, init `0xFFFF`, no reflection, no final XOR) over everything up to *and including* the trailing `6304`, compared against the last 4 hex digits. Verified against a real AstraPay payload ending `63042C15`.

> **NMID is not in the login or `/me` response.** It is a property of the QRIS, not the business, so read it from `GET /api/qris` — that way it always matches the QR being displayed, including for a sub-account with its own terminal.

**Two names, deliberately.** `businessName` is what the merchant typed into business info; **`merchantName`, `nmid` and `acquirer` are parsed out of the QRIS code itself** at upload — EMV tag `59`, sub-tag `02` of the `ID.CO.QRIS.WWW` template, the GUID of the other merchant-account template (e.g. `ID.CO.ASTRAPAY.WWW`), and sub-tag `07` of tag `62` respectively. `acquirer` is the raw reverse-domain identifier; map it to a brand name in the app if you want to display one. Caption the QR with the parsed pair, not the business name — a sub-account with its own QRIS has its own merchant identity, and the caption must match the QR on screen. Both are also returned per assignment by `GET /api/qris/users/{id}`.

**QRIS is stored and served as a _code_, not an image.** The app decodes the merchant's QR once and sends the **EMV payload string**; the server stores that and returns it, and the app renders the QR locally. A few hundred bytes travel instead of a multi-MB base64 image.

**QRIS is optional.** A freshly registered merchant has no QRIS record at all, so `GET /api/qris` returns **`404 QRIS data not found`** until they supply one via `POST /api/qris/upload` (which creates the record on first upload). Once a record exists, `qrisCode` **may still be `null`** — e.g. after a reset, the response is `type: MANAGED` with `qrisCode: null`. The server does **not** synthesize a placeholder; the client renders an empty state and prompts the merchant to add their QRIS.

**Success — HTTP 200**

```json
{
  "status": 1,
  "data": {
    "qrisCode": "00020101021126640018ID.CO.ASTRAPAY.WWW…63042C15",
    "merchantName": "Susilo",
    "nmid": "ID1026540778978",
    "acquirer": "ID.CO.ASTRAPAY.WWW",
    "terminalId": "A001",
    "businessName": "Alice Shop",
    "businessAddress": "Jl. Contoh No. 1"
  }
}
```

| `type` | Description |
|---|---|
| `MANAGED` | The account's initial/managed image, or `null` if none has been set |
| `UPLOADED` | User-uploaded image (overrides MANAGED) |

---

#### `POST /api/qris/upload` — Set the business QRIS code

Once uploaded, `GET /api/qris` returns this image with `type: UPLOADED`.

**Request body:** `{ "qrisCode": "00020101021126610014COM…6304ABCD" }` — the EMV payload the app decoded from the merchant's QR, max 1024 chars

**Success — HTTP 200:** `{ "status": 1, "message": "QRIS berhasil diupload" }`

**Errors:** `400 qrisCode wajib diisi`, `400 Kode QRIS wajib diisi` (blank), `400 Format kode QRIS tidak valid` (contains control characters), `400 qrisCode terlalu panjang (maksimal 1024 karakter)`

---


#### `PUT /api/qris/users/{id}` — Assign a user their own QRIS (super admin)

The main account gives one user their own QRIS code; the **terminal id is read out of that code** (same merchant PAN, distinct terminal), so a payment callback routes by terminal. The path `{id}` is the **user id** (the same identifier as `/api/users/members/{id}`). **Super admin only, and team management must be enabled** (`403` otherwise). `terminalId` is unique within the business (`400 Terminal ID sudah digunakan`); the target must be an active user of the caller's business (`400` otherwise).

**Request body:** `{ "qrisCode": "00020101021126610014COM…6304ABCD" }` (max 1024 chars). The terminal id is **parsed from the code** (EMV tag `62` → sub-tag `07`), not supplied — so it can never disagree with the QR. A code without a terminal label stores `null`.

**Success — HTTP 200:** `{ "status": 1, "message": "QRIS pengguna disimpan", "data": { "userId": 7, "username": "...", "qrisCode": "...", "merchantName": "Susilo", "nmid": "ID10265...", "acquirer": "ID.CO.ASTRAPAY.WWW", "terminalId": "A001", "createdAt": "...", "updatedAt": "..." } }`

#### `GET /api/qris/users` · `GET /api/qris/users/{id}` · `DELETE /api/qris/users/{id}` (super admin)

List all per-user assignments (`userId` + `username` + `terminalId`, code omitted), fetch one by user id (with the code), or remove one by user id (the user reverts to the shared business QRIS). `404 QRIS pengguna tidak ditemukan` if not assigned.

---

### QRIS callback

#### `POST /snap/v1.0/qr/qr-mpm-notify`

Inbound QRIS payment notification (SNAP-like QR MPM notify) from the QRIS merchant service (e.g. AstraPay). **Server-to-server — no `X-API-TOKEN` and no JWT.** If `CALLBACK_SECRET` is set the request must carry it as the `X-Callback-Secret` header (else `401`) — set it, because otherwise anyone who can reach this URL can forge a payment notification. Records the payment as a `MANAGED` transaction and stores the **raw payload** in the transaction's `info` column (the provider's `additionalInfo` object is stored under the shorter name).

This endpoint does **not** use the standard `{ status, message, data }` envelope — it returns the callback contract's own acknowledgment shape.

**Routing.** The useful fields arrive under `additionalInfo`: `terminalId`, `merchantPan` (there is **no NMID**), `customerPan`, `transactionId`. The service:
> - **finds the business by `merchantPan`** (`businesses.merchant_pan`) and records the transaction there. A missing or unmatched `merchantPan` is **rejected with `404`** (logged), unless `CALLBACK_FALLBACK_TO_DEFAULT=true`, which attributes it to the default account (`CALLBACK_DEFAULT_USERNAME`) as before;
> - **routes the push by `terminalId`**: the **super admin** always gets it, a user with **no** [per-user QRIS](#put-apiqrisusersusername--assign-a-user-their-own-qris-super-admin) always gets it (they use the shared business QRIS), and a user **with** their own QRIS gets it only when their terminal id matches the callback's. A callback with no `terminalId` rings the whole business.
>
> **Idempotent:** re-delivering the same notification does **not** create a second transaction — see *Idempotency* below.
>
> **⚠️ Still PoC:** `X-TIMESTAMP` is ignored, so the transaction date is the server's `now()` (deliberate — the server clock is trusted over the provider's).

**Headers**

| Header | Mandatory | Description |
|---|---|---|
| `Content-Type: application/json` | ✅ | |
| `X-TIMESTAMP` | optional | ISO-8601 transaction time (offset / `Z` / none). **Ignored** — the transaction date is recorded as the server's `now()`. |

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

- **Attribution** — every callback is recorded against the seed user's business (`app.callback.default-username` → its `business_id`); if that user doesn't exist, returns `404`.
- **Idempotency** — the notification is deduped on the provider's own reference: `additionalInfo.transactionId`, falling back to `originalReferenceNo`. Re-delivering the same payment (gateways retry on any timeout or non-2xx) is acknowledged with `200` and the **original** transaction's data, without recording it again. Two retries arriving at the same instant can produce one `409`; the next retry then sees the committed row and gets a normal `200`, so a duplicate never becomes a second transaction. A payload with neither reference can't be deduped — it is recorded and a warning is logged.
- **Status mapping** — `00`→SUCCESS, `01`→PENDING, `02`→FAILED, `03`→REFUNDED, `04`→CANCELLED, `05`→EXPIRED, else/absent UNKNOWN.
- **Amount** — parsed from `amount.value`; missing/invalid → `0` (never fails the callback).
- **Records a `MANAGED` transaction** and saves the full payload JSON to its `info` column.
- **Sends an FCM push to every device of every user in the business** (best-effort — a push failure never fails the callback; skipped if Firebase isn't configured).

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

### Voice ads

Deliver a short (**≤10s, ≤2 MB**) voice ad from the server to **one device**, and track it through its lifecycle. A campaign is the brand's audio (stored in Cloud Storage); a *delivery* is one send to one device, carrying the state machine. Today a campaign has exactly one delivery (a device chosen explicitly); a future campaign engine will fan one campaign out to many devices by location — the API already reports per `deliveryId`, so that change is additive. This is the substrate for a later brand dashboard.

**State machine:** `CREATED → PREPARING → READY → STARTING → PLAYED → FINISHED`, with `ERROR` from a bad download (`SCHEDULED` is reserved, unused). The admin side drives create/prepare/play; the device drives the rest by reporting.

**Two audiences, two auth schemes:**

- **Admin surface** (`/api/ads/campaigns/**`) is server-to-server — **no JWT**, gated by a dedicated `X-ADS-ADMIN-TOKEN` header (`app.ads.admin-token`; blank ⇒ feature off, all admin calls `403`), enforced by a filter before the body is validated. This is *not* the shared `X-API-TOKEN` (which is embedded in the app).
- **Device reports** (`/api/ads/deliveries/**`) use the normal **JWT** — the service checks the caller is the delivery's target device (else `403`).

The two pushes are **data-only, priority `high`, sent after commit** (see [push payloads](#push-payloads)); the audio download link is a 24h GCS signed URL.

#### `POST /api/ads/campaigns` — Create campaign (admin)

Headers: `X-API-TOKEN` + `X-ADS-ADMIN-TOKEN`.

```json
{
  "name": "Promo Kopi",
  "brand": "Kopi Bejo",
  "audioBase64": "<base64 audio, ≤2 MB decoded, ≤10s>",
  "contentType": "audio/mpeg",
  "targetUsername": "budi",
  "targetDeviceId": "device-1"
}
```

The audio is validated **before** anything is uploaded: `400` if the `contentType` is outside the supported set (MP3/M4A/MP4/OGG/WAV), if it exceeds 2 MB or 10s (duration read from the header), or if it isn't readable audio. `targetUsername`/`targetDeviceId` must be an active user with a logged-in device **that has a registered FCM token** (else `400` — a push would go nowhere). The GCS upload happens last, after the rows are saved, so a failure never orphans an object. `201 Created` with the campaign (state `CREATED`); `data.deliveries[0].id` is the `deliveryId` the device reports against.

```json
{
  "status": 1,
  "message": "Kampanye berhasil dibuat",
  "data": {
    "id": 3, "name": "Promo Kopi", "brand": "Kopi Bejo",
    "audioContentType": "audio/mpeg", "audioSha256": "9f86d081…", "audioSizeBytes": 84044,
    "state": "CREATED", "createdAt": "2026-07-19T14:00:00",
    "deliveries": [
      { "id": 7, "campaignId": 3, "targetUsername": "budi", "targetDeviceId": "device-1",
        "state": "CREATED", "createdAt": "2026-07-19T14:00:00" }
    ]
  }
}
```

#### `POST /api/ads/campaigns/{id}/prepare` — Push download (admin)

Moves the delivery to `PREPARING` and pushes a `PREPARE` (with a fresh 24h signed `audioUrl` + `sha256`/`sizeBytes`) to the target device. `200`. `400` if no delivery is in a preparable state.

#### `POST /api/ads/campaigns/{id}/play` — Push play (admin)

Requires the delivery to be `READY` (the app confirmed a good download). Moves it to `STARTING` and pushes a `PLAY`. `200`. `400` if not ready.

#### `GET /api/ads/campaigns` · `GET /api/ads/campaigns/{id}` — Read (admin)

List (paginated, newest first) or fetch one campaign with its delivery state.

#### `GET /api/ads/history` — Merchant ad history (super admin)

Headers: `X-API-TOKEN` + `Authorization: Bearer <JWT>`. Unlike the campaign admin surface above (server-to-server, `X-ADS-ADMIN-TOKEN`), this is a **normal JWT** endpoint for the merchant: the business's **super admin** reads every ad delivered to its own devices and its sub-accounts' — **all states**, newest first. A plain member gets `403`.

Paginated: optional `page` (default 1) + `size` (default 50, max 200). Each row flattens the campaign's name/brand onto the delivery, and identifies the target device's owner by both `targetUsername` and `targetName` (snapshotted, so they read correctly even after the member is removed/renamed); `userActive` is `false` once that member has left the business.

```json
{
  "status": 1,
  "count": 1,
  "page": 1,
  "totalPages": 1,
  "total": 1,
  "data": [
    {
      "id": 42,
      "campaignId": 5,
      "campaignName": "Promo Kopi",
      "brand": "Kopi Nusantara",
      "targetUsername": "budi",
      "targetName": "Budi",
      "userActive": true,
      "state": "PLAYED",
      "errorReason": null,
      "playedAt": "2026-07-19T14:30:00",
      "finishedAt": null,
      "playLatitude": -6.2088,
      "playLongitude": 106.8456,
      "createdAt": "2026-07-19T14:00:00"
    }
  ]
}
```

#### `POST /api/ads/deliveries/{id}/downloaded` — Report download (device)

Headers: `X-API-TOKEN` + `Authorization: Bearer <JWT>`.

```json
{ "success": true, "sha256": "9f86d081…", "sizeBytes": 84044 }
```

The app reports what it downloaded. `success: true` with a `sha256` (hex, case-insensitive) + `sizeBytes` matching the campaign → state `READY`; a mismatch, or `success: false` (with an optional `"error"` note), → state `ERROR`. **Idempotent** — a duplicate report of an already-settled delivery is a no-op returning its current state. `200` with the delivery.

#### `POST /api/ads/deliveries/{id}/played` — Report played (device)

```json
{ "playedAt": "2026-07-19T14:30:00", "latitude": -6.2088, "longitude": 106.8456 }
```

`playedAt` is WIB wall-clock (no timezone); more than a day in the future is rejected (`400`, bad device clock). `latitude`/`longitude` are optional but **both-or-neither** (a lone coordinate is `400`). With both → state `FINISHED`; without them (a background play) → state `PLAYED`, awaiting the location call below. If a played-with-location report arrives after the delivery is already `PLAYED`, the location is still saved (→ `FINISHED`) rather than dropped. `200`.

#### `POST /api/ads/deliveries/{id}/location` — Report deferred location (device)

```json
{ "latitude": -6.2088, "longitude": 106.8456 }
```

The play location the `played` report couldn't include (the app only reads location in the foreground). Both required; state `PLAYED` → `FINISHED`. `200`.

---

## Error reference

Errors shared across all endpoints:

| HTTP | Message | Cause |
|---|---|---|
| 400 | `Request body tidak valid atau tidak ditemukan` | Malformed or missing JSON body |
| 400 | `Format parameter tidak valid: <param>` | Invalid query parameter type |
| 401 | `Missing or invalid X-API-TOKEN` | Wrong or absent API token |
| 401 | `Unauthorized — include Authorization: Bearer <token> header` | Missing, expired, or logged-out (blacklisted) JWT |
| 403 | `Hanya super admin yang dapat mengelola pengguna` / `Super admin tidak dapat dihapus` | Non–super-admin managing users, or acting on a super admin |
| 403 | `Hanya super admin yang dapat mengubah pengaturan bisnis` | Non–super-admin changing app config, business picture, or the QRIS |
| 404 | `Route not found` | Unknown path |
| 405 | `Method not allowed: <METHOD>` | Wrong HTTP method |
| 409 | `Username sudah digunakan` | Username already registered |
| 409 | `NMID sudah digunakan oleh bisnis lain` | Another business already uses this NMID |
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

> **On Cloud Run nothing scrapes these.** `/actuator/prometheus` and `/actuator/metrics` need the `X-API-TOKEN`, and only the local Compose Prometheus sends it, so production metrics go unused. Watch production with Cloud Monitoring instead: an **uptime check** on `/actuator/health`, a **log-based alert** on `[CALLBACK]` errors, and — the one that matters most for this product — an alert when **no payment callback has arrived for N minutes** (the `[CALLBACK] Recorded` log line / `qris.callbacks.received` counter). Distributed tracing is not set up.

**Custom metrics**

| Metric | Tags | Description |
|---|---|---|
| `login.attempts_total` | `result=success\|failure` | Login outcomes |
| `transactions.fetched_total` | — | Total transaction records returned |
| `notifications.sent_total` | `result=success\|failure` | FCM notification outcomes |

**Request logging**

Every request is logged as `METHOD PATH[?query] STATUS DURATIONms`. Requests that fail with a `4xx` also log the reason (`[400] METHOD /path — <reason>`) and, on any `4xx`/`5xx`, the request body (`[BODY] …`). Body logging is production-safe: secrets (`password`/`token`/`pushToken`) are masked, the body is single-line and capped at 2 KB, and auth/credential endpoints (`/api/auth/**`, register, `/api/users/members**`) are never captured. Under `SPRING_PROFILES_ACTIVE=prod` all logs are emitted as JSON (one object per line) for Cloud Logging.

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
| `LOGIN_MAX_FAILURES` | no | Consecutive failed logins per username before a lockout. Default `10` |
| `LOGIN_LOCKOUT_MINUTES` | no | How long a locked account must wait. Default `15` |
| `MAX_REQUEST_BYTES` | no | Max request body size; larger bodies get `413`. Default `8388608` (8 MB) — the largest real payloads are ~3 MB, so it can be lowered |
| `DB_POOL_SIZE` | no | JDBC pool size **per instance**, default `10`. Keep pool × Cloud Run max-instances (default 2), plus the database's own reserved connections, under its `max_connections` — a `db-f1-micro` allows very few |
| `JAVA_OPTS` | no | JVM flags; **replaces** the image default `-XX:MaxRAMPercentage=75` (it is not appended). Lower the percentage if the platform OOM-kills the container |
| `BUSINESS_MAX_USERS` | no | Max users per business, **including** the super admin. Default `10` |
| `BUSINESS_SIZES` / `BUSINESS_DEFAULT_SIZE` | no | Allowed business sizes + default (comma-separated). Default `Mikro,Kecil,Menengah,Besar,Non-Profit` / `Mikro` |
| `BUSINESS_INDUSTRIES` / `BUSINESS_DEFAULT_INDUSTRY` | no | Allowed industries + default (comma-separated). Default is the 11-industry list / `Makanan & Minuman` |
| `CALLBACK_DEFAULT_USERNAME` | no | Account a callback is attributed to when `CALLBACK_FALLBACK_TO_DEFAULT` is on. Defaults to `SEED_USERNAME` |
| `CALLBACK_FALLBACK_TO_DEFAULT` | no | Default `false`: a callback with a missing/unknown `merchantPan` is rejected (`404`). `true` records it against the default account instead |
| `MAIL_HOST` / `MAIL_USERNAME` / `MAIL_PASSWORD` | no | Outgoing SMTP for password-reset emails (Zoho Mail: `smtp.zoho.com` for **free** accounts, `smtppro.zoho.com` for **paid** ones — the wrong host fails login with `554 5.7.8 Access Restricted`; `.eu`/`.in`/`.com.au`/`.jp` for other data centres). **All three** must be set or the feature is off and `/api/auth/forgot-password` answers `503`. With two-factor authentication on the Zoho account, `MAIL_PASSWORD` must be an **application-specific password** |
| `MAIL_FROM` / `MAIL_FROM_NAME` | no | Sender address (must be the mailbox or one of its Zoho aliases; defaults to `MAIL_USERNAME`) and display name (default `Bejo`) |
| `MAIL_PORT` / `MAIL_STARTTLS` / `MAIL_SSL` | no | Default `587` + STARTTLS. For port 465: `MAIL_PORT=465 MAIL_SSL=true MAIL_STARTTLS=false` |
| `CALLBACK_SECRET` | no | Shared secret the payment provider must send as `X-Callback-Secret`. Blank = the callback is unauthenticated (a warning is logged at prod startup) |
| `REQUEST_LOG_BODY_ON_ERROR` / `REQUEST_LOG_MAX_BODY_LENGTH` | no | Log request bodies on 4xx/5xx (redacted, capped). Default `true` / `2048` |
| `DB_URL` | yes | JDBC URL e.g. `jdbc:postgresql://localhost:5432/bejo` |
| `DB_USERNAME` | yes | PostgreSQL username |
| `DB_PASSWORD` | yes | PostgreSQL password |
| `FIREBASE_PROJECT_ID` | no* | Firebase project that owns the app's FCM tokens. With no key given, the app sends as its **own service account** (application-default credentials — on Cloud Run no key file is needed, which matters where the org forbids creating keys). That account needs `roles/firebasecloudmessaging.admin`; `init-gcp.sh` grants it, and `deploy-cloud-run.sh` sets this to the GCP project by default |
| `FIREBASE_CREDENTIALS_BASE64` | no* | Base64-encoded Firebase service account JSON (takes precedence over `FIREBASE_PROJECT_ID`) |
| `FIREBASE_CREDENTIALS_PATH` | no* | Path to Firebase service account JSON file |
| `ADS_ADMIN_TOKEN` | no | Gates the [voice-ads](#voice-ads) admin surface (`X-ADS-ADMIN-TOKEN`). Blank ⇒ feature off |
| `ADS_GCS_BUCKET` | no† | Cloud Storage bucket for ad audio. Needed only to use voice ads |
| `ADS_SIGNED_URL_TTL_SECONDS` | no | Audio download-link lifetime. Default `86400` (24h; GCS max 7d) |
| `ADS_MAX_AUDIO_BYTES` | no | Max decoded audio size. Default `2097152` (2 MB) |
| `ADS_MAX_AUDIO_SECONDS` | no | Max audio duration. Default `10` |
| `SEED_PASSWORD` | no | Seed user password — seeding skipped if blank |
| `SEED_USERNAME` | no | Default: `admin` |
| `SEED_NAME` | no | Default: `Admin` |
| `SEED_BUSINESS_NAME` | no | Default: `My Business` |
| `SEED_BUSINESS_ADDRESS` | no | |
| `SEED_NMID` | no | National Merchant ID for seed user |
| `SEED_MERCHANT_PAN` | no | QRIS merchant PAN for the seed business (routes payment callbacks) |
| `APP_REQUESTLOGGING_LOGBODYONERROR` | no | Log request bodies on 4xx/5xx (redacted, capped). Default `true`; set `false` to disable |
| `APP_REQUESTLOGGING_MAXBODYLENGTH` | no | Max logged body length in chars. Default `2048` |

\* One of the two Firebase options is required to use push notifications.

† Voice ads need a Cloud Storage bucket + Application Default Credentials, so they work only on GCP (not local Compose). Leave `ADS_ADMIN_TOKEN` blank to keep the feature off.

### GCP deployment

Config is split across **three layers**, by who consumes it and how sensitive it is:

| Layer | Holds | Why it lives here |
|---|---|---|
| **GitHub Actions secret** | no GCP secret at all — Cloud Run auth is keyless ([Workload Identity Federation](#github-actions-authentication-to-gcp)); only `VM_SSH_KEY` (VM). `GCP_SA_KEY` remains as a fallback for projects that allow keys | all the CI pipeline needs to authenticate to the target |
| **GitHub Actions variables** | non-sensitive config: project/region/service names, seed + business options | plaintext is fine — none of it is secret |
| **GCP Secret Manager** | runtime secrets: DB password, JWT secret, API token, Firebase creds, seed password | consumed by the **running app** on Cloud Run, not by CI |

**Why runtime secrets live in Secret Manager, not GitHub:** `deploy.yml` passes them to Cloud Run as **references** (`--set-secrets="API_TOKEN=bejo-api-token:latest"`), so the value never touches the CI runner, its logs, or the Cloud Run revision's plaintext env. Only the **runtime** service account (`bejo-cloudrun`, granted `secretmanager.secretAccessor` by `init-gcp.sh`) can read them — the CI service account can't, so a GitHub compromise can't leak your DB password. You also get Secret Manager's versioning, rotation, and per-access audit for free. `init-gcp.sh` creates these from `.env.gcp` and `deploy.yml` mounts them:

| Secret Manager secret | From `.env.gcp` | Mounted as container env |
|---|---|---|
| `bejo-db-password` | `DB_PASSWORD` | `DB_PASSWORD` |
| `bejo-jwt-secret` | `JWT_SECRET` | `JWT_SECRET` |
| `bejo-api-token` | `API_TOKEN` | `API_TOKEN` |
| `bejo-firebase-credentials` | `FIREBASE_CREDENTIALS_BASE64` *(optional, key-based FCM only)* | `FIREBASE_CREDENTIALS_BASE64` (only when `MOUNT_FIREBASE_CREDENTIALS=true`) |
| `bejo-seed-password` | `SEED_PASSWORD` | `SEED_PASSWORD` |
| `bejo-ads-admin-token` | `ADS_ADMIN_TOKEN` *(optional)* | `ADS_ADMIN_TOKEN` |
| `bejo-mail-password` | `MAIL_PASSWORD` *(optional)* | `MAIL_PASSWORD` (only when `MOUNT_MAIL_PASSWORD=true`) |
| `bejo-callback-secret` | `CALLBACK_SECRET` *(optional)* | `CALLBACK_SECRET` (only when `MOUNT_CALLBACK_SECRET=true`) |

`init-gcp.sh` also creates the **ad-audio bucket** `gs://<project>-ads` (uniform access + public-access-prevention; audio is reachable only via signed URLs) and grants the Cloud Run service account `objectAdmin` on it plus `serviceAccountTokenCreator` on itself (to V4-sign URLs without a key).

> ⚠️ Don't store these as GitHub **variables** — a GitHub variable is plaintext and readable by anyone with repo access. (The API token, in particular, belongs only in `bejo-api-token`.)

**GitHub Actions variables** (Settings → Variables → Actions)

| Variable | Description |
|---|---|
| `GCP_PROJECT_ID` | GCP project ID |
| `GCP_REGION` | Region, `us-central1` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Workload Identity provider resource name, `projects/<number>/locations/global/workloadIdentityPools/github/providers/github-actions` — printed by `init-gcp.sh`. When set, the deploy authenticates keylessly; when empty it falls back to `GCP_SA_KEY` |
| `GCP_DEPLOY_SERVICE_ACCOUNT` | The CI service account to impersonate, `bejo-github-actions@<project>.iam.gserviceaccount.com` — printed by `init-gcp.sh` |
| `GCP_SERVICE_NAME` | Cloud Run service name |
| `ARTIFACT_REGISTRY_REPO` | Artifact Registry repository name |
| `CLOUD_SQL_INSTANCE_CONNECTION_NAME` | e.g. `project:region:instance` |
| `CLOUD_SQL_DB_NAME` | Database name |
| `CLOUD_SQL_DB_USER` | Database user |
| `CLOUD_RUN_SA` | Cloud Run service account name |
| `CUSTOM_DOMAIN` | `api.bejo.temanproduk.com` |
| `SEED_USERNAME` / `SEED_NAME` / `SEED_BUSINESS_NAME` / `SEED_BUSINESS_ADDRESS` / `SEED_NMID` / `SEED_MERCHANT_PAN` | Seed user values |
| `BUSINESS_MAX_USERS` / `BUSINESS_SIZES` / `BUSINESS_DEFAULT_SIZE` / `BUSINESS_INDUSTRIES` / `BUSINESS_DEFAULT_INDUSTRY` | *(optional)* Business option overrides — built-in defaults apply if unset |
| `JWT_EXPIRATION_MS` / `CALLBACK_DEFAULT_USERNAME` / `CALLBACK_FALLBACK_TO_DEFAULT` / `REQUEST_LOG_BODY_ON_ERROR` / `REQUEST_LOG_MAX_BODY_LENGTH` | *(optional)* Other overrides — defaults apply if unset |
| `LOGIN_MAX_FAILURES` / `LOGIN_LOCKOUT_MINUTES` / `MAX_REQUEST_BYTES` / `PUSH_QUEUE_THREADS` / `PUSH_QUEUE_CAPACITY` | *(optional)* Login throttle, request-size cap and FCM queue tuning — defaults `10` / `15` / `8388608` / `4` / `1000` |
| `DB_POOL_SIZE` / `JAVA_OPTS` | *(optional)* JDBC pool size per instance (default `10`) and JVM flags (replaces the default `-XX:MaxRAMPercentage=75`) |
| `CLOUD_RUN_TIMEOUT` | *(optional)* Cloud Run request timeout in seconds. Default `15` (max `3600`) |
| `CLOUD_RUN_MIN_INSTANCES` / `CLOUD_RUN_MAX_INSTANCES` / `CLOUD_RUN_MEMORY` / `CLOUD_RUN_CPU` | *(optional)* Cloud Run sizing — defaults `0` / `2` / `512Mi` / `1` |
| `ADS_GCS_BUCKET` | *(optional)* Ad-audio bucket. Defaults to `<project>-ads` |
| `ADS_SIGNED_URL_TTL_SECONDS` / `ADS_MAX_AUDIO_BYTES` / `ADS_MAX_AUDIO_SECONDS` | *(optional)* Voice-ad limits — defaults `86400` / `2097152` / `10` |
| `MOUNT_ADS_ADMIN_TOKEN` | *(optional)* Set to `false` if `ADS_ADMIN_TOKEN` was left blank in `.env.gcp` (so `bejo-ads-admin-token` doesn't exist) — otherwise CI deploys fail mounting the missing secret. Default `true` |
| `MAIL_HOST` / `MAIL_USERNAME` / `MAIL_FROM` / `MAIL_FROM_NAME` / `MAIL_PORT` / `MAIL_STARTTLS` / `MAIL_SSL` | *(optional)* Outgoing email settings (non-secret) — see the env table; unset keeps password reset off |
| `MOUNT_MAIL_PASSWORD` | *(optional)* Set to `true` once `bejo-mail-password` exists in Secret Manager **and** keep it set: without it a release unmounts the SMTP password and silently switches password reset off. Default `false` |
| `MOUNT_CALLBACK_SECRET` | *(optional)* Set to `true` once `bejo-callback-secret` exists in Secret Manager **and the provider is sending `X-Callback-Secret`**. Default `false` |
| `CLOUD_RUN_CPU_THROTTLING` | *(optional)* `false` keeps Cloud Run CPU allocated between requests (costlier). Use it if payment alerts lag the callback — queued pushes are starved of CPU after the response. Default `true` |

> These optional overrides must be set as GitHub Actions **variables** for CI deploys (`deploy.yml`) **and** in `.env.gcp` for the one-time `init-gcp.sh` run — `--set-env-vars` replaces the whole set on each deploy, so an override only in `.env.gcp` would be reset by the next CI deploy.

#### GitHub Actions authentication to GCP

CI uses **Workload Identity Federation**: GitHub issues the job a short-lived OIDC token, Google exchanges it for credentials that impersonate `bejo-github-actions`, and no key file or secret exists to leak. `scripts/init-gcp.sh` creates the pool and provider, locks them to **this repository and the `release` branch** (a workflow pushed to any other branch cannot mint credentials; `GITHUB_DEPLOY_REF=any` lifts the branch restriction), and prints two `gh variable set` commands for `GCP_WORKLOAD_IDENTITY_PROVIDER` and `GCP_DEPLOY_SERVICE_ACCOUNT`. This is also the only option on organizations that enforce `constraints/iam.disableServiceAccountKeyCreation` (downloadable service-account keys are forbidden there — the script's key creation fails with `Key creation is not allowed on this service account`).

**Legacy key (only where the org allows keys)** — `bash scripts/init-gcp.sh --sa-key` (or `--rotate-key`) writes a key file and prints the command to store it as the secret below; the key itself is never printed. A re-run skips key creation when one exists.

**GitHub Actions secret** (Settings → Secrets → Actions) — only for the legacy key path

| Secret | Description |
|---|---|
| `GCP_SA_KEY` | Base64-encoded service account JSON key, used only when `GCP_WORKLOAD_IDENTITY_PROVIDER` is empty |
