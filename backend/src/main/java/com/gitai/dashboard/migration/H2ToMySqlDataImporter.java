package com.gitai.dashboard.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Copies the persisted dashboard data from an existing H2 database into a freshly initialized MySQL database.
 * It performs database reads and inserts only: it never creates sync jobs, invokes Git, clones/fetches, or parses notes.
 */
public class H2ToMySqlDataImporter {
    private static final Logger log = LoggerFactory.getLogger(H2ToMySqlDataImporter.class);

    private static final List<TableDefinition> TABLES = List.of(
            table("departments", "id", "name", "description"),
            table("projects", "id", "department_id", "name", "description"),
            table("repository_groups", "id", "project_id", "name"),
            table("repositories", "id", "project_id", "group_id", "name", "git_url", "default_branch", "mirror_path",
                    "last_synced_at", "last_sync_status", "last_sync_error", "history_base_sha", "history_since_sha", "history_offset",
                    "history_complete", "synced_head_sha", "sync_configured_at", "sync_window_initialized"),
            table("daily_attribution_stats", "id", "repository_id", "stat_date", "ai_lines", "human_lines", "mixed_lines", "unknown_lines", "commit_count", "synced_at"),
            table("agent_daily_stats", "id", "repository_id", "stat_date", "agent", "model", "ai_lines", "session_count"),
            table("commit_attribution_stats", "id", "repository_id", "commit_sha", "commit_date", "commit_author", "commit_subject",
                    "note_object_sha", "source_note_ref", "ai_lines", "human_lines", "mixed_lines", "unknown_lines", "additions", "deletions"),
            table("commit_agent_stats", "id", "repository_id", "commit_sha", "agent", "model", "ai_lines", "accepted_lines", "session_count"),
            table("sync_jobs", "id", "repository_id", "active_repository_id", "status", "requested_at", "started_at", "finished_at",
                    "processed_commits", "history_complete", "history_offset", "message", "error_message", "phase", "phase_updated_at", "batch_commit_count"),
            table("sync_schedule_settings", "id", "enabled", "interval_minutes", "last_triggered_at", "updated_at"),
            table("app_users", "id", "username", "display_name", "role", "department_id", "enabled", "created_at", "updated_at"),
            table("auth_sessions", "id", "user_id", "token_hash", "expires_at", "created_at", "last_seen_at"),
            table("operation_audit_logs", "id", "user_id", "username", "department_id", "action", "target_type", "target_id", "detail", "created_at")
    );
    private static final List<String> IDENTITY_TABLES = TABLES.stream()
            .map(TableDefinition::name)
            .filter(name -> !"sync_schedule_settings".equals(name))
            .toList();

    private final DataSource targetDataSource;
    private final String sourceUrl;
    private final String sourceUsername;
    private final String sourcePassword;
    private final int batchSize;

