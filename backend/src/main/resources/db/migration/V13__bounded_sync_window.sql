ALTER TABLE repositories ADD COLUMN sync_configured_at TIMESTAMP NULL;
ALTER TABLE repositories ADD COLUMN sync_window_initialized BOOLEAN NOT NULL DEFAULT FALSE;

-- Existing repositories do not have a reliable configuration timestamp. Leave it NULL so
-- the first sync initializes a fresh one-month window from the application server clock.

