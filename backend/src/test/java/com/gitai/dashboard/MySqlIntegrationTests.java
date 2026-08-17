package com.gitai.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs only when a dedicated MySQL test database is supplied. It validates the real MySQL driver,
 * Flyway migration set and production schema; it must never target a production database.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class MySqlIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.profiles.active", () -> "mysql");
        registry.add("spring.datasource.url", () -> requiredEnvironment("MYSQL_TEST_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironment("MYSQL_TEST_USER"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("MYSQL_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void flywayBuildsTheExpectedProductionSchemaWithoutDevelopmentRepositories() {
        Integer tables = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = database()
                  and table_name in ('departments', 'repositories', 'commit_attribution_stats', 'sync_jobs', 'app_users')
                """, Integer.class);
        Integer developmentPaths = jdbc.queryForObject("""
                select count(*) from repositories
                where git_url like '%test-repos%' or git_url like 'D:/code/%'
                """, Integer.class);
        String rootDepartment = jdbc.queryForObject("select name from departments where id = 1", String.class);

        assertEquals(5, tables);
        assertEquals(0, developmentPaths);
        assertEquals("研发效能部", rootDepartment);
        assertTrue(jdbc.queryForObject("select count(*) from app_users", Integer.class) >= 3);
    }

    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " must be set for MySQL integration tests");
        return value;
    }
}
