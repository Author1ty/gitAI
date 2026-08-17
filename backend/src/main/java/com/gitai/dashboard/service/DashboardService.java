package com.gitai.dashboard.service;

import com.gitai.dashboard.api.DashboardDtos;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class DashboardService {
    private final NamedParameterJdbcTemplate jdbc;

    public DashboardService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DashboardDtos.FilterOptions filters() { return filters(null); }

    /** Department administrators receive only their own organization branch. */
    public DashboardDtos.FilterOptions filters(Long departmentScope) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("departmentScope", departmentScope);
        String departmentWhere = departmentScope == null ? "" : " where id = :departmentScope";
        String scopedJoin = departmentScope == null ? "" : " where p.department_id = :departmentScope";
        var departments = jdbc.query("select id, name from departments" + departmentWhere + " order by name", params,
                (rs, row) -> new DashboardDtos.Option(rs.getLong("id"), rs.getString("name"), null, "department"));
        var projects = jdbc.query("select p.id, p.name, p.department_id from projects p" + scopedJoin + " order by p.name", params,
                (rs, row) -> new DashboardDtos.Option(rs.getLong("id"), rs.getString("name"), rs.getLong("department_id"), "project"));
        var groups = jdbc.query("select g.id, g.name, g.project_id from repository_groups g join projects p on p.id = g.project_id" + scopedJoin + " order by g.name", params,
                (rs, row) -> new DashboardDtos.Option(rs.getLong("id"), rs.getString("name"), rs.getLong("project_id"), "group"));
        var repositories = jdbc.query("select r.id, r.name, r.project_id from repositories r join projects p on p.id = r.project_id" + scopedJoin + " order by r.name", params,
                (rs, row) -> new DashboardDtos.Option(rs.getLong("id"), rs.getString("name"), rs.getLong("project_id"), "repository"));
        return new DashboardDtos.FilterOptions(departments, projects, groups, repositories);
    }

    public DashboardDtos.DashboardResponse dashboard(Long departmentId, Long projectId, Long repositoryId, Long groupId, LocalDate from, LocalDate to) {
        // Imported repositories can have years of history. With no explicit picker range, expose the whole imported history
        // instead of silently hiding older commits behind a rolling 30-day window.
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("End date must not precede start date");
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("departmentId", departmentId)
                .addValue("projectId", projectId)
                .addValue("repositoryId", repositoryId)
                .addValue("groupId", groupId)
                .addValue("from", from)
                .addValue("to", to);
        String where = filtersSql();

        String summarySql = """
                select coalesce(sum(s.ai_lines), 0) ai_lines, coalesce(sum(s.human_lines), 0) human_lines,
                       coalesce(sum(s.mixed_lines), 0) mixed_lines, coalesce(sum(s.unknown_lines), 0) unknown_lines,
                       coalesce(sum(s.commit_count), 0) commits, count(distinct s.repository_id) repositories
                from daily_attribution_stats s
                join repositories r on r.id = s.repository_id
                join projects p on p.id = r.project_id
                where %s
                """.formatted(where);
        DashboardDtos.Summary summary = jdbc.queryForObject(summarySql, params, (rs, row) -> new DashboardDtos.Summary(
                rs.getLong("ai_lines"), rs.getLong("human_lines"), rs.getLong("mixed_lines"),
                rs.getLong("unknown_lines"), rs.getLong("commits"), rs.getLong("repositories")));

        String trendSql = """
                select s.stat_date, coalesce(sum(s.ai_lines), 0) ai_lines, coalesce(sum(s.human_lines), 0) human_lines,
                       coalesce(sum(s.mixed_lines), 0) mixed_lines, coalesce(sum(s.unknown_lines), 0) unknown_lines
                from daily_attribution_stats s
                join repositories r on r.id = s.repository_id
                join projects p on p.id = r.project_id
                where %s
                group by s.stat_date order by s.stat_date
                """.formatted(where);
        var trend = jdbc.query(trendSql, params, (rs, row) -> new DashboardDtos.TrendPoint(
                rs.getObject("stat_date", LocalDate.class), rs.getLong("ai_lines"), rs.getLong("human_lines"),
                rs.getLong("mixed_lines"), rs.getLong("unknown_lines")));

        String departmentSql = distributionSql("d.id", "d.name");
        var departments = jdbc.query(departmentSql, params, (rs, row) -> distribution(rs));
        String projectSql = distributionSql("p.id", "p.name");
        var projects = jdbc.query(projectSql, params, (rs, row) -> distribution(rs));

        // Keep configured repositories visible even before their first successful sync.  The dashboard
        // summary still measures imported attribution rows, while this list is also the operational source
        // of truth for remote repositories that are waiting for their initial mirror/sync.
        String repositorySql = """
                select r.id, r.name, p.name project_name, coalesce(g.name, 'Ungrouped') group_name,
                       coalesce(sum(s.ai_lines), 0) ai_lines, coalesce(sum(s.human_lines), 0) human_lines,
                       coalesce(sum(s.mixed_lines), 0) mixed_lines, coalesce(sum(s.unknown_lines), 0) unknown_lines,
                       max(s.synced_at) synced_at, r.last_sync_status, r.history_complete, r.history_offset, r.last_sync_error
                from repositories r
                join projects p on p.id = r.project_id
                left join repository_groups g on g.id = r.group_id
                left join daily_attribution_stats s on s.repository_id = r.id
                    and (:from is null or s.stat_date >= :from)
                    and (:to is null or s.stat_date <= :to)
                where (:departmentId is null or p.department_id = :departmentId)
                  and (:projectId is null or p.id = :projectId)
                  and (:repositoryId is null or r.id = :repositoryId)
                  and (:groupId is null or r.group_id = :groupId)
                group by r.id, r.name, p.name, g.name, r.last_sync_status, r.history_complete, r.history_offset, r.last_sync_error
                order by ai_lines desc, r.name
                """;
        var repositories = jdbc.query(repositorySql, params, (rs, row) -> new DashboardDtos.RepositoryMetric(
                rs.getLong("id"), rs.getString("name"), rs.getString("project_name"), rs.getString("group_name"),
                rs.getLong("ai_lines"), rs.getLong("human_lines"), rs.getLong("mixed_lines"), rs.getLong("unknown_lines"),
                Optional.ofNullable(rs.getTimestamp("synced_at")).map(Object::toString).orElse(null), rs.getString("last_sync_status"),
                rs.getBoolean("history_complete"), rs.getLong("history_offset"), rs.getString("last_sync_error")));

        String agentSql = """
                select a.agent, a.model, coalesce(sum(a.ai_lines), 0) ai_lines, coalesce(sum(a.session_count), 0) sessions
                from agent_daily_stats a
                join repositories r on r.id = a.repository_id
                join projects p on p.id = r.project_id
                where (:from is null or a.stat_date >= :from)
                  and (:to is null or a.stat_date <= :to)
                  and (:departmentId is null or p.department_id = :departmentId)
                  and (:projectId is null or p.id = :projectId)
                  and (:repositoryId is null or r.id = :repositoryId)
                  and (:groupId is null or r.group_id = :groupId)
                group by a.agent, a.model order by ai_lines desc
                """;
        var agents = jdbc.query(agentSql, params, (rs, row) -> new DashboardDtos.AgentMetric(
                rs.getString("agent"), rs.getString("model"), rs.getLong("ai_lines"), rs.getLong("sessions")));

        var projectPanorama = jdbc.query(scopeSql("p.id", "p.name", "d.name", "p.id, p.name, d.name"), params,
                (rs, row) -> scopeMetric(rs));
        var groupPanorama = jdbc.query(groupScopeSql(), params, (rs, row) -> scopeMetric(rs));
        var personRankings = personRankings(params);

        return new DashboardDtos.DashboardResponse(summary, trend, departments, projects, repositories, agents,
                projectPanorama, groupPanorama, personRankings, hierarchy(departmentId));
    }

    private String scopeSql(String id, String name, String parentName, String groupBy) {
        return """
                select cast(%s as varchar) id, %s name, %s parent_name,
                       count(distinct s.repository_id) repositories, coalesce(sum(s.commit_count), 0) commits,
                       coalesce(sum(s.ai_lines), 0) ai_lines, coalesce(sum(s.human_lines), 0) human_lines,
                       coalesce(sum(s.mixed_lines), 0) mixed_lines, coalesce(sum(s.unknown_lines), 0) unknown_lines
                from daily_attribution_stats s
                join repositories r on r.id = s.repository_id
                join projects p on p.id = r.project_id
                join departments d on d.id = p.department_id
                where %s
                group by %s
                order by ai_lines desc, name
                """.formatted(id, name, parentName, filtersSql(), groupBy);
    }

    private String groupScopeSql() {
        return """
                select cast(coalesce(g.id, 0) as varchar) id, coalesce(g.name, '\u672a\u5206\u7ec4') name, p.name parent_name,
                       count(distinct s.repository_id) repositories, coalesce(sum(s.commit_count), 0) commits,
                       coalesce(sum(s.ai_lines), 0) ai_lines, coalesce(sum(s.human_lines), 0) human_lines,
                       coalesce(sum(s.mixed_lines), 0) mixed_lines, coalesce(sum(s.unknown_lines), 0) unknown_lines
                from daily_attribution_stats s
                join repositories r on r.id = s.repository_id
                join projects p on p.id = r.project_id
                left join repository_groups g on g.id = r.group_id
                where %s
                group by g.id, g.name, p.name
                order by ai_lines desc, name
                """.formatted(filtersSql());
    }

    private DashboardDtos.ScopeMetric scopeMetric(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DashboardDtos.ScopeMetric(rs.getString("id"), rs.getString("name"), rs.getString("parent_name"),
                rs.getLong("repositories"), rs.getLong("commits"), rs.getLong("ai_lines"), rs.getLong("human_lines"),
                rs.getLong("mixed_lines"), rs.getLong("unknown_lines"));
    }

    private DashboardDtos.PersonRankings personRankings(MapSqlParameterSource params) {
        String base = """
                select coalesce(nullif(trim(c.commit_author), ''), '\u672a\u77e5\u63d0\u4ea4\u8005') author,
                       count(distinct c.repository_id) repositories, count(*) commits,
                       coalesce(sum(c.ai_lines), 0) ai_lines, coalesce(sum(c.human_lines), 0) human_lines,
                       coalesce(sum(c.mixed_lines), 0) mixed_lines, coalesce(sum(c.unknown_lines), 0) unknown_lines
                from commit_attribution_stats c
                join repositories r on r.id = c.repository_id
                join projects p on p.id = r.project_id
                where %s
                group by coalesce(nullif(trim(c.commit_author), ''), '\u672a\u77e5\u63d0\u4ea4\u8005')
                """.formatted(commitFiltersSql());
        var byAiLines = jdbc.query(base + " order by ai_lines desc, author limit 50", params,
                (rs, row) -> personMetric(rs));
        var byAiRate = jdbc.query(base + """
                 order by case when sum(c.ai_lines + c.human_lines + c.mixed_lines + c.unknown_lines) = 0 then 0
                               else sum(c.ai_lines) * 1.0 / sum(c.ai_lines + c.human_lines + c.mixed_lines + c.unknown_lines)
                          end desc, ai_lines desc, author
                 limit 50
                """, params, (rs, row) -> personMetric(rs));
        return new DashboardDtos.PersonRankings(byAiLines, byAiRate);
    }

    private DashboardDtos.PersonMetric personMetric(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DashboardDtos.PersonMetric(rs.getString("author"), rs.getLong("repositories"), rs.getLong("commits"),
                rs.getLong("ai_lines"), rs.getLong("human_lines"), rs.getLong("mixed_lines"), rs.getLong("unknown_lines"));
    }

    private String commitFiltersSql() {
        return "(:from is null or c.commit_date >= :from) " +
                "and (:to is null or c.commit_date <= :to) " +
                "and (:departmentId is null or p.department_id = :departmentId) " +
                "and (:projectId is null or p.id = :projectId) " +
                "and (:repositoryId is null or r.id = :repositoryId) " +
                "and (:groupId is null or r.group_id = :groupId)";
    }

    private String filtersSql() {
        return "(:from is null or s.stat_date >= :from) " +
                "and (:to is null or s.stat_date <= :to) " +
                "and (:departmentId is null or p.department_id = :departmentId) " +
                "and (:projectId is null or p.id = :projectId) " +
                "and (:repositoryId is null or r.id = :repositoryId) " +
                "and (:groupId is null or r.group_id = :groupId)";
    }

    private String distributionSql(String id, String name) {
        return """
                select cast(%s as varchar) id, %s name, coalesce(sum(s.ai_lines), 0) ai_lines,
                       coalesce(sum(s.human_lines), 0) human_lines, coalesce(sum(s.mixed_lines), 0) mixed_lines,
                       coalesce(sum(s.unknown_lines), 0) unknown_lines
                from daily_attribution_stats s
                join repositories r on r.id = s.repository_id
                join projects p on p.id = r.project_id
                join departments d on d.id = p.department_id
                where %s
                group by %s, %s order by ai_lines desc
                """.formatted(id, name, filtersSql(), id, name);
    }
    private DashboardDtos.Distribution distribution(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DashboardDtos.Distribution(rs.getString("id"), rs.getString("name"), rs.getLong("ai_lines"),
                rs.getLong("human_lines"), rs.getLong("mixed_lines"), rs.getLong("unknown_lines"));
    }

    public List<DashboardDtos.GroupNode> hierarchy(Long departmentScope) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("departmentScope", departmentScope);
        String departmentWhere = departmentScope == null ? "" : " where id = :departmentScope";
        String scopedJoin = departmentScope == null ? "" : " where p.department_id = :departmentScope";
        var departments = jdbc.query("select id, name from departments" + departmentWhere + " order by name", params,
                (rs, row) -> new DashboardDtos.Option(rs.getLong("id"), rs.getString("name"), null, "department"));
        var projects = jdbc.query("select p.id, p.name, p.department_id from projects p" + scopedJoin + " order by p.name", params,
                (rs, row) -> new DashboardDtos.Option(rs.getLong("id"), rs.getString("name"), rs.getLong("department_id"), "project"));
        var groups = jdbc.query("select g.id, g.name, g.project_id from repository_groups g join projects p on p.id = g.project_id" + scopedJoin + " order by g.name", params,
                (rs, row) -> new DashboardDtos.Option(rs.getLong("id"), rs.getString("name"), rs.getLong("project_id"), "group"));
        var repos = jdbc.query("select r.id, r.name, r.project_id, r.group_id from repositories r join projects p on p.id = r.project_id" + scopedJoin + " order by r.name", params,
                (rs, row) -> new DashboardDtos.Option(rs.getLong("id"), rs.getString("name"), rs.wasNull() ? null : rs.getLong("group_id"), "repository"));
        List<DashboardDtos.GroupNode> result = new ArrayList<>();
        for (var d : departments) {
            List<DashboardDtos.GroupNode> projectNodes = new ArrayList<>();
            for (var p : projects.stream().filter(x -> Objects.equals(x.parentId(), d.id())).toList()) {
                List<DashboardDtos.GroupNode> childNodes = new ArrayList<>();
                for (var g : groups.stream().filter(x -> Objects.equals(x.parentId(), p.id())).toList()) {
                    List<DashboardDtos.GroupNode> repoNodes = repos.stream()
                            .filter(x -> Objects.equals(x.parentId(), g.id()))
                            .map(x -> new DashboardDtos.GroupNode(x.id(), x.name(), "repository", List.of())).toList();
                    childNodes.add(new DashboardDtos.GroupNode(g.id(), g.name(), "group", repoNodes));
                }
                repos.stream().filter(x -> x.parentId() == null && belongsToProject(x.id(), p.id()))
                        .forEach(x -> childNodes.add(new DashboardDtos.GroupNode(x.id(), x.name(), "repository", List.of())));
                projectNodes.add(new DashboardDtos.GroupNode(p.id(), p.name(), "project", childNodes));
            }
            result.add(new DashboardDtos.GroupNode(d.id(), d.name(), "department", projectNodes));
        }
        return result;
    }

    private boolean belongsToProject(Long repositoryId, Long projectId) {
        Integer count = jdbc.queryForObject("select count(*) from repositories where id = :repositoryId and project_id = :projectId",
                new MapSqlParameterSource().addValue("repositoryId", repositoryId).addValue("projectId", projectId), Integer.class);
        return count != null && count > 0;
    }
}


