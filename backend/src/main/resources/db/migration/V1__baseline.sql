CREATE TABLE departments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    description VARCHAR(500)
);

CREATE TABLE projects (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    department_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    CONSTRAINT fk_projects_department FOREIGN KEY (department_id) REFERENCES departments(id),
    CONSTRAINT uk_project_per_department UNIQUE (department_id, name)
);

CREATE TABLE repository_groups (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    CONSTRAINT fk_groups_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT uk_group_per_project UNIQUE (project_id, name)
);

CREATE TABLE repositories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    group_id BIGINT NULL,
    name VARCHAR(160) NOT NULL,
    git_url VARCHAR(500),
    default_branch VARCHAR(120) NOT NULL DEFAULT 'main',
    CONSTRAINT fk_repositories_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_repositories_group FOREIGN KEY (group_id) REFERENCES repository_groups(id),
    CONSTRAINT uk_repository_per_project UNIQUE (project_id, name)
);

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
);

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
);

CREATE INDEX idx_daily_stats_date ON daily_attribution_stats(stat_date);
CREATE INDEX idx_daily_stats_repository_date ON daily_attribution_stats(repository_id, stat_date);
CREATE INDEX idx_repositories_group ON repositories(group_id);
