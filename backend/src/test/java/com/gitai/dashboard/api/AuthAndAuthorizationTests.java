package com.gitai.dashboard.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role-matrix regression tests. These cover server-side authorization so a caller cannot bypass the UI by calling APIs
 * directly. The test database is intentionally separate from the general input-validation test database.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_authorization;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
class AuthAndAuthorizationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void anonymousRequestsCannotReadProtectedDashboardData() throws Exception {
        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/hierarchy"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void viewerCanReadButCannotChangeCatalogSyncScheduleOrOperations() throws Exception {
        String viewer = token("viewer");

        mockMvc.perform(get("/api/dashboard").header("Authorization", viewer))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/sync-jobs").header("Authorization", viewer))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sync-jobs").header("Authorization", viewer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/catalog/repositories/1").header("Authorization", viewer))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/catalog/projects").header("Authorization", viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departmentId\":1,\"name\":\"blocked-project\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/sync-schedule").header("Authorization", viewer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"intervalMinutes\":30}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/operations/users").header("Authorization", viewer))
                .andExpect(status().isForbidden());
    }

    @Test
    void departmentAdminIsRestrictedToItsOwnDepartmentAndCannotAccessOperations() throws Exception {
        createOtherDepartmentRepository();
        String departmentAdmin = token("deptadmin");

        mockMvc.perform(get("/api/filters").header("Authorization", departmentAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments.length()").value(1))
                .andExpect(jsonPath("$.departments[0].id").value(1));
        mockMvc.perform(get("/api/hierarchy").header("Authorization", departmentAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1));
        mockMvc.perform(get("/api/dashboard").header("Authorization", departmentAdmin).param("repositoryId", "2"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/repositories/2/sync-jobs").header("Authorization", departmentAdmin))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/sync-schedule").header("Authorization", departmentAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"intervalMinutes\":30}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/catalog/departments").header("Authorization", departmentAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"blocked-department\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/operations/overview").header("Authorization", departmentAdmin))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdminCanCreateRepositoryGroupForProject() throws Exception {
        String superAdmin = token("superadmin");

        mockMvc.perform(post("/api/catalog/groups").header("Authorization", superAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":1,\"name\":\"authorization-regression-group\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber());
    }
    @Test
    void superAdminCanManageUsersAndInvalidatedTokensNoLongerWork() throws Exception {
        String superAdmin = token("superadmin");
        String username = "rbac-smoke-user";

        mockMvc.perform(post("/api/operations/users").header("Authorization", superAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"RBAC Smoke User\",\"role\":\"VIEWER\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("VIEWER"));
        mockMvc.perform(get("/api/operations/audit-logs").header("Authorization", superAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'USER_CREATED')]").isNotEmpty());

        String createdUserToken = token(username);
        mockMvc.perform(post("/api/auth/logout").header("Authorization", createdUserToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", createdUserToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyDepartmentAdministratorsMayCarryDepartmentScopeAndPasswordsAreNotPersisted() throws Exception {
        String superAdmin = token("superadmin");

        mockMvc.perform(post("/api/operations/users").header("Authorization", superAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bad-scope\",\"displayName\":\"Bad Scope\",\"role\":\"VIEWER\",\"departmentId\":1,\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/operations/users").header("Authorization", superAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"department-scope\",\"displayName\":\"Department Scope\",\"role\":\"DEPARTMENT_ADMIN\",\"departmentId\":1,\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentId").value(1));

        Integer passwordColumns = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where lower(table_name) = 'app_users' and lower(column_name) like '%password%'
                """, Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, passwordColumns);
    }

    @Test
    void superAdminCanDeleteRepositoryAndItsAttributionData() throws Exception {
        String superAdmin = token("superadmin");
        jdbc.update("insert into projects (id, department_id, name, description) values (90, 1, 'delete-project', 'delete regression')");
        jdbc.update("insert into repositories (id, project_id, group_id, name, git_url, default_branch) values (90, 90, null, 'delete-repository', 'file:///tmp/delete-repository.git', 'main')");
        jdbc.update("""
                insert into daily_attribution_stats (repository_id, stat_date, synced_at)
                values (90, '2026-08-17', CURRENT_TIMESTAMP)
                """);
        jdbc.update("""
                insert into agent_daily_stats (repository_id, stat_date, agent, model)
                values (90, '2026-08-17', 'test-agent', 'test-model')
                """);
        jdbc.update("""
                insert into commit_attribution_stats (repository_id, commit_sha, commit_date, commit_author, commit_subject)
                values (90, 'delete-commit-sha', '2026-08-17', 'delete-user', 'delete test')
                """);
        jdbc.update("""
                insert into commit_agent_stats (repository_id, commit_sha, agent, model)
                values (90, 'delete-commit-sha', 'test-agent', 'test-model')
                """);
        jdbc.update("insert into sync_jobs (repository_id, active_repository_id, status, message) values (90, null, 'SUCCESS', 'done')");

        mockMvc.perform(delete("/api/catalog/repositories/90").header("Authorization", superAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(90));

        org.junit.jupiter.api.Assertions.assertEquals(0, count("repositories", 90));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("daily_attribution_stats", 90));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("agent_daily_stats", 90));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("commit_attribution_stats", 90));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("commit_agent_stats", 90));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("sync_jobs", 90));
    }

    @Test
    void deletingRepositoryWithActiveSyncJobIsRejected() throws Exception {
        String superAdmin = token("superadmin");
        jdbc.update("insert into projects (id, department_id, name, description) values (91, 1, 'active-delete-project', 'delete regression')");
        jdbc.update("insert into repositories (id, project_id, group_id, name, git_url, default_branch) values (91, 91, null, 'active-delete-repository', 'file:///tmp/active-delete-repository.git', 'main')");
        jdbc.update("insert into sync_jobs (repository_id, active_repository_id, status, message) values (91, 91, 'RUNNING', 'running')");

        mockMvc.perform(delete("/api/catalog/repositories/91").header("Authorization", superAdmin))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(1, count("repositories", 91));
    }

    @Test
    void tokenExpiryUsesServerEpochTimeInsteadOfDatabaseCurrentTimestamp() throws Exception {
        String viewer = token("viewer");
        Long sessionId = jdbc.queryForObject("select max(id) from auth_sessions", Long.class);
        long serverNowEpochMs = System.currentTimeMillis();

        // A database timestamp in the past must not invalidate a session whose server-issued epoch is still valid.
        jdbc.update("update auth_sessions set expires_at = ?, expires_at_epoch_ms = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), serverNowEpochMs + 60_000, sessionId);
        mockMvc.perform(get("/api/dashboard").header("Authorization", viewer))
                .andExpect(status().isOk());

        // Conversely, a future database timestamp cannot keep a server-expired token alive.
        jdbc.update("update auth_sessions set expires_at = ?, expires_at_epoch_ms = ? where id = ?",
                Timestamp.from(Instant.now().plusSeconds(3600)), serverNowEpochMs - 1, sessionId);
        mockMvc.perform(get("/api/dashboard").header("Authorization", viewer))
                .andExpect(status().isForbidden());
    }
    private int count(String table, long repositoryId) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + (table.equals("repositories") ? "id" : "repository_id") + " = ?", Integer.class, repositoryId);
    }

    private void createOtherDepartmentRepository() {
        jdbc.update("insert into departments (id, name, description) values (2, 'Other department', 'Authorization test scope')");
        jdbc.update("insert into projects (id, department_id, name, description) values (2, 2, 'Other project', 'Authorization test scope')");
        jdbc.update("""
                insert into repositories (id, project_id, group_id, name, git_url, default_branch)
                values (2, 2, null, 'other-repository', 'file:///tmp/other-repository.git', 'main')
                """);
    }

    private String token(String username) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"third-party-placeholder\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String marker = "\"token\":\"";
        int start = body.indexOf(marker) + marker.length();
        int end = body.indexOf('\"', start);
        return "Bearer " + body.substring(start, end);
    }
}

