-- Persist durable, low-frequency progress so multi-year / multi-million-line imports are observable after refresh or restart.
ALTER TABLE sync_jobs ADD COLUMN phase VARCHAR(32) NOT NULL DEFAULT 'QUEUED';
ALTER TABLE sync_jobs ADD COLUMN phase_updated_at TIMESTAMP NULL;
ALTER TABLE sync_jobs ADD COLUMN batch_commit_count BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_sync_jobs_status_phase_updated ON sync_jobs(status, phase_updated_at);
