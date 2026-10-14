-- V5: transactions that are recorded before they are paid (a QRIS sale waiting for the customer).
-- Additive and safe on a populated table: both columns are nullable, nothing is rewritten.
--
--  * commerce.transactions.paid_at: when an awaiting-payment transaction was paid — set when a payment
--    detected by the app settles it, or when someone marks it paid by hand. NULL for every transaction
--    that was never awaiting (cash, transfer, callbacks, device-detected payments), and while it waits.
--  * commerce.transactions.payment_reference: the Idempotency-Key of the PARSED payment that settled it.
--    A PARSED payment that matches an awaiting transaction is merged into it rather than recorded as a
--    second row (that would count the money twice), so the payment's own key has no row to live on; this
--    column is how a retried request with the same key is recognised and answered with the same
--    transaction instead of settling a different one.

ALTER TABLE commerce.transactions ADD COLUMN IF NOT EXISTS paid_at TIMESTAMP;
ALTER TABLE commerce.transactions ADD COLUMN IF NOT EXISTS payment_reference VARCHAR(255);

-- Looks a settled payment up by its key; most rows have none, so index only the ones that do. Unique, so two
-- copies of one request racing each other cannot settle two different transactions: the second one fails.
CREATE UNIQUE INDEX IF NOT EXISTS idx_transactions_payment_reference
    ON commerce.transactions (business_id, payment_reference)
    WHERE payment_reference IS NOT NULL;

-- Finds the oldest awaiting transaction of an amount; the awaiting rows are a small slice of the table.
CREATE INDEX IF NOT EXISTS idx_transactions_awaiting
    ON commerce.transactions (business_id, amount, transaction_date)
    WHERE status = 'AWAITING_PAYMENT' AND deleted_at IS NULL;
