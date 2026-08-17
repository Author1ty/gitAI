INSERT INTO app_users (id, username, display_name, role, department_id, enabled) VALUES
    (1, 'superadmin', '系统管理员', 'SUPER_ADMIN', NULL, 1),
    (2, 'deptadmin', '部门管理员', 'DEPARTMENT_ADMIN', 1, 1),
    (3, 'viewer', '只读查看者', 'VIEWER', NULL, 1);
