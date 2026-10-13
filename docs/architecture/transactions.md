# Transactions

> Moved out of `CLAUDE.md` unchanged to keep that file small — it is loaded into every session.
> `CLAUDE.md` keeps the rules and gotchas; this is the detail behind them.

Bulk sync, sold-product line items, soft delete, author audit and the PARSED-transaction push.

## Bulk transaction sync (offline)

`POST /api/transactions/bulk` (`TransactionService.bulkCreate`) lets the client push a batch of manually-entered transactions accumulated while offline. Key design points:
- **Per-item idempotency** — each item carries its own client-generated `idempotencyKey` (not the request-level `Idempotency-Key` header), so re-sending the whole batch (or a partial batch after a dropped connection) never duplicates.
- **Partial success** — the method is deliberately **not** `@Transactional`; each item is validated individually (via an injected `jakarta.validation.Validator`, since the request DTO does not cascade-validate its list elements) and saved in its own transaction. One malformed/duplicate row can't roll back or block the rest.
- **Per-item result** — returns `BulkTransactionResultResponseDto` with counts + a `results` list of `{idempotencyKey, status (CREATED|DUPLICATE|FAILED), transactionId, error}`. The client matches by `idempotencyKey` to mark local rows synced (CREATED/DUPLICATE) or retry/fix (FAILED). A concurrent unique-constraint hit is caught and reported as DUPLICATE.

## Transaction line items (products sold)

