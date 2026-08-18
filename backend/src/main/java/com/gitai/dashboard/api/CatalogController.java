package com.gitai.dashboard.api;

import com.gitai.dashboard.auth.AccessPolicy;
import com.gitai.dashboard.auth.AuditService;
import com.gitai.dashboard.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;

/** Catalog configuration endpoints. Write access is enforced on the server, not only by the UI. */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final AuditService audit;

    public CatalogController(JdbcTemplate jdbc, AccessPolicy access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    @PostMapping("/departments")
    public Created createDepartment(@Valid @RequestBody DepartmentRequest request) {
        access.requireSuperAdmin();
        long id = insert("insert into departments (name, description) values (?, ?)", request.name().trim(), blankToNull(request.description()));
        audit.record(access.currentUser(), "CATALOG_DEPARTMENT_CREATED", "department", id, request.name().trim());
        return new Created(id);
    }

    @PostMapping("/projects")
    public Created createProject(@Valid @RequestBody ProjectRequest request) {
        access.requireDepartment(request.departmentId());
        ensureExists("departments", request.departmentId(), "Department");
        long id = insert("insert into projects (department_id, name, description) values (?, ?, ?)", request.departmentId(),
                request.name().trim(), blankToNull(request.description()));
        audit.record(access.currentUser(), "CATALOG_PROJECT_CREATED", "project", id, request.name().trim());
        return new Created(id);
    }

    @PostMapping("/groups")
    public Created createGroup(@Valid @RequestBody GroupRequest request) {
        access.requireProject(request.projectId());
        long id = insert("insert into repository_groups (project_id, name) values (?, ?)", request.projectId(), request.name().trim());
        audit.record(access.currentUser(), "CATALOG_GROUP_CREATED", "repository_group", id, request.name().trim());
        return new Created(id);
    }

    @PostMapping("/repositories")
    public Created createRepository(@Valid @RequestBody RepositoryRequest request) {
        access.requireProject(request.projectId());
        if (request.groupId() != null) {
            access.requireGroup(request.groupId());
            Integer count = jdbc.queryForObject("select count(*) from repository_groups where id = ? and project_id = ?", Integer.class,
                    request.groupId(), request.projectId());
            if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Repository group does not belong to the project");
        }
        long id = insert("""
                insert into repositories (project_id, group_id, name, git_url, default_branch, mirror_path, sync_configured_at, sync_window_initialized)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, request.projectId(), request.groupId(), request.name().trim(), request.gitUrl().trim(),
                blankToNull(request.defaultBranch()) == null ? "main" : request.defaultBranch().trim(), blankToNull(request.mirrorPath()),
                LocalDateTime.now(), true);
        audit.record(access.currentUser(), "CATALOG_REPOSITORY_CREATED", "repository", id, request.name().trim());
        return new Created(id);
    }

    @DeleteMapping("/repositories/{repositoryId}")
    @Transactional
    public Deleted deleteRepository(@PathVariable long repositoryId) {
        access.requireRepository(repositoryId);

        var repository = jdbc.queryForMap("""
                select r.id, r.name, p.name project_name, d.name department_name, g.name group_name
                from repositories r
                join projects p on p.id = r.project_id
                join departments d on d.id = p.department_id
                left join repository_groups g on g.id = r.group_id
                where r.id = ? for update
                """, repositoryId);
        Integer activeJobs = jdbc.queryForObject(
                "select count(*) from sync_jobs where repository_id = ? and status in ('QUEUED', 'RUNNING')",
                Integer.class,
                repositoryId
        );
        if (activeJobs != null && activeJobs > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "\u8BE5\u6570\u636E\u6E90\u5B58\u5728\u6B63\u5728\u5904\u7406\u7684\u540C\u6B65\u4EFB\u52A1\uFF0C\u8BF7\u7B49\u5F85\u4EFB\u52A1\u5B8C\u6210\u6216\u53D6\u6D88\u540E\u518D\u5220\u9664"
            );
        }

        // Delete repository-scoped attribution, aggregation and job records before removing the data source.
        // Keep the Git mirror directory to avoid deleting a user-provided custom path.
        jdbc.update("delete from commit_agent_stats where repository_id = ?", repositoryId);
        jdbc.update("delete from commit_attribution_stats where repository_id = ?", repositoryId);
        jdbc.update("delete from agent_daily_stats where repository_id = ?", repositoryId);
        jdbc.update("delete from daily_attribution_stats where repository_id = ?", repositoryId);
        jdbc.update("delete from sync_jobs where repository_id = ?", repositoryId);
        jdbc.update("delete from repositories where id = ?", repositoryId);

        String repositoryName = String.valueOf(repository.get("name"));
        String scope = String.valueOf(repository.get("department_name")) + " / "
                + String.valueOf(repository.get("project_name")) + " / "
                + (repository.get("group_name") == null ? "\u672a\u5206\u7ec4" : repository.get("group_name"));
        audit.record(
                access.currentUser(),
                "CATALOG_REPOSITORY_DELETED",
                "repository",
                repositoryId,
                repositoryName + " (" + scope + ")"
        );
        return new Deleted(repositoryId);
    }
    @DeleteMapping("/groups/{groupId}")
    @Transactional
    public Deleted deleteGroup(@PathVariable long groupId) {
        access.requireGroup(groupId);
        String groupName = nameForUpdate("repository_groups", groupId, "Repository group");
        int repositoryCount = count("select count(*) from repositories where group_id = ?", groupId);
        if (repositoryCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "\u8BE5\u4ED3\u5E93\u5206\u7EC4\u4E0B\u8FD8\u6709 " + repositoryCount + " \u4E2A\u6570\u636E\u6E90\uFF0C\u8BF7\u5148\u5220\u9664\u6216\u8C03\u6574\u4E0B\u7EA7\u6570\u636E\u6E90");
        }
        jdbc.update("delete from repository_groups where id = ?", groupId);
        audit.record(access.currentUser(), "CATALOG_GROUP_DELETED", "repository_group", groupId, groupName);
        return new Deleted(groupId);
    }

    @DeleteMapping("/projects/{projectId}")
    @Transactional
    public Deleted deleteProject(@PathVariable long projectId) {
        access.requireProject(projectId);
        String projectName = nameForUpdate("projects", projectId, "Project");
        int groupCount = count("select count(*) from repository_groups where project_id = ?", projectId);
        int repositoryCount = count("select count(*) from repositories where project_id = ?", projectId);
        if (groupCount > 0 || repositoryCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "\u8BE5\u9879\u76EE\u4E0B\u8FD8\u6709 " + groupCount + " \u4E2A\u4ED3\u5E93\u5206\u7EC4\u548C " + repositoryCount
                            + " \u4E2A\u6570\u636E\u6E90\uFF0C\u8BF7\u5148\u5220\u9664\u4E0B\u7EA7\u6570\u636E");
        }
        jdbc.update("delete from projects where id = ?", projectId);
        audit.record(access.currentUser(), "CATALOG_PROJECT_DELETED", "project", projectId, projectName);
        return new Deleted(projectId);
    }

    @DeleteMapping("/departments/{departmentId}")
    @Transactional
    public Deleted deleteDepartment(@PathVariable long departmentId) {
        // A department is an authorization boundary, so only the top-level administrator may remove it.
        access.requireSuperAdmin();
        String departmentName = nameForUpdate("departments", departmentId, "Department");
        int projectCount = count("select count(*) from projects where department_id = ?", departmentId);
        int userCount = count("select count(*) from app_users where department_id = ?", departmentId);
        if (projectCount > 0 || userCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "\u8BE5\u90E8\u95E8\u4E0B\u8FD8\u6709 " + projectCount + " \u4E2A\u9879\u76EE\u548C " + userCount
                            + " \u4E2A\u5DF2\u5206\u914D\u8D26\u53F7\uFF0C\u8BF7\u5148\u6E05\u7406\u4E0B\u7EA7\u7ED3\u6784\u548C\u90E8\u95E8\u8D26\u53F7\u6388\u6743");
        }
        jdbc.update("delete from departments where id = ?", departmentId);
        audit.record(access.currentUser(), "CATALOG_DEPARTMENT_DELETED", "department", departmentId, departmentName);
        return new Deleted(departmentId);
    }

    private String nameForUpdate(String table, long id, String label) {
        var names = jdbc.query("select name from " + table + " where id = ? for update", (rs, row) -> rs.getString(1), id);
        if (names.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, label + " does not exist");
        return names.get(0);
    }

    private int count(String sql, long id) {
        Integer result = jdbc.queryForObject(sql, Integer.class, id);
        return result == null ? 0 : result;
    }

    private void ensureExists(String table, long id, String label) {
        Integer count = jdbc.queryForObject("select count(*) from " + table + " where id = ?", Integer.class, id);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " does not exist");
    }

    private long insert(String sql, Object... arguments) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Create operation did not return an id");
        return key.longValue();
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record Created(long id) {}
    public record Deleted(long id) {}
    public record DepartmentRequest(@NotBlank String name, String description) {}
    public record ProjectRequest(@NotNull Long departmentId, @NotBlank String name, String description) {}
    public record GroupRequest(@NotNull Long projectId, @NotBlank String name) {}
    public record RepositoryRequest(@NotNull Long projectId, Long groupId, @NotBlank String name,
                                    @NotBlank String gitUrl, String defaultBranch, String mirrorPath) {}
}


