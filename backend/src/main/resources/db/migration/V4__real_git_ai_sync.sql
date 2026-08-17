ALTER TABLE repositories ADD COLUMN mirror_path VARCHAR(1000);
ALTER TABLE repositories ADD COLUMN last_synced_at TIMESTAMP NULL;
ALTER TABLE repositories ADD COLUMN last_sync_status VARCHAR(32) NOT NULL DEFAULT 'NOT_SYNCED';
ALTER TABLE repositories ADD COLUMN last_sync_error VARCHAR(2000);

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
);

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
);

CREATE INDEX idx_commit_stats_repository_date ON commit_attribution_stats(repository_id, commit_date);
CREATE INDEX idx_commit_agent_repository_commit ON commit_agent_stats(repository_id, commit_sha);

DELETE FROM agent_daily_stats;
DELETE FROM daily_attribution_stats;
DELETE FROM repositories;
DELETE FROM repository_groups;
DELETE FROM projects;
DELETE FROM departments;

INSERT INTO departments (id, name, description) VALUES
  (1, '研发效能部', '负责工程效率、研发工具和质量平台。');

INSERT INTO projects (id, department_id, name, description) VALUES
  (1, 1, 'Git AI 归因看板', '基于 Git AI Note 的代码归因统计。');

INSERT INTO repository_groups (id, project_id, name) VALUES
  (1, 1, '本地验证仓库');

INSERT INTO repositories (id, project_id, group_id, name, git_url, default_branch, mirror_path, last_sync_status) VALUES
  (1, 1, 1, 'git-ai-attribution-sample',
   'D:/code/skill/git-ai/test-repos/git-ai-attribution-sample-remote.git',
   'main',
   'D:/code/skill/git-ai/test-repos/git-ai-attribution-sample-mirror.git',
   'NOT_SYNCED');