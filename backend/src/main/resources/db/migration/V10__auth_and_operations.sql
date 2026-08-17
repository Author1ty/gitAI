CREATE TABLE app_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    role VARCHAR(32) NOT NULL,
    department_id BIGINT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_app_users_department FOREIGN KEY (department_id) REFERENCES departments(id)
);

CREATE TABLE auth_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);

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
);

CREATE INDEX idx_auth_sessions_token ON auth_sessions(token_hash);
CREATE INDEX idx_auth_sessions_expires ON auth_sessions(expires_at);
CREATE INDEX idx_audit_logs_created_at ON operation_audit_logs(created_at);
CREATE INDEX idx_audit_logs_user_created ON operation_audit_logs(user_id, created_at);
CREATE INDEX idx_audit_logs_department_created ON operation_audit_logs(department_id, created_at);

INSERT INTO app_users (id, username, display_name, role, department_id, enabled) VALUES
    (1, 'superadmin', 'System Administrator', 'SUPER_ADMIN', NULL, TRUE),
    (2, 'deptadmin', 'Department Administrator', 'DEPARTMENT_ADMIN', 1, TRUE),
    (3, 'viewer', 'Read Only Viewer', 'VIEWER', NULL, TRUE);
