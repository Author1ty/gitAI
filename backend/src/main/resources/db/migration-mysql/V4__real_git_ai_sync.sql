-- Keep the root department required by the built-in department administrator only.
-- Repositories must be configured by an administrator; do not ship development-machine paths or sample Git URLs.
INSERT INTO departments (id, name, description)
VALUES (1, '研发效能部', '系统默认根部门，仅用于初始化权限关联。');
