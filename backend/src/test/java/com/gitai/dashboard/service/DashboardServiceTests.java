package com.gitai.dashboard.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardServiceTests {
    private NamedParameterJdbcTemplate jdbc;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:dashboard_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        service = new DashboardService(jdbc);
        jdbc.getJdbcTemplate().update("""
                insert into daily_attribution_stats
                (repository_id, stat_date, ai_lines, human_lines, mixed_lines, unknown_lines, commit_count, synced_at)
                values (1, ?, 2, 3, 0, 4, 1, CURRENT_TIMESTAMP)
                """, LocalDate.of(2012, 3, 6));
        jdbc.getJdbcTemplate().update("""
                insert into daily_attribution_stats
                (repository_id, stat_date, ai_lines, human_lines, mixed_lines, unknown_lines, commit_count, synced_at)
                values (1, ?, 5, 0, 0, 0, 1, CURRENT_TIMESTAMP)
                """, LocalDate.of(2026, 8, 14));
        jdbc.getJdbcTemplate().update("""
                insert into agent_daily_stats (repository_id, stat_date, agent, model, ai_lines, session_count)
                values (1, ?, 'test-agent', 'test-model', 7, 2)
                """, LocalDate.of(2012, 3, 6));
        jdbc.getJdbcTemplate().update("""
                insert into commit_attribution_stats
                (repository_id, commit_sha, commit_date, commit_author, commit_subject, ai_lines, human_lines, mixed_lines, unknown_lines, additions, deletions)
                values (1, 'alice-2012', ?, 'Alice', 'first commit', 2, 3, 0, 4, 9, 0)
                """, LocalDate.of(2012, 3, 6));
        jdbc.getJdbcTemplate().update("""
                insert into commit_attribution_stats
                (repository_id, commit_sha, commit_date, commit_author, commit_subject, ai_lines, human_lines, mixed_lines, unknown_lines, additions, deletions)
                values (1, 'bob-2026', ?, 'Bob', 'second commit', 5, 0, 0, 0, 5, 0)
                """, LocalDate.of(2026, 8, 14));
    }

    @Test
    void defaultsToAllImportedHistoryWhenNoDateRangeIsProvided() {
        var dashboard = service.dashboard(null, null, null, null, null, null);

        assertEquals(7, dashboard.summary().aiLines());
        assertEquals(3, dashboard.summary().humanLines());
        assertEquals(4, dashboard.summary().unknownLines());
        assertEquals(2, dashboard.summary().commits());
        assertEquals(2, dashboard.trend().size());
        assertEquals(1, dashboard.agents().size());
        assertEquals(7, dashboard.agents().getFirst().aiLines());
        assertEquals(1, dashboard.projectPanorama().size());
        assertEquals(1, dashboard.groupPanorama().size());
        assertEquals("Bob", dashboard.personRankings().byAiLines().getFirst().author());
        assertEquals("Bob", dashboard.personRankings().byAiRate().getFirst().author());
    }

    @Test
    void appliesAnExplicitDateRangeToAllDashboardSections() {
        var dashboard = service.dashboard(null, null, null, null,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15));

        assertEquals(5, dashboard.summary().aiLines());
        assertEquals(0, dashboard.summary().humanLines());
        assertEquals(0, dashboard.summary().unknownLines());
        assertEquals(1, dashboard.trend().size());
        assertEquals(0, dashboard.agents().size());
        assertEquals(1, dashboard.projectPanorama().size());
        assertEquals(1, dashboard.groupPanorama().size());
        assertEquals(1, dashboard.personRankings().byAiLines().size());
        assertEquals("Bob", dashboard.personRankings().byAiLines().getFirst().author());
    }
}
