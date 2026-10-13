# Account settings

> Moved out of `CLAUDE.md` unchanged to keep that file small — it is loaded into every session.
> `CLAUDE.md` keeps the rules and gotchas; this is the detail behind them.

Per-business settings synced from the mobile client, and the team-management gate.

## Account settings

`AccountSettingsController` → `AccountSettingsService` manage a per-**business** `account_settings` row (one per `business_id`, created lazily on first update, shared by all the business's users) holding the settings synced from the mobile client:
- **Monitored apps** — a `packageName → issuer` map (mirrors the Android `notifAppList` pref), stored as JSON via `StringMapJsonConverter`.
- **Notification keywords** — `includeKeywords` (whitelist) + `excludeKeywords` (blacklist) string lists (JSON via `StringListJsonConverter`) plus `blacklistEnabled` (default true) / `whitelistEnabled` (default false) toggles.

- **Config toggles** — `qrisWithProfilePicture` (compose the QRIS image onto the profile picture), `inventoryEnabled` (enable inventory management), `receiveAds` (opt in to voice ads), and `teamManagementEnabled` (activate employee accounts + presence), all boolean columns defaulting false, all set through **one generic endpoint** `PUT /api/account-settings/toggle` (`{setting, enabled}`; unknown setting → 400) rather than a `PUT` per flag. `receiveAds` + `teamManagementEnabled` are bundled into the login + `/me` `accountSettings` so the client knows the account's ad preference and team status (super-admin-set). **`teamManagementEnabled` is effective**: the response/gate read it as the stored flag **OR** the account already has >1 active user, so an existing team is never locked out (`AccountSettingsService.isTeamManagementEnabled`).

**Team-management gate:** when it's off, all the team-feature writes are rejected with **403 `"Fitur manajemen tim belum diaktifkan"`** via `AccountSettingsService.requireTeamManagementEnabled(businessId)` — member **add/update/delete** (`MemberService.requireSuperAdmin`, the single choke point; `list` is not gated) and presence **clock-in/out, ping, respond** (`PresenceService`). So a fresh solo account must first enable `teamManagement` (via the toggle) before it can add its first employee. The presence-write gate is defensive — since the flag is effectively on whenever members exist, it's practically unreachable, but it keeps the rule uniform.

**Authorization:** reading (`GET /api/account-settings`) is open to any member, but **all mutations are super-admin-only** — the three `PUT`s go through `AccountSettingsService.loadOrCreate`, which calls `BusinessContext.requireSuperAdminBusinessId` (403 `ForbiddenException` for a plain member). The same gate protects the QRIS (`POST /api/qris/upload`), the business picture (`PUT`/`DELETE /api/business/picture`), and the shared **business info + bank edits** (`PATCH` on `/api/users/me/business` + `/api/users/me/bank` — all super-admin-only; a plain member gets `403`). Per-user profile edits (name/email/phone, password) stay open to the member themselves.

`GET /api/account-settings` returns defaults (empty lists/map, blacklist on, whitelist off, toggles off) when no row exists yet — it never 404s. `PUT /api/account-settings/monitored-apps` replaces the whole map; `PUT /api/account-settings/keywords` is a partial update (null field = unchanged, empty list = cleared). The service validates and normalizes input — package names match `[A-Za-z0-9_.]+`, issuers/keywords are trimmed + lowercased, keyword lists deduped, with size caps — throwing `IllegalArgumentException` (400) on bad input.
