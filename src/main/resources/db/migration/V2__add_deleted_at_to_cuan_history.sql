-- Soft delete for transactions: NULL = active, non-NULL = deleted at that time
ALTER TABLE cuan_history ADD COLUMN deleted_at TIMESTAMP;
