package com.gitai.dashboard.api;

import java.time.LocalDate;
import java.util.List;

public final class DashboardDtos {
    private DashboardDtos() {}

    public record Option(Long id, String name, Long parentId, String type) {}
    public record FilterOptions(List<Option> departments, List<Option> projects, List<Option> groups, List<Option> repositories) {}
    public record Summary(long aiLines, long humanLines, long mixedLines, long unknownLines, long commits, long repositories) {}
    public record TrendPoint(LocalDate date, long aiLines, long humanLines, long mixedLines, long unknownLines) {}
    public record Distribution(String id, String name, long aiLines, long humanLines, long mixedLines, long unknownLines) {}
    public record RepositoryMetric(Long repositoryId, String name, String projectName, String groupName, long aiLines, long humanLines, long mixedLines, long unknownLines, String syncedAt, String syncStatus, boolean historyComplete, long historyOffset, String syncError) {}
    public record AgentMetric(String agent, String model, long aiLines, long sessions) {}
    public record ScopeMetric(String id, String name, String parentName, long repositories, long commits, long aiLines, long humanLines, long mixedLines, long unknownLines) {}
    public record PersonMetric(String author, long repositories, long commits, long aiLines, long humanLines, long mixedLines, long unknownLines) {}
    public record PersonRankings(List<PersonMetric> byAiLines, List<PersonMetric> byAiRate) {}
    public record GroupNode(Long id, String name, String type, List<GroupNode> children) {}
    public record DashboardResponse(Summary summary, List<TrendPoint> trend, List<Distribution> departments, List<Distribution> projects, List<RepositoryMetric> repositories, List<AgentMetric> agents, List<ScopeMetric> projectPanorama, List<ScopeMetric> groupPanorama, PersonRankings personRankings, List<GroupNode> hierarchy) {}
}

