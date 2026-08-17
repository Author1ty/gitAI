package com.gitai.dashboard.auth;

public record CurrentUser(long id, String username, String displayName, AppRole role, Long departmentId) {
    public boolean isSuperAdmin() { return role == AppRole.SUPER_ADMIN; }
    public boolean isDepartmentAdmin() { return role == AppRole.DEPARTMENT_ADMIN; }
    public boolean canManage() { return role != AppRole.VIEWER; }
}
