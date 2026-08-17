package com.gitai.dashboard.service;

import com.gitai.dashboard.api.DashboardDtos;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the dashboard's real operating shape: multiple departments,
 * projects, repository groups, repositories, authors and commit dates.
 */
class DashboardServiceMultiDimensionTests {
    private NamedParameterJdbcTemplate jdbc;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:dashboard_multi_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        service = new DashboardService(jdbc);
        insertOrganization();
        insertStatistics();
    }

    @Test
    void aggregatesTheEntireMultiDepartmentLandscape() {
        DashboardDtos.DashboardResponse dashboard = service.dashboard(null, null, null, null, null, null);

        assertEquals(36, dashboard.summary().aiLines());
        assertEquals(42, dashboard.summary().humanLines());
        assertEquals(36, dashboard.summary().mixedLines());
        assertEquals(102, dashboard.summary().unknownLines());
        assertEquals(24, dashboard.summary().commits());
        assertEquals(6, dashboard.summary().repositories());
        assertEquals(4, dashboard.trend().size());
        assertEquals(LocalDate.of(2026, 8, 12), dashboard.trend().getFirst().date());
        assertEquals(LocalDate.of(2026, 8, 15), dashboard.trend().getLast().date());
        assertEquals(2, dashboard.departments().stream().filter(item -> item.id().equals("101") || item.id().equals("102")).count());
        assertEquals(4, dashboard.projects().stream().filter(item -> item.id().matches("20[1-4]")).count());
        assertEquals(6, dashboard.repositories().stream().filter(item -> item.repositoryId() >= 401 && item.repositoryId() <= 406).count());
        assertEquals(4, dashboard.projectPanorama().stream().filter(item -> item.id().matches("20[1-4]")).count());
        assertEquals(4, dashboard.groupPanorama().stream().filter(item -> item.id().matches("30[1-4]")).count());
        assertEquals(3, dashboard.personRankings().byAiLines().size());
        assertEquals(3, dashboard.personRankings().byAiRate().size());
        assertAuthors(dashboard.personRankings().byAiLines());
        assertEquals(2, service.filters(101L).projects().size());
        assertEquals(2, service.filters(101L).groups().size());
        assertEquals(4, service.filters(101L).repositories().size());
        assertTrue(service.filters(101L).departments().stream().allMatch(item -> item.id().equals(101L)));
    }

    @Test
    void keepsConfiguredRepositoryVisibleBeforeItsFirstSuccessfulSync() {
        insertRepository(499, 201, 301, "remote-waiting-for-first-sync");
        update("update repositories set last_sync_status = 'NOT_SYNCED', history_complete = false, history_offset = 0 where id = 499");

        DashboardDtos.DashboardResponse dashboard = service.dashboard(null, null, null, null, null, null);

        DashboardDtos.RepositoryMetric repository = dashboard.repositories().stream()
                .filter(item -> item.repositoryId() == 499L)
                .findFirst()
                .orElseThrow();
        assertEquals(0, repository.aiLines());
        assertEquals(0, repository.humanLines());
        assertEquals(0, repository.mixedLines());
        assertEquals(0, repository.unknownLines());
        assertEquals(null, repository.syncedAt());
        assertEquals("NOT_SYNCED", repository.syncStatus());
        assertTrue(!repository.historyComplete());
    }

    @Test
    void appliesDepartmentProjectGroupRepositoryAndDateFiltersToEverySection() {
        DashboardDtos.DashboardResponse department = service.dashboard(101L, null, null, null, null, null);
        assertEquals(4, department.summary().repositories());
        assertEquals(16, department.summary().commits());
        assertEquals(2, department.projectPanorama().size());
        assertEquals(2, department.groupPanorama().size());
        assertEquals(4, department.repositories().size());
        assertEquals(3, department.personRankings().byAiLines().size());

        DashboardDtos.DashboardResponse project = service.dashboard(null, 201L, null, null, null, null);
        assertEquals(2, project.summary().repositories());
        assertEquals(8, project.summary().commits());
        assertEquals(1, project.projectPanorama().size());
        assertEquals(1, project.groupPanorama().size());
        assertEquals(2, project.repositories().size());

        DashboardDtos.DashboardResponse group = service.dashboard(null, null, null, 301L, null, null);
        assertEquals(project.summary(), group.summary());
        assertEquals(2, group.repositories().size());

        DashboardDtos.DashboardResponse repository = service.dashboard(null, null, 401L, null, null, null);
        assertEquals(1, repository.summary().repositories());
        assertEquals(4, repository.summary().commits());
        assertEquals(6, repository.summary().aiLines());
        assertEquals(7, repository.summary().humanLines());
        assertEquals(6, repository.summary().mixedLines());
        assertEquals(17, repository.summary().unknownLines());
        assertEquals(1, repository.repositories().size());
        assertEquals(1, repository.projectPanorama().size());
        assertEquals(1, repository.groupPanorama().size());

        DashboardDtos.DashboardResponse yesterday = service.dashboard(null, null, null, null,
                LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 15));
        assertEquals(6, yesterday.summary().repositories());
        assertEquals(6, yesterday.summary().commits());
        assertEquals(1, yesterday.trend().size());
        assertEquals(LocalDate.of(2026, 8, 15), yesterday.trend().getFirst().date());
        assertEquals(4, yesterday.projectPanorama().size());
        assertEquals(4, yesterday.groupPanorama().size());
        assertAuthors(yesterday.personRankings().byAiLines());
    }

    private void assertAuthors(List<DashboardDtos.PersonMetric> people) {
        assertTrue(people.stream().anyMatch(item -> item.author().equals("Alice")));
        assertTrue(people.stream().anyMatch(item -> item.author().equals("Bob")));
        assertTrue(people.stream().anyMatch(item -> item.author().equals("Carol")));
    }

    private void insertOrganization() {
        update("insert into departments (id, name, description) values (101, 'Business Engineering', 'test')");
        update("insert into departments (id, name, description) values (102, 'Platform Engineering', 'test')");
        update("insert into projects (id, department_id, name, description) values (201, 101, 'Commerce Platform', 'test')");
        update("insert into projects (id, department_id, name, description) values (202, 101, 'Merchant Workspace', 'test')");
        update("insert into projects (id, department_id, name, description) values (203, 102, 'Quality Platform', 'test')");
        update("insert into projects (id, department_id, name, description) values (204, 102, 'Observability Platform', 'test')");
        update("insert into repository_groups (id, project_id, name) values (301, 201, 'Core Services')");
        update("insert into repository_groups (id, project_id, name) values (302, 202, 'Frontend Apps')");
        update("insert into repository_groups (id, project_id, name) values (303, 203, 'Engineering Productivity')");
        update("insert into repository_groups (id, project_id, name) values (304, 204, 'Developer Tools')");
        insertRepository(401, 201, 301, "commerce-api");
        insertRepository(402, 201, 301, "payment-worker");
        insertRepository(403, 202, 302, "merchant-web");
        insertRepository(404, 202, 302, "customer-portal");
        insertRepository(405, 203, 303, "quality-gateway");
        insertRepository(406, 204, 304, "observability-cli");
    }

    private void insertStatistics() {
        String[][] authorMatrix = {
                {"Alice", "Bob", "Carol", "Alice"},
                {"Bob", "Carol", "Alice", "Bob"},
                {"Carol", "Alice", "Bob", "Carol"},
                {"Alice", "Carol", "Bob", "Alice"},
                {"Bob", "Alice", "Carol", "Bob"},
                {"Carol", "Bob", "Alice", "Carol"}
        };
        String[] agents = {"cursor", "copilot", "cursor", "copilot", "aider", "cursor"};
        String[] models = {"gpt-4.1", "gpt-4.1", "claude-3.7", "gpt-4o", "deepseek-v3", "gpt-4.1"};
        long[] repositories = {401, 402, 403, 404, 405, 406};
        for (int repositoryIndex = 0; repositoryIndex < repositories.length; repositoryIndex++) {
            for (int dayIndex = 0; dayIndex < 4; dayIndex++) {
                LocalDate date = LocalDate.of(2026, 8, 12 + dayIndex);
                long ai = dayIndex == 0 ? 6 : 0;
                long human = dayIndex == 1 ? 7 : 0;
                long mixed = dayIndex == 2 ? 6 : 0;
                long unknown = dayIndex == 0 ? 4 : dayIndex == 1 ? 3 : dayIndex == 3 ? 10 : 0;
                update("insert into daily_attribution_stats (repository_id, stat_date, ai_lines, human_lines, mixed_lines, unknown_lines, commit_count, synced_at) values (?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP)",
                        repositories[repositoryIndex], date, ai, human, mixed, unknown);
                update("insert into commit_attribution_stats (repository_id, commit_sha, commit_date, commit_author, commit_subject, ai_lines, human_lines, mixed_lines, unknown_lines, additions, deletions) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 10, 0)",
                        repositories[repositoryIndex], "fixture-" + repositories[repositoryIndex] + "-" + dayIndex, date,
                        authorMatrix[repositoryIndex][dayIndex], "fixture commit", ai, human, mixed, unknown);
                if (ai > 0 || mixed > 0) {
                    update("insert into agent_daily_stats (repository_id, stat_date, agent, model, ai_lines, session_count) values (?, ?, ?, ?, ?, 1)",
                            repositories[repositoryIndex], date, agents[repositoryIndex], models[repositoryIndex], ai + mixed);
                }
            }
        }
    }

    private void insertRepository(long id, long projectId, long groupId, String name) {
        update("insert into repositories (id, project_id, group_id, name, git_url, default_branch, last_sync_status, history_complete, history_offset) values (?, ?, ?, ?, 'file:///fixture', 'main', 'SUCCESS', true, 0)",
                id, projectId, groupId, name);
    }

    private void update(String sql, Object... values) {
        jdbc.getJdbcTemplate().update(sql, values);
    }
}
