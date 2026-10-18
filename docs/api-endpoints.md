# API endpoints

> Moved out of `CLAUDE.md` unchanged to keep that file small — it is loaded into every session.
> `CLAUDE.md` keeps the rules and gotchas; this is the detail behind them.

Every endpoint with its auth requirement. Response envelope and pagination conventions are in `CLAUDE.md`.

## API endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/snap/v1.0/qr/qr-mpm-notify` | None (optional `X-Callback-Secret`) | Inbound QRIS payment callback (SNAP QR MPM notify); routes by `merchantPan` (unknown → 404), records a MANAGED transaction, stores raw payload in `info` |
| `POST` | `/api/users/register` | Public | Self-service registration; creates a **new business** + its first user (the `SUPER_ADMIN`, **email unverified**) + bank (no QRIS row — QRIS is optional). Emails a 6-digit code; no phone number is collected; the email must be unique; 503 if email is not configured |
| `GET` | `/api/users/me` | JWT | The caller's own account bundle (`MeResponseDto`: id + profile + role + shared business/bank/picture/account-settings) — same shape as login minus the token, for refreshing state |
| `GET` | `/api/users/members` | JWT | List the users of the caller's business (any member) |
| `POST` | `/api/users/members` | JWT (super admin) | Add a user to the caller's business as a `MEMBER` |
| `PATCH` | `/api/users/members/{id}` | JWT (super admin) | Update a member (name/email/phone/password; a super admin can't be modified here) |
| `DELETE` | `/api/users/members/{id}` | JWT (super admin) | Soft-delete a member (releases its username); a super admin can't be deleted |
| `POST` | `/api/auth/login` | Public | Validate credentials, upsert session, returns JWT. `403` + `data.reason=EMAIL_NOT_VERIFIED` while the email is unverified (checked after the password) |
| `POST` | `/api/auth/verify-email` | Public (`X-API-TOKEN` only) | `{username, code}` → marks the email verified; every failure is one `400` message |
| `POST` | `/api/auth/resend-verification` | Public (`X-API-TOKEN` only) | Email a fresh verification code to `{username}`; always 200 (no account enumeration), 503 if email is not configured |
| `POST` | `/api/auth/forgot-password` | Public (`X-API-TOKEN` only) | Email a 6-digit reset code to the address on file for `{username}`; always 200 (no account enumeration), 503 if email is not configured |
| `POST` | `/api/auth/reset-password` | Public (`X-API-TOKEN` only) | `{username, code, newPassword}` → sets the password, ends every session, lifts any login lockout; every failure is one `400` message |
| `POST` | `/api/auth/logout` | JWT | Blacklist the current token (jti); optional body `{deviceId}` clears that device's FCM token |
| `GET` | `/api/transactions` | JWT | Transaction history; optional `dateStart`/`dateEnd` (ISO date, inclusive); paginated (`page`/`size`). Each row carries `createdBy`/`updatedBy` (who created/last-edited it), `status` (`SUCCESS`, `AWAITING_PAYMENT`, …) and, once a waiting sale is paid, `paidAt` |
| `GET` | `/api/transactions/summary` | JWT | Dashboard totals (summed amount + count) for today / yesterday / this month; optional `date` = client's local "today" (defaults to server date). Sales still **awaiting payment**, and **cancelled** ones, are not counted |
| `GET` | `/api/transactions/{transactionId}` | JWT | One transaction **with its sold-product line items** (the history list omits items) |
| `POST` | `/api/transactions` | JWT | Add MANUAL/PARSED transaction; requires `Idempotency-Key` header. A MANUAL entry with `issuerName` **`QRIS`** starts `AWAITING_PAYMENT`; a PARSED payment for the exact amount of a waiting sale **settles it instead of adding a row** (the response carries that sale's id). Optional `items` (product line items) — validates + reduces stock, records a SALE stock mutation. A **PARSED** entry also pushes to everyone sharing the QRIS it landed on **except the reporting device**, identified by the optional `X-Device-Id` header (see below) |
| `POST` | `/api/transactions/bulk` | JWT | Bulk-sync MANUAL/PARSED transactions (offline entry); per-item `idempotencyKey`, partial-success result (no line items, no PARSED push) |
| `PATCH` | `/api/transactions/{transactionId}` | JWT | Edit a MANUAL/PARSED transaction (partial update; MANAGED is immutable). Optional `items` fully replaces the line items, reconciling stock by delta + re-deriving amount. Stamps `updatedBy` |
| `POST` | `/api/voice/amount` | JWT | "Say the amount": body `{ audio, sampleRateHertz? }` — `audio` is base64 of raw 16-bit mono PCM (0.3–10 s, default 16 kHz). Answers `{ transcript, amount, confidence }` (`amount` absent when no number was heard). 400 for an unusable clip, 429 over 12 a minute, 503 when speech recognition is unavailable |
| `POST` | `/api/voice/transaction` | JWT | "Say the sale": body `{ audio, sampleRateHertz?, products? }` — `audio` is base64 of raw 16-bit mono PCM (0.5–30 s, default 16 kHz), `products` the merchant's catalogue as the app has it (`[{ id, name, price }]`, at most 200). **Gemini on Vertex AI** works out what was said; nothing is saved. Answers `{ understood, transcript, amount, paymentMethod, customerInfo, merchantInfo, items: [{ productId, name, quantity, unitPrice, isNew }] }` with every field present (nulls written out); the model's output is validated: `paymentMethod` is one of `Tunai`/`Transfer`/`Kartu Kredit`/`Kartu Debit`/`QRIS`/`Lainnya` or null, a matched item (`productId` in the request) takes its name and price from the catalogue, any other item is new (`isNew`, `productId` null), at most 20 items. 400 for an unusable clip or request, 429 over 6 a minute / 100 a day per user, 503 "Input suara belum tersedia. Isi manual dulu ya." when Gemini is unavailable |
| `GET` | `/api/voice/models/converter` | JWT | The voice-converter model files for on-device voice cloning ("Suara Kamu Sendiri"): `{ revision, files: [{ name, bytes, sha256, url }] }` where `url` is a short-lived signed download link (1 h, `VOICE_MODELS_SIGNED_URL_TTL_SECONDS`) — a download interrupted past that needs a fresh call; the app is meant to check `bytes` and `sha256` after downloading. Any signed-in user, at most 10 calls per rolling hour (`VOICE_MODELS_MAX_REQUESTS_PER_HOUR`) → 429 "Terlalu sering meminta model suara. Coba lagi nanti."; only a call that returned links counts. 503 "Model suara belum tersedia" when the bucket is not configured, the models have not been uploaded, the manifest is invalid (unsafe file name, bad checksum or size, a name listed twice, more than 8 files) or storage/signing fails |
| `POST` | `/api/transactions/{transactionId}/mark-paid` | JWT | Mark a sale that is `AWAITING_PAYMENT` as paid (`SUCCESS`, `paidAt` = now); any user of the business. 400 if it was never awaiting payment or was already cancelled at day end; repeating it on a paid one is a no-op |
| `DELETE` | `/api/transactions/{transactionId}` | JWT (super admin) | Soft-delete a transaction (sets `deleted_at`); returns any sold line items' stock to their products. **Sub-accounts get 403** |
| `PUT` | `/api/devices/{deviceId}/fcm-token` | JWT | Update FCM push token (scoped to the authenticated user's session) |
| `POST` | `/api/notifications/test` | JWT | **Test:** send an FCM push from the request payload to one device (no DB write) |
| `POST` | `/api/notifications/user` | JWT | **Test/utility:** broadcast an FCM push to **all** the caller's devices (no `deviceId`; via `sendToUser`; no DB write) |
| `GET` | `/api/qris` | JWT | The caller's **effective** QRIS: their own per-user QRIS if assigned, else the shared business QRIS. Returns `qrisCode` + parsed `merchantName`/`nmid`/`acquirer`/`terminalId` + business name/address (no `type` discriminator) |
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
| `POST` | `/api/admin/auth/otp/request` | Public (`X-API-TOKEN` only) | **Admin dashboard.** Email an 8-digit sign-in code to `{email}` if it is on the `ADMIN_EMAILS` allow-list; always 200 (does not reveal who is an admin), 503 if email is not configured |
| `POST` | `/api/admin/auth/otp/verify` | Public (`X-API-TOKEN` only) | `{email, code}` → `{token, tokenType, expiresIn, email}`: an admin session token (12h). Every failure gives the same 400 |
| `POST` | `/api/admin/auth/logout` | Admin session | Revoke the admin session |
| `GET` | `/api/admin/me` | Admin session | The signed-in admin's email |
| `GET` | `/api/admin/users` | Admin session | **Every** app user across all businesses, newest first, with their business; optional `q` (name/username/email/phone/business), `role` (`SUPER_ADMIN`\|`MEMBER`), `status` (`active`\|`deleted`); paginated |
| `GET` | `/api/admin/transactions` | Admin session | **Every** transaction across all businesses (incl. soft-deleted, flagged by `deletedAt`), newest first; optional `q` (transaction id/RRN/creator/business), `dateStart`/`dateEnd` (inclusive), `type`, `status`; paginated |

"Admin session" = `Authorization: Bearer <token from otp/verify>`. It is not a user JWT: neither is accepted in place of the other. `/api/admin/**` is the only surface with CORS enabled (for the dashboard's origin).
