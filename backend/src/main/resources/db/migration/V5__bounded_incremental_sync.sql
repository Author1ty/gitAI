ALTER TABLE repositories ADD COLUMN history_base_sha VARCHAR(64);
ALTER TABLE repositories ADD COLUMN history_since_sha VARCHAR(64);
ALTER TABLE repositories ADD COLUMN history_offset BIGINT NOT NULL DEFAULT 0;
ALTER TABLE repositories ADD COLUMN history_complete BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE repositories ADD COLUMN synced_head_sha VARCHAR(64);

CREATE INDEX idx_commit_stats_repository_sha ON commit_attribution_stats(repository_id, commit_sha);