A MANUAL/PARSED transaction may optionally carry `items` — a list of `(productId, quantity)` tuples for the products sold. `TransactionService.create` handles them atomically (the method is `@Transactional`, so any failure rolls back the whole transaction):
- **Stock arithmetic is done by the database, never read-modify-write.** Every stock change goes through a conditional update — `ProductRepository.decreaseStock` (`UPDATE … SET stock = stock - :qty WHERE id = :id AND stock >= :qty`, 0 rows = refuse) or `increaseStock`. The services used to read the stock, subtract in Java and save, which let two concurrent sales each read the same figure and each write their own answer: one sale vanished and stock could go negative. `StockConcurrencyTest` holds this guarantee (40 concurrent sales against 20 units → exactly 20 succeed, stock lands on 0; against the old code all 40 "succeeded"). Because the loaded entities are stale after such an update, the ledger's before/after values are read back with `findStockByIdIn` (a JPQL projection, so it bypasses the persistence context) rather than taken from memory.
- **Two-pass, validate-then-apply** — pass 1 resolves every line to one of the merchant's own products and checks `stock >= quantity`; an unknown product, a duplicate product line, or **insufficient stock** throws `IllegalArgumentException` (400) and nothing changes. (Pass 1 produces the *friendly* message; the conditional update above is what makes it safe when a concurrent sale takes the stock in between.) Pass 2 reduces each product's `stock`, saves a `transaction_items` row (snapshotting `sku`/`name`/`unitPrice` = the product's current selling price, plus `subtotal`), and records a **SALE** `StockMutation` (via `StockMutationService.recordStockChange`) so the stock ledger stays complete.
- `transaction_items.transaction_id` → `transactions.id`; `product_id` is a plain reference (no FK) so history survives a later product edit/delete. When items are present the transaction **`amount` is derived** from the sum of line subtotals (the client-sent amount is overridden), so the total can never disagree with its items.
- **Reading them back:** the history list (`GET /api/transactions`) omits items to keep the payload small; `GET /api/transactions/{transactionId}` returns the transaction **with** its `items`. `TransactionResponseDto.items` is `@JsonInclude(NON_NULL)`, so it's absent on the list and present on the detail. `PATCH` returns the transaction with its (post-edit) items.
- **Editing items** (`PATCH /api/transactions/{transactionId}` with `items`) is a **full replacement**, handled by `TransactionService.reconcileItems`: it diffs the new items against the existing ones and adjusts stock by the **per-product delta** — an increased/added line draws more stock down (validated, same 400s as create), a reduced/removed line **returns** stock. Atomic two-pass; one **SALE** mutation records the net changes (note `"Sale edit …"`). A non-empty list **re-derives `amount`**; an empty list `[]` clears all items and returns their stock (amount keeps the scalar-updated value); `items: null` leaves the items untouched. A removed line whose product was since deleted is dropped without returning stock.
- Bulk sync (`POST /api/transactions/bulk`) does **not** process items (offline stock validation is out of scope) — items on a bulk item are ignored.

## Transaction soft delete

`transactions.deleted_at = NULL` → active; non-null → deleted. `DELETE /api/transactions/{transactionId}` sets the timestamp; the row is never removed. History/edit/delete queries filter on `deleted_at IS NULL`, but `existsByTransactionId` and the idempotency-key replay lookup deliberately include deleted rows — the DB unique constraints span all rows, so a deleted `transaction_id`/`idempotency_key` still can't be reused.

**Stock return on delete:** if the deleted transaction has line items, `TransactionService.restoreStockFromItems` credits each item's quantity back to its product (best-effort — a product deleted since the sale is skipped) and records one **SALE** reversal `StockMutation` (note `"Sale delete …"`). The `transaction_items` rows are kept (the soft-deleted transaction is the historical record); stock is returned exactly once — a deleted transaction can't be deleted again (the `deleted_at IS NULL` lookup 404s), so there's no double-return.

**Delete is super-admin-only:** `TransactionService.delete` resolves the business via `BusinessContext.requireSuperAdminBusinessId` (not `requireBusinessId`), so a sub-account (MEMBER) trying to delete gets **403** — only the super admin can remove transactions. (Create/edit stay open to any member.)

## Transaction author audit (`created_by` / `updated_by`)

MANUAL/PARSED transactions snapshot **who touched them**: `transactions.created_by` = the username of the account/sub-account that entered it (set in `buildTransaction`, so both single-`create` and `bulkCreate` populate it), and `updated_by` = the username that last edited it (stamped in `update`, null until the first edit). Both are `VARCHAR(50)`, both **null on a MANAGED callback row** (no human author). They ride on `TransactionResponseDto` (`@JsonInclude(NON_NULL)`) so the history list (`GET /api/transactions`) and detail surface `createdBy`/`updatedBy`, with `updatedBy` simply absent until an edit. Username is a snapshot (like `presences`/`ad_deliveries`), so it survives a later rename/soft-delete of the author.

## PARSED transaction push (routed by who detected it)

When a **PARSED** transaction is created (a real-time payment an app detected off a QRIS merchant notification and self-reported), `TransactionService.notifyParsedTransaction` pushes a `TransactionPushPayload` (data-only, Android HIGH).

**The reporting DEVICE is excluded — not the reporting account.** That device announced the payment locally before calling the API, so pushing it back is pure echo; but the same merchant is often signed in on a second phone or a counter tablet, and those have heard nothing. Excluding by username silenced every device they own, which is why the exclusion is per-device.

The device is identified by the **`X-Device-Id`** header on `POST /api/transactions` (the app's `ANDROID_ID`, the same value it sends as `deviceId` at login and which lands in `sessions.device_id`). The header is **optional**: an app build that predates it sends nothing, `excludeDeviceId` is null, and nobody is filtered — i.e. the reporter gets its own echo back, exactly as before. So the server can ship ahead of the app.

Recipients (usernames):

- the business **super admin** — always (merchant oversight of every sale);
- every `BusinessRole.MEMBER` **without** their own `user_qris` (the shared-QRIS cohort), regardless of who reported it. A member **with** their own `user_qris` runs a separate merchant app and sees its own payments there, so they are never in this cohort;
- **the reporter**, whoever they are (`recipients.add(creator)`). "It already played the sound" is true of one *device*, not an account — the merchant's other phones and tablets have announced nothing. Without this a member with their own QRIS was excluded from their own sale, being in neither group above.

Those usernames are then resolved to devices and the reporting one is dropped: `PushNotificationService.sendToUsernames(usernames, payload, excludeDeviceId)` filters `PushRecipient.deviceId()`. The two-arg overload (no exclusion) is what `QrisCallbackService` still uses — a **MANAGED** callback is learned by the server first, so everyone must be told, super admin included.

Recipients are resolved synchronously (fast DB reads) but the send is **deferred to after commit** (`TransactionSynchronization.afterCommit`, the presence/ads pattern) so a rollback never buzzes a phone, and it's **best-effort** — a send failure is logged, never rethrown, and never fails the transaction. **Only the single `POST /api/transactions` path** does this; `bulkCreate` (offline catch-up) deliberately does not — a burst of pushes for stale entries would be wrong.
