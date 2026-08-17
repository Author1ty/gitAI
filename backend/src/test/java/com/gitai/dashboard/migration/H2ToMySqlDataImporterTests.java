package com.gitai.dashboard.migration;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class H2ToMySqlDataImporterTests {

    @TempDir
    Path tempDir;

    @Test
    void importsExistingDataWithoutRecomputingItAndClosesActiveJobs() throws Exception {
        String sourceUrl = "jdbc:h2:file:" + tempDir.resolve("legacy-dashboard").toString().replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        String targetUrl = "jdbc:h2:mem:target_dashboard;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection source = DriverManager.getConnection(sourceUrl, "sa", "");
             Connection target = DriverManager.getConnection(targetUrl, "sa", "")) {
            createSchema(source);
            createSchema(target);
            seedLegacySource(source);
            seedCleanTargetBaseline(target);
        }

        JdbcDataSource targetDataSource = new JdbcDataSource();
        targetDataSource.setURL(targetUrl);
        targetDataSource.setUser("sa");
        targetDataSource.setPassword("");
        H2ToMySqlDataImporter.MigrationReport report = new H2ToMySqlDataImporter(targetDataSource, sourceUrl, "sa", "", 2).importData();

        assertEquals(13, report.tables().size());
        assertEquals(1, rows(targetUrl, "departments"));
        assertEquals(1, rows(targetUrl, "repositories"));
        assertEquals(2, rows(targetUrl, "commit_attribution_stats"));
        assertEquals(15, value(targetUrl, "SELECT SUM(ai_lines) FROM commit_attribution_stats"));
        assertEquals(0, value(targetUrl, "SELECT enabled FROM sync_schedule_settings WHERE id = 1"));
        assertEquals(1, value(targetUrl, "SELECT COUNT(*) FROM sync_jobs WHERE status = 'CANCELLED' AND active_repository_id IS NULL"));
        assertEquals(2, value(targetUrl, "SELECT COUNT(*) FROM app_users WHERE username IN ('legacy-admin', 'legacy-viewer')"));
    }

    @Test
    void refusesTargetContainingBusinessData() throws Exception {
        String sourceUrl = "jdbc:h2:file:" + tempDir.resolve("legacy-refusal").toString().replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        String targetUrl = "jdbc:h2:mem:target_refusal;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection source = DriverManager.getConnection(sourceUrl, "sa", "");
             Connection target = DriverManager.getConnection(targetUrl, "sa", "")) {
            createSchema(source);
            createSchema(target);
            seedLegacySource(source);
            execute(target, "INSERT INTO departments (id, name) VALUES (99, 'do-not-overwrite')");
        }
        JdbcDataSource targetDataSource = new JdbcDataSource();
        targetDataSource.setURL(targetUrl);
        targetDataSource.setUser("sa");
        assertThrows(IllegalStateException.class,
                () -> new H2ToMySqlDataImporter(targetDataSource, sourceUrl, "sa", "", 2).importData());
        assertEquals(1, rows(targetUrl, "departments"));
    }

    private static void createSchema(Connection connection) throws Exception {
        String[] statements = {
                "CREATE TABLE departments (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(120) NOT NULL, description VARCHAR(500))",
                "CREATE TABLE projects (id BIGINT AUTO_INCREMENT PRIMARY KEY, department_id BIGINT NOT NULL, name VARCHAR(160) NOT NULL, description VARCHAR(500))",
                "CREATE TABLE repository_groups (id BIGINT AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL, name VARCHAR(160) NOT NULL)",
                "CREATE TABLE repositories (id BIGINT AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL, group_id BIGINT, name VARCHAR(160) NOT NULL, git_url VARCHAR(500), default_branch VARCHAR(120) NOT NULL, mirror_path VARCHAR(1000), last_synced_at TIMESTAMP, last_sync_status VARCHAR(32) NOT NULL, last_sync_error VARCHAR(2000), history_base_sha VARCHAR(64), history_since_sha VARCHAR(64), history_offset BIGINT NOT NULL, history_complete BOOLEAN NOT NULL, synced_head_sha VARCHAR(64))",
                "CREATE TABLE daily_attribution_stats (id BIGINT AUTO_INCREMENT PRIMARY KEY, repository_id BIGINT NOT NULL, stat_date DATE NOT NULL, ai_lines BIGINT NOT NULL, human_lines BIGINT NOT NULL, mixed_lines BIGINT NOT NULL, unknown_lines BIGINT NOT NULL, commit_count INT NOT NULL, synced_at TIMESTAMP NOT NULL)",
                "CREATE TABLE agent_daily_stats (id BIGINT AUTO_INCREMENT PRIMARY KEY, repository_id BIGINT NOT NULL, stat_date DATE NOT NULL, agent VARCHAR(100) NOT NULL, model VARCHAR(160) NOT NULL, ai_lines BIGINT NOT NULL, session_count INT NOT NULL)",
                "CREATE TABLE commit_attribution_stats (id BIGINT AUTO_INCREMENT PRIMARY KEY, repository_id BIGINT NOT NULL, commit_sha VARCHAR(64) NOT NULL, commit_date DATE NOT NULL, commit_author VARCHAR(255), commit_subject VARCHAR(1000), note_object_sha VARCHAR(64), source_note_ref VARCHAR(120), ai_lines BIGINT NOT NULL, human_lines BIGINT NOT NULL, mixed_lines BIGINT NOT NULL, unknown_lines BIGINT NOT NULL, additions BIGINT NOT NULL, deletions BIGINT NOT NULL)",
                "CREATE TABLE commit_agent_stats (id BIGINT AUTO_INCREMENT PRIMARY KEY, repository_id BIGINT NOT NULL, commit_sha VARCHAR(64) NOT NULL, agent VARCHAR(100) NOT NULL, model VARCHAR(160) NOT NULL, ai_lines BIGINT NOT NULL, accepted_lines BIGINT NOT NULL, session_count INT NOT NULL)",
                "CREATE TABLE sync_jobs (id BIGINT AUTO_INCREMENT PRIMARY KEY, repository_id BIGINT NOT NULL, active_repository_id BIGINT, status VARCHAR(16) NOT NULL, requested_at TIMESTAMP NOT NULL, started_at TIMESTAMP, finished_at TIMESTAMP, processed_commits BIGINT NOT NULL, history_complete BOOLEAN, history_offset BIGINT NOT NULL, message VARCHAR(500), error_message VARCHAR(2000), phase VARCHAR(32) NOT NULL, phase_updated_at TIMESTAMP, batch_commit_count BIGINT NOT NULL)",
                "CREATE TABLE sync_schedule_settings (id INT PRIMARY KEY, enabled BOOLEAN NOT NULL, interval_minutes INT NOT NULL, last_triggered_at TIMESTAMP, updated_at TIMESTAMP NOT NULL)",
                "CREATE TABLE app_users (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(120) NOT NULL, display_name VARCHAR(160) NOT NULL, role VARCHAR(32) NOT NULL, department_id BIGINT, enabled BOOLEAN NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)",
                "CREATE TABLE auth_sessions (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, token_hash CHAR(64) NOT NULL, expires_at TIMESTAMP NOT NULL, created_at TIMESTAMP NOT NULL, last_seen_at TIMESTAMP NOT NULL)",
                "CREATE TABLE operation_audit_logs (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, username VARCHAR(120), department_id BIGINT, action VARCHAR(100) NOT NULL, target_type VARCHAR(100), target_id VARCHAR(120), detail VARCHAR(2000), created_at TIMESTAMP NOT NULL)"
        };
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }

    private static void seedLegacySource(Connection connection) throws Exception {
        String now = "TIMESTAMP '2026-08-17 10:00:00'";
        execute(connection, "INSERT INTO departments VALUES (10, 'Engineering', 'Legacy department')");
        execute(connection, "INSERT INTO projects VALUES (20, 10, 'Platform', 'Legacy project')");
        execute(connection, "INSERT INTO repository_groups VALUES (30, 20, 'Core')");
        execute(connection, "INSERT INTO repositories VALUES (40, 20, 30, 'api', 'https://git.example/api.git', 'main', '/old/mirror/api.git', " + now + ", 'SUCCESS', NULL, 'base', 'since', 12, TRUE, 'head')");
        execute(connection, "INSERT INTO daily_attribution_stats VALUES (50, 40, DATE '2026-08-16', 12, 8, 1, 2, 2, " + now + ")");
        execute(connection, "INSERT INTO agent_daily_stats VALUES (60, 40, DATE '2026-08-16', 'cursor', 'model-a', 12, 1)");
        execute(connection, "INSERT INTO commit_attribution_stats VALUES (70, 40, 'abc', DATE '2026-08-16', 'Alice', 'first', 'note1', 'refs/notes/ai', 12, 8, 1, 2, 20, 3)");
        execute(connection, "INSERT INTO commit_attribution_stats VALUES (71, 40, 'def', DATE '2026-08-17', 'Bob', 'second', 'note2', 'refs/notes/ai', 3, 2, 0, 0, 5, 1)");
        execute(connection, "INSERT INTO commit_agent_stats VALUES (80, 40, 'abc', 'cursor', 'model-a', 12, 10, 1)");
        execute(connection, "INSERT INTO sync_jobs VALUES (90, 40, 40, 'RUNNING', " + now + ", " + now + ", NULL, 1, FALSE, 1, 'In progress', NULL, 'READING_COMMITS', " + now + ", 2)");
        execute(connection, "INSERT INTO sync_schedule_settings VALUES (1, TRUE, 60, " + now + ", " + now + ")");
        execute(connection, "INSERT INTO app_users VALUES (100, 'legacy-admin', 'Legacy Admin', 'SUPER_ADMIN', NULL, TRUE, " + now + ", " + now + ")");
        execute(connection, "INSERT INTO app_users VALUES (101, 'legacy-viewer', 'Legacy Viewer', 'VIEWER', 10, TRUE, " + now + ", " + now + ")");
        execute(connection, "INSERT INTO auth_sessions VALUES (110, 100, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', " + now + ", " + now + ", " + now + ")");
        execute(connection, "INSERT INTO operation_audit_logs VALUES (120, 100, 'legacy-admin', 10, 'IMPORT_TEST', 'repository', '40', 'preserve audit', " + now + ")");
    }

    private static void seedCleanTargetBaseline(Connection connection) throws Exception {
        String now = "TIMESTAMP '2026-08-17 10:00:00'";
        execute(connection, "INSERT INTO departments VALUES (1, 'Root', 'baseline')");
        execute(connection, "INSERT INTO app_users VALUES (1, 'superadmin', 'System', 'SUPER_ADMIN', NULL, TRUE, " + now + ", " + now + ")");
        execute(connection, "INSERT INTO app_users VALUES (2, 'deptadmin', 'Department', 'DEPARTMENT_ADMIN', 1, TRUE, " + now + ", " + now + ")");
        execute(connection, "INSERT INTO app_users VALUES (3, 'viewer', 'Viewer', 'VIEWER', NULL, TRUE, " + now + ", " + now + ")");
        execute(connection, "INSERT INTO sync_schedule_settings VALUES (1, FALSE, 60, NULL, " + now + ")");
    }

    private static long rows(String url, String table) throws Exception { return value(url, "SELECT COUNT(*) FROM " + table); }
    private static long value(String url, String query) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            result.next();
            return result.getLong(1);
        }
    }
    private static void execute(Connection connection, String sql) throws Exception { try (Statement statement = connection.createStatement()) { statement.execute(sql); } }
}