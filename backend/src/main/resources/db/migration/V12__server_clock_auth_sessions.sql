-- Authentication expiry is evaluated exclusively by the application server clock.
-- Existing database-clock sessions are intentionally invalidated; users must log in once after this upgrade.
ALTER TABLE auth_sessions ADD COLUMN expires_at_epoch_ms BIGINT NOT NULL DEFAULT 0;
DELETE FROM auth_sessions;
CREATE INDEX idx_auth_sessions_expires_epoch ON auth_sessions(expires_at_epoch_ms);