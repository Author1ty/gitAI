package com.gitai.dashboard.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(CurrentUser user, String action, String targetType, Object targetId, String detail) {
        log.info("audit event action={} user={} targetType={} targetId={} detail={}", action,
                user == null ? "anonymous" : user.username(), targetType, targetId, detail);
        jdbc.update("""
                insert into operation_audit_logs (user_id, username, department_id, action, target_type, target_id, detail)
                values (?, ?, ?, ?, ?, ?, ?)
                """, user == null ? null : user.id(), user == null ? null : user.username(),
                user == null ? null : user.departmentId(), action, targetType,
                targetId == null ? null : String.valueOf(targetId), detail);
    }
}
