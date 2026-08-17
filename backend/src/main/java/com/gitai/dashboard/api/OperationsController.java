package com.gitai.dashboard.api;

import com.gitai.dashboard.auth.AccessPolicy;
import com.gitai.dashboard.auth.AppRole;
import com.gitai.dashboard.auth.AuditService;
import com.gitai.dashboard.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;

/** Operations console: account authorization, durable activity audit, and small operational health summary. */
@RestController
@RequestMapping("/api/operations")
public class OperationsController {
    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final AuditService audit;

    public OperationsController(JdbcTemplate jdbc, AccessPolicy access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping("/overview")
    public OperationsOverview overview() {
        access.requireSuperAdmin();
        return new OperationsOverview(count("select count(*) from app_users"), count("select count(*) from app_users where enabled = TRUE"),
                count("select count(*) from repositories"));
    }

    @GetMapping("/users")
    public List<UserView> users() {
        access.requireSuperAdmin();
        return jdbc.query("""
                select u.id, u.username, u.display_name, u.role, u.department_id, u.enabled, d.name department_name,
                       u.created_at, u.updated_at
                from app_users u left join departments d on d.id = u.department_id
                order by u.username
                """, (rs, row) -> new UserView(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("role"), rs.getObject("department_id", Long.class), rs.getString("department_name"), rs.getBoolean("enabled"),
                text(rs.getTimestamp("created_at")), text(rs.getTimestamp("updated_at"))));
    }

    @PostMapping("/users")
    public UserView createUser(@Valid @RequestBody UserWriteRequest request) {
        access.requireSuperAdmin();
        validateRoleAndDepartment(request.role(), request.departmentId());
        long id = insert("insert into app_users (username, display_name, role, department_id, enabled) values (?, ?, ?, ?, ?)",
                request.username().trim(), request.displayName().trim(), request.role().name(), request.departmentId(), request.enabled());
        audit.record(access.currentUser(), "USER_CREATED", "user", id, request.username().trim() + " / " + request.role().name());
        return user(id);
    }

    @PutMapping("/users/{userId}")
    public UserView updateUser(@PathVariable long userId, @Valid @RequestBody UserWriteRequest request) {
        access.requireSuperAdmin();
        validateRoleAndDepartment(request.role(), request.departmentId());
        int changed = jdbc.update("""
                update app_users set username = ?, display_name = ?, role = ?, department_id = ?, enabled = ?, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, request.username().trim(), request.displayName().trim(), request.role().name(), request.departmentId(), request.enabled(), userId);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User was not found");
        audit.record(access.currentUser(), "USER_UPDATED", "user", userId, request.username().trim() + " / " + request.role().name() + " / enabled=" + request.enabled());
        return user(userId);
    }

    @GetMapping("/audit-logs")
    public List<AuditLogView> auditLogs(@RequestParam(defaultValue = "100") int limit) {
        access.requireSuperAdmin();
        int boundedLimit = Math.clamp(limit, 1, 300);
        return jdbc.query("""
                select id, username, department_id, action, target_type, target_id, detail, created_at
                from operation_audit_logs order by created_at desc, id desc limit ?
                """, (rs, row) -> new AuditLogView(rs.getLong("id"), rs.getString("username"), rs.getObject("department_id", Long.class),
                rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"), rs.getString("detail"), text(rs.getTimestamp("created_at"))), boundedLimit);
    }

    private UserView user(long id) {
        List<UserView> users = jdbc.query("""
                select u.id, u.username, u.display_name, u.role, u.department_id, u.enabled, d.name department_name,
                       u.created_at, u.updated_at
                from app_users u left join departments d on d.id = u.department_id where u.id = ?
                """, (rs, row) -> new UserView(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("role"), rs.getObject("department_id", Long.class), rs.getString("department_name"), rs.getBoolean("enabled"),
                text(rs.getTimestamp("created_at")), text(rs.getTimestamp("updated_at"))), id);
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User was not found");
        return users.getFirst();
    }

    private void validateRoleAndDepartment(AppRole role, Long departmentId) {
        if (role == AppRole.DEPARTMENT_ADMIN && departmentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department administrators require a department");
        }
        if (role != AppRole.DEPARTMENT_ADMIN && departmentId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only department administrators can be assigned a department");
        }
        if (departmentId != null) {
            Long departmentCount = jdbc.queryForObject("select count(*) from departments where id = ?", Long.class, departmentId);
            if (departmentCount == null || departmentCount == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department was not found");
            }
        }
    }

    private long insert(String sql, Object... arguments) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) throw new IllegalStateException("User create did not return an id");
        return id.longValue();
    }

    private long count(String sql) { Long value = jdbc.queryForObject(sql, Long.class); return value == null ? 0 : value; }
    private static String text(Timestamp timestamp) { return timestamp == null ? null : timestamp.toLocalDateTime().toString(); }

    public record OperationsOverview(long totalUsers, long enabledUsers, long repositories) {}
    public record UserView(long id, String username, String displayName, String role, Long departmentId, String departmentName,
                           boolean enabled, String createdAt, String updatedAt) {}
    public record UserWriteRequest(@NotBlank String username, @NotBlank String displayName, @NotNull AppRole role, Long departmentId, @NotNull Boolean enabled) {}
    public record AuditLogView(long id, String username, Long departmentId, String action, String targetType, String targetId, String detail, String createdAt) {}
}
