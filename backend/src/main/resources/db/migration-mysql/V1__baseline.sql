-- MySQL 8.0+ baseline.  All production tables explicitly use InnoDB and utf8mb4.
CREATE TABLE departments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    description VARCHAR(500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE projects (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    department_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    CONSTRAINT fk_projects_department FOREIGN KEY (department_id) REFERENCES departments(id),
    CONSTRAINT uk_project_per_department UNIQUE (department_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE repository_groups (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    CONSTRAINT fk_groups_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT uk_group_per_project UNIQUE (project_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE repositories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    group_id BIGINT NULL,
    name VARCHAR(160) NOT NULL,
    git_url VARCHAR(500),
    default_branch VARCHAR(120) NOT NULL DEFAULT 'main',
    mirror_path VARCHAR(1000),
    last_synced_at TIMESTAMP NULL,
    last_sync_status VARCHAR(32) NOT NULL DEFAULT 'NOT_SYNCED',
    last_sync_error VARCHAR(2000),
    history_base_sha VARCHAR(64),
    history_since_sha VARCHAR(64),
    history_offset BIGINT NOT NULL DEFAULT 0,
    history_complete TINYINT(1) NOT NULL DEFAULT 0,
    synced_head_sha VARCHAR(64),
    CONSTRAINT fk_repositories_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_repositories_group FOREIGN KEY (group_id) REFERENCES repository_groups(id),
    CONSTRAINT uk_repository_per_project UNIQUE (project_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE daily_attribution_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    ai_lines BIGINT NOT NULL DEFAULT 0,
    human_lines BIGINT NOT NULL DEFAULT 0,
    mixed_lines BIGINT NOT NULL DEFAULT 0,
    unknown_lines BIGINT NOT NULL DEFAULT 0,
    commit_count INT NOT NULL DEFAULT 0,
    synced_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_daily_repository FOREIGN KEY (repository_id) REFERENCES repositories(id),
    CONSTRAINT uk_daily_repository UNIQUE (repository_id, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_daily_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    agent VARCHAR(100) NOT NULL,
    model VARCHAR(160) NOT NULL,
    ai_lines BIGINT NOT NULL DEFAULT 0,
    session_count INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_agent_repository FOREIGN KEY (repository_id) REFERENCES repositories(id),
    CONSTRAINT uk_agent_daily UNIQUE (repository_id, stat_date, agent, model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE commit_attribution_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    commit_date DATE NOT NULL,
    commit_author VARCHAR(255),
    commit_subject VARCHAR(1000),
    note_object_sha VARCHAR(64),
    source_note_ref VARCHAR(120),
    ai_lines BIGINT NOT NULL DEFAULT 0,
    human_lines BIGINT NOT NULL DEFAULT 0,
    mixed_lines BIGINT NOT NULL DEFAULT 0,
    unknown_lines BIGINT NOT NULL DEFAULT 0,
    additions BIGINT NOT NULL DEFAULT 0,
    deletions BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_commit_repository FOREIGN KEY (repository_id) REFERENCES repositories(id),
    CONSTRAINT uk_commit_repository UNIQUE (repository_id, commit_sha)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE commit_agent_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    agent VARCHAR(100) NOT NULL,
    model VARCHAR(160) NOT NULL,
    ai_lines BIGINT NOT NULL DEFAULT 0,
    accepted_lines BIGINT NOT NULL DEFAULT 0,
    session_count INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_commit_agent_repository FOREIGN KEY (repository_id) REFERENCES repositories(id),
    CONSTRAINT uk_commit_agent UNIQUE (repository_id, commit_sha, agent, model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sync_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    active_repository_id BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    processed_commits BIGINT NOT NULL DEFAULT 0,
    history_complete TINYINT(1) NULL,
    history_offset BIGINT NOT NULL DEFAULT 0,
    message VARCHAR(500),
    error_message VARCHAR(2000),
    phase VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    phase_updated_at TIMESTAMP NULL,
    batch_commit_count BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_sync_jobs_repository FOREIGN KEY (repository_id) REFERENCES repositories(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sync_schedule_settings (
    id INT NOT NULL PRIMARY KEY,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    interval_minutes INT NOT NULL DEFAULT 60,
    last_triggered_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE app_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    role VARCHAR(32) NOT NULL,
    department_id BIGINT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_app_users_department FOREIGN KEY (department_id) REFERENCES departments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auth_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE operation_audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    username VARCHAR(120),
    department_id BIGINT NULL,
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(100),
    target_id VARCHAR(120),
    detail VARCHAR(2000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_logs_user FOREIGN KEY (user_id) REFERENCES app_users(id),
    CONSTRAINT fk_audit_logs_department FOREIGN KEY (department_id) REFERENCES departments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_daily_stats_date ON daily_attribution_stats(stat_date);
CREATE INDEX idx_daily_stats_repository_date ON daily_attribution_stats(repository_id, stat_date);
CREATE INDEX idx_repositories_group ON repositories(group_id);
CREATE INDEX idx_commit_stats_repository_date ON commit_attribution_stats(repository_id, commit_date);
CREATE INDEX idx_commit_stats_repository_sha ON commit_attribution_stats(repository_id, commit_sha);
CREATE INDEX idx_commit_agent_repository_commit ON commit_agent_stats(repository_id, commit_sha);
CREATE UNIQUE INDEX uk_sync_jobs_active_repository ON sync_jobs(active_repository_id);
CREATE INDEX idx_sync_jobs_status_requested ON sync_jobs(status, requested_at);
CREATE INDEX idx_sync_jobs_repository_requested ON sync_jobs(repository_id, requested_at);
CREATE INDEX idx_sync_jobs_status_phase_updated ON sync_jobs(status, phase_updated_at);
CREATE INDEX idx_commit_stats_date_author ON commit_attribution_stats(commit_date, commit_author);
CREATE INDEX idx_commit_stats_author ON commit_attribution_stats(commit_author);
CREATE INDEX idx_auth_sessions_token ON auth_sessions(token_hash);
CREATE INDEX idx_auth_sessions_expires ON auth_sessions(expires_at);
CREATE INDEX idx_audit_logs_created_at ON operation_audit_logs(created_at);
CREATE INDEX idx_audit_logs_user_created ON operation_audit_logs(user_id, created_at);
CREATE INDEX idx_audit_logs_department_created ON operation_audit_logs(department_id, created_at);
