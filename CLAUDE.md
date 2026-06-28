# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A Java 25 / Spring Boot REST API that provides session-based login (against its own database), transaction history, FCM token management, and push notification delivery. Originally inspired by the Node.js project at `/Users/anton/Documents/Projects/cuan` but uses local DB authentication instead of scraping an external gateway.

## Commands

```bash
# Build
mvn package -DskipTests

# Run locally (requires PostgreSQL at localhost:5432)
mvn spring-boot:run

# Run a single test
mvn test -Dtest=ClassName#methodName

# Full stack via Docker Compose
docker compose up --build

# Rebuild only the app
docker compose up --build app
```

## Required environment variables

| Variable | Description |
|---|---|
| `JWT_SECRET` | Base64-encoded secret, min 256 bits. Generate: `openssl rand -base64 32` |
| `JWT_EXPIRATION_MS` | Token TTL in milliseconds (default `86400000` = 24 h) |
| `DB_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/bejo` |
| `DB_USERNAME` / `DB_PASSWORD` | PostgreSQL credentials |
| `FIREBASE_CREDENTIALS_BASE64` | Base64-encoded Firebase service account JSON |
| `FIREBASE_CREDENTIALS_PATH` | Alternative: path to service account JSON file |

Set via a `.env` file alongside `docker-compose.yml` — Docker Compose picks it up automatically.

## Authentication

Every API request (including login) must include:

```
X-API-TOKEN: <value from API_TOKEN in .env>
```

`ApiTokenFilter` validates this first. If missing or wrong, returns `401` immediately.

Protected endpoints additionally require:

```
Authorization: Bearer <JWT>
```

The JWT is returned by the login endpoint in `data.token`. Protected controllers resolve the username via Spring's `Authentication` principal.

`/actuator/**` is exempt from both checks.

## API endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/auth/login` | Public | Validate credentials, returns JWT |
| `GET` | `/api/transactions` | JWT | Fetch transaction history for the authenticated user |
| `PUT` | `/api/devices/{deviceId}/fcm-token` | JWT | Update FCM push token |
| `POST` | `/api/notifications` | JWT | Send FCM push notification |
| `PATCH` | `/api/users/me` | JWT | Update name, email, and/or password in one call (all fields optional; password change requires oldPassword) |
| `PATCH` | `/api/users/me/phone` | JWT | Update phone number |

All responses follow `{ "status": 1\|0, "message": "...", "data": {...}, "count": N }` — null fields are omitted.

Login success response: `{ "status": 1, "data": { "username": "...", "token": "...", "tokenType": "Bearer", "expiresIn": 86400 } }`

A Postman collection is at `bejo.postman_collection.json`. The Login request has a test script that auto-saves the token to the `{{token}}` collection variable used by all other requests.

## Architecture

```
controller/   → thin HTTP layer; validation via @Valid / @Validated
service/      → business logic, metrics via Micrometer Counter
repository/   → Spring Data JPA interfaces (SessionRepository, TransactionRepository)
entity/       → JPA entities mapping to cuan_sessions and cuan_history tables
dto/          → request records (@NotBlank validated) + response records
config/       → ApiKeyAuthFilter, FirebaseConfig, RequestLoggingFilter, SecurityConfig
exception/    → GlobalExceptionHandler + ResourceNotFoundException
```

### Login flow

`AuthService.login()` authenticates against the local `users` table:
1. Look up `users` row by `username` — 401 if not found.
2. Verify password against `password_hash` (BCrypt via `PasswordEncoder`).
3. Upsert a row in `cuan_sessions` keyed on `(username, device_id)` with a new UUID session ID.
4. Return `{ username, sessionId }`.

**Creating users:** Insert directly into the `users` table with a BCrypt-hashed password.  
Generate a hash from the Spring shell or a one-liner:
```java
System.out.println(new BCryptPasswordEncoder().encode("your-password"));
```
Then:
```sql
INSERT INTO users (username, password_hash) VALUES ('alice', '$2a$10$...');
```

### Observability

- `/actuator/health` — liveness/readiness
- `/actuator/prometheus` — Micrometer metrics scraped by Prometheus (container: `bejo-prometheus:9090`)
- Custom counters: `login.attempts{result}`, `transactions.fetched`, `notifications.sent{result}`
- `RequestLoggingFilter` logs `METHOD PATH STATUS DURATIONms` for every request
- JSON log format active under `SPRING_PROFILES_ACTIVE=prod`

## Database

Schema is created by `docker/init.sql` on first PostgreSQL startup. Spring Boot is configured with `ddl-auto: validate` — Hibernate validates entity mappings but never alters the schema.

Key tables:
- `cuan_sessions` — one row per `(username, device_id)`; holds cookies, MID, FCM token, schedule window
- `cuan_history` — deduped by `transaction_id`; inserted by the scraper (not this API)
