package com.gitai.dashboard.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AccessPolicy {
    private static final Logger log = LoggerFactory.getLogger(AccessPolicy.class);
    private final AuthService auth;
    private final JdbcTemplate jdbc;

    public AccessPolicy(AuthService auth, JdbcTemplate jdbc) { this.auth = auth; this.jdbc = jdbc; }

    public CurrentUser currentUser() { return auth.currentUser(); }

    public void requireSuperAdmin() {
        if (!currentUser().isSuperAdmin()) forbidden();
    }

    public void requireManage() {
        if (!currentUser().canManage()) forbidden();
    }

    public Long dashboardDepartment(Long requestedDepartmentId, Long projectId, Long groupId, Long repositoryId) {
        CurrentUser user = currentUser();
        if (!user.isDepartmentAdmin()) return requestedDepartmentId;
        Long departmentId = user.departmentId();
        if (departmentId == null) forbidden();
        if (requestedDepartmentId != null && !requestedDepartmentId.equals(departmentId)) forbidden();
        if (projectId != null) requireProject(projectId);
        if (groupId != null) requireGroup(groupId);
        if (repositoryId != null) requireRepository(repositoryId);
        return departmentId;
    }

    public void requireDepartment(long departmentId) {
        requireManage();
        CurrentUser user = currentUser();
        if (user.isDepartmentAdmin() && !Long.valueOf(departmentId).equals(user.departmentId())) forbidden();
    }

    public void requireProject(long projectId) { requireEntityDepartment("projects p", "p.id", projectId); }
    public void requireGroup(long groupId) { requireEntityDepartment("repository_groups g join projects p on p.id = g.project_id", "g.id", groupId); }
    public void requireRepository(long repositoryId) { requireEntityDepartment("repositories r join projects p on p.id = r.project_id", "r.id", repositoryId); }

    public Long allowedDepartmentId() {
        CurrentUser user = currentUser();
        return user.isDepartmentAdmin() ? user.departmentId() : null;
    }

    private void requireEntityDepartment(String source, String idColumn, long id) {
        requireManage();
        CurrentUser user = currentUser();
        Long departmentId = jdbc.query("select p.department_id from " + source + " where " + idColumn + " = ?", 
                rs -> rs.next() ? rs.getLong(1) : null, id);
        if (departmentId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Target resource was not found");
        if (user.isDepartmentAdmin() && !departmentId.equals(user.departmentId())) forbidden();
    }

    private void forbidden() {
        CurrentUser user = currentUser();
        log.warn("access denied username={} userId={} role={} departmentId={}", user.username(), user.id(), user.role(), user.departmentId());
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission for this operation");
    }
}

