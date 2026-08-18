CREATE TABLE sync_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    active_repository_id BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    processed_commits BIGINT NOT NULL DEFAULT 0,
    history_complete BOOLEAN NULL,
    history_offset BIGINT NOT NULL DEFAULT 0,
    message VARCHAR(500),
    error_message VARCHAR(2000),
    CONSTRAINT fk_sync_jobs_repository FOREIGN KEY (repository_id) REFERENCES repositories(id)
);

-- NULL permits completed job history to coexist; a non-NULL value enforces one active job per repository.
CREATE UNIQUE INDEX uk_sync_jobs_active_repository ON sync_jobs(active_repository_id);
CREATE INDEX idx_sync_jobs_status_requested ON sync_jobs(status, requested_at);
CREATE INDEX idx_sync_jobs_repository_requested ON sync_jobs(repository_id, requested_at);
