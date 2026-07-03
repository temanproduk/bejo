-- General-purpose column to store extra transaction context as JSON text
-- (e.g. the raw QRIS merchant callback payload).
ALTER TABLE cuan_history ADD COLUMN additional_info TEXT;