    public H2ToMySqlDataImporter(DataSource targetDataSource, String sourceUrl, String sourceUsername, String sourcePassword, int batchSize) {
        this.targetDataSource = targetDataSource;
        this.sourceUrl = requireText(sourceUrl, "git-ai.h2-import.source-url is required");
        this.sourceUsername = sourceUsername == null ? "sa" : sourceUsername;
        this.sourcePassword = sourcePassword == null ? "" : sourcePassword;
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("git-ai.h2-import.batch-size must be between 1 and 10000");
        }
        this.batchSize = batchSize;
    }

    public MigrationReport importData() throws SQLException {
        long startedNanos = System.nanoTime();
        try (Connection source = java.sql.DriverManager.getConnection(readOnlyH2Url(sourceUrl), sourceUsername, sourcePassword);
             Connection target = targetDataSource.getConnection()) {
            source.setReadOnly(true);
            requireH2Source(source);
            requireTables(source, "source H2 database");
            requireTables(target, "target database");
            prepareEmptyTarget(target);

            target.setAutoCommit(false);
            List<TableReport> reports = new ArrayList<>();
            try {
                for (TableDefinition table : TABLES) {
                    reports.add(copyTable(source, target, table));
                }
                disableAllFutureSyncs(target);
                resetIdentityValues(target);
                validateCountsAndAttribution(source, target, reports);
                target.commit();
            } catch (Exception exception) {
                target.rollback();
                throw exception;
            } finally {
                target.setAutoCommit(true);
            }
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
            return new MigrationReport(reports, elapsedMillis);
        }
    }

    private TableReport copyTable(Connection source, Connection target, TableDefinition table) throws SQLException {
        long startedNanos = System.nanoTime();
        long sourceRows = countRows(source, table.name());
        String columns = String.join(", ", table.columns());
        String placeholders = String.join(", ", java.util.Collections.nCopies(table.columns().size(), "?"));
        String selectSql = "SELECT " + columns + " FROM " + table.name() + " ORDER BY id";
        String insertSql = "INSERT INTO " + table.name() + " (" + columns + ") VALUES (" + placeholders + ")";
        long copied = 0;
        try (PreparedStatement select = source.prepareStatement(selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement insert = target.prepareStatement(insertSql)) {
            select.setFetchSize(batchSize);
            try (ResultSet rows = select.executeQuery()) {
                int pending = 0;
                while (rows.next()) {
                    for (int index = 0; index < table.columns().size(); index++) {
                        insert.setObject(index + 1, rows.getObject(index + 1));
                    }
                    insert.addBatch();
                    pending++;
                    copied++;
                    if (pending == batchSize) {
                        insert.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    insert.executeBatch();
                }
            }
        }
        long targetRows = countRows(target, table.name());
        if (sourceRows != copied || sourceRows != targetRows) {
            throw new SQLException("Row validation failed for " + table.name() + ": source=" + sourceRows + ", copied=" + copied + ", target=" + targetRows);
        }
        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        log.info("H2 import copied table={} rows={} elapsedMs={}", table.name(), copied, elapsedMillis);
        return new TableReport(table.name(), sourceRows, targetRows, maxId(target, table.name()), elapsedMillis);
    }

    private void prepareEmptyTarget(Connection target) throws SQLException {
        Map<String, Long> counts = tableCounts(target);
        boolean completelyEmpty = counts.values().stream().allMatch(count -> count == 0);
        boolean cleanFlywayBaseline = counts.get("departments") == 1
                && counts.get("app_users") == 3
                && counts.get("sync_schedule_settings") == 1
                && counts.entrySet().stream()
                .filter(entry -> !List.of("departments", "app_users", "sync_schedule_settings").contains(entry.getKey()))
                .allMatch(entry -> entry.getValue() == 0);
        if (!completelyEmpty && !cleanFlywayBaseline) {
            throw new IllegalStateException("Target database is not empty or a clean Git AI MySQL baseline. "
                    + "For safety, import was not started. Create a new empty MySQL database and let Flyway initialize it first. Current rows=" + counts);
        }
        if (cleanFlywayBaseline) {
            // These are only the baseline rows created by the MySQL Flyway migrations. Delete them before preserving source IDs.
            try (Statement statement = target.createStatement()) {
                statement.executeUpdate("DELETE FROM sync_schedule_settings");
                statement.executeUpdate("DELETE FROM app_users");
                statement.executeUpdate("DELETE FROM departments");
            }
        }
    }

    private void disableAllFutureSyncs(Connection target) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.executeUpdate("UPDATE sync_schedule_settings SET enabled = 0, last_triggered_at = NULL, updated_at = CURRENT_TIMESTAMP");
            statement.executeUpdate("UPDATE sync_jobs SET active_repository_id = NULL");
            statement.executeUpdate("""
                    UPDATE sync_jobs
                    SET status = 'CANCELLED', phase = 'CANCELLED', phase_updated_at = CURRENT_TIMESTAMP,
                        finished_at = COALESCE(finished_at, CURRENT_TIMESTAMP),
                        message = CONCAT(LEFT(COALESCE(message, ''), 420), ' [closed during H2-to-MySQL migration; not re-run]')
                    WHERE status IN ('QUEUED', 'RUNNING')
                    """);
        }
        log.info("H2 import safety post-processing completed: automatic sync disabled and any active jobs closed without execution");
    }

    private void resetIdentityValues(Connection target) throws SQLException {
        String product = target.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        for (String table : IDENTITY_TABLES) {
            long next = maxId(target, table) + 1;
            try (Statement statement = target.createStatement()) {
                if (product.contains("mysql")) {
                    statement.execute("ALTER TABLE " + table + " AUTO_INCREMENT = " + next);
                } else if (product.contains("h2")) {
                    statement.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH " + next);
                }
            }
        }
    }

    private void validateCountsAndAttribution(Connection source, Connection target, List<TableReport> reports) throws SQLException {
        for (TableReport report : reports) {
            long actual = countRows(target, report.table());
            if (report.sourceRows() != actual) {
                throw new SQLException("Post-import count mismatch for " + report.table() + ": source=" + report.sourceRows() + ", target=" + actual);
            }
        }
        for (String table : List.of("daily_attribution_stats", "commit_attribution_stats", "agent_daily_stats", "commit_agent_stats")) {
            Map<String, Long> sourceAggregate = attributionAggregate(source, table);
            Map<String, Long> targetAggregate = attributionAggregate(target, table);
            if (!sourceAggregate.equals(targetAggregate)) {
                throw new SQLException("Attribution aggregate mismatch for " + table + ": source=" + sourceAggregate + ", target=" + targetAggregate);
            }
        }
        log.info("H2 import validation passed: all table counts and AI attribution aggregates are identical");
    }

    private Map<String, Long> attributionAggregate(Connection connection, String table) throws SQLException {
        String sql = switch (table) {
            case "daily_attribution_stats", "commit_attribution_stats" ->
                    "SELECT COUNT(*) AS row_count, COALESCE(SUM(ai_lines), 0) AS ai_lines, COALESCE(SUM(human_lines), 0) AS human_lines, "
                            + "COALESCE(SUM(mixed_lines), 0) AS mixed_lines, COALESCE(SUM(unknown_lines), 0) AS unknown_lines FROM " + table;
            case "agent_daily_stats", "commit_agent_stats" ->
                    "SELECT COUNT(*) AS row_count, COALESCE(SUM(ai_lines), 0) AS ai_lines, COALESCE(SUM(session_count), 0) AS session_count FROM " + table;
            default -> throw new IllegalArgumentException("Unsupported attribution aggregate table: " + table);
        };
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            Map<String, Long> result = new LinkedHashMap<>();
            result.put("row_count", row.getLong("row_count"));
            result.put("ai_lines", row.getLong("ai_lines"));
            if (table.equals("daily_attribution_stats") || table.equals("commit_attribution_stats")) {
                result.put("human_lines", row.getLong("human_lines"));
                result.put("mixed_lines", row.getLong("mixed_lines"));
                result.put("unknown_lines", row.getLong("unknown_lines"));
            } else {
                result.put("session_count", row.getLong("session_count"));
            }
            return result;
        }
    }

    private Map<String, Long> tableCounts(Connection connection) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TableDefinition table : TABLES) counts.put(table.name(), countRows(connection, table.name()));
        return counts;
    }

    private void requireTables(Connection connection, String description) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (TableDefinition table : TABLES) {
            boolean exists = false;
            for (String candidate : List.of(table.name(), table.name().toUpperCase(Locale.ROOT))) {
                try (ResultSet tables = metadata.getTables(null, null, candidate, new String[]{"TABLE"})) {
                    if (tables.next()) { exists = true; break; }
                }
            }
            if (!exists) throw new IllegalStateException("Required table '" + table.name() + "' is missing from " + description);
        }
    }

    private void requireH2Source(Connection source) throws SQLException {
        String product = source.getMetaData().getDatabaseProductName();
        if (!product.toLowerCase(Locale.ROOT).contains("h2")) {
            throw new IllegalArgumentException("git-ai.h2-import.source-url must point to an H2 database; detected " + product);
        }
    }

    private long countRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private long maxId(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static TableDefinition table(String name, String... columns) {
        return new TableDefinition(name, List.copyOf(Arrays.asList(columns)));
    }

    private static String readOnlyH2Url(String url) {
        String normalized = requireText(url, "git-ai.h2-import.source-url is required");
        return normalized.toUpperCase(Locale.ROOT).contains("ACCESS_MODE_DATA=") ? normalized : normalized + ";ACCESS_MODE_DATA=r";
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    public record TableReport(String table, long sourceRows, long targetRows, long maxId, long elapsedMillis) {}
    public record MigrationReport(List<TableReport> tables, long elapsedMillis) {
        public long totalRows() { return tables.stream().mapToLong(TableReport::targetRows).sum(); }
    }
    private record TableDefinition(String name, List<String> columns) {}
}
