package com.gitai.dashboard.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitai.dashboard.logging.LogSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Imports Git AI attribution in bounded batches.  A repository's first historical import is deliberately
 * paged so a multi-year repository cannot turn one dashboard click into an unbounded Git/CPU/DB operation.
 */
@Service
public class GitAiSyncService {
    private static final String NOTES_REF = "ai";
    private static final Logger log = LoggerFactory.getLogger(GitAiSyncService.class);

    private final JdbcTemplate jdbc;
    private final GitCommandService git;
    private final GitAiProperties properties;
    private final GitAiNoteParser noteParser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GitAiSyncService(JdbcTemplate jdbc, GitCommandService git, GitAiProperties properties,
                            GitAiNoteParser noteParser) {
        this.jdbc = jdbc;
        this.git = git;
        this.properties = properties;
        this.noteParser = noteParser;
    }

    public List<SyncResult> syncAll() {
        List<Long> repositoryIds = jdbc.queryForList("select id from repositories order by id", Long.class);
        log.info("repository synchronization started repositoryCount={}", repositoryIds.size());
        long started = System.nanoTime();
        List<SyncResult> results = new ArrayList<>();
        for (Long repositoryId : repositoryIds) results.add(syncRepository(repositoryId));
        long succeeded = results.stream().filter(result -> "SUCCESS".equals(result.status())).count();
        log.info("repository synchronization completed repositoryCount={} succeeded={} failed={} durationMs={}",
                results.size(), succeeded, results.size() - succeeded, elapsedMs(started));
        return results;
    }

    public SyncResult syncRepository(long repositoryId) {
        return syncRepository(repositoryId, (stage, processedCommits, batchCommitCount) -> { });
    }

    /**
     * Executes one bounded repository batch and emits only coarse checkpoints.  The callback deliberately avoids
     * per-line and per-command updates: for a large repository the database must remain an observer, not a bottleneck.
     */
    public SyncResult syncRepository(long repositoryId, SyncProgressListener progress) {
        RepositoryTarget target = findRepository(repositoryId);
        long started = System.nanoTime();
        log.info("repository sync started repositoryId={} repository={} remote={} branch={} historyOffset={} historyComplete={}",
                target.id(), target.name(), LogSupport.safeRemote(target.gitUrl()), target.defaultBranch(),
                target.historyOffset(), target.historyComplete());
        jdbc.update("update repositories set last_sync_status = 'SYNCING', last_sync_error = null where id = ?", repositoryId);
        try {
            progress.onProgress(SyncStage.PREPARING_MIRROR, 0, 0);
            Path mirrorPath = resolveMirrorPath(target);
            ensureMirror(target, mirrorPath);
            log.info("repository mirror ready repositoryId={} mirrorPath={}", target.id(), mirrorPath);

            progress.onProgress(SyncStage.READING_COMMITS, 0, 0);
            String currentHead = git.runGit(mirrorPath, "rev-parse", "--verify", target.defaultBranch()).trim();
            SyncRange range = selectRange(target, mirrorPath, currentHead);
            log.info("repository sync range selected repositoryId={} currentHead={} baseSha={} sinceSha={} offset={}",
                    target.id(), abbreviateSha(currentHead), abbreviateSha(range.baseSha()), abbreviateSha(range.sinceSha()), range.offset());
            List<CommitInfo> commits = readCommits(mirrorPath, range);

            int batchCommitCount = commits.size();
            log.info("repository commit batch loaded repositoryId={} commitCount={} maxBatch={} offset={}",
                    target.id(), batchCommitCount, properties.getMaxCommitsPerSync(), range.offset());
            progress.onProgress(SyncStage.READING_ATTRIBUTION, 0, batchCommitCount);
            List<CommitAttribution> attributions = new ArrayList<>(batchCommitCount);
            for (int index = 0; index < batchCommitCount; index++) {
                attributions.add(readAttribution(mirrorPath, target.id(), commits.get(index)));
                int processed = index + 1;
                if (processed == batchCommitCount || processed % 25 == 0) {
                    progress.onProgress(SyncStage.READING_ATTRIBUTION, processed, batchCommitCount);
                }
            }

            progress.onProgress(SyncStage.WRITING_STATS, batchCommitCount, batchCommitCount);
            upsertRepositoryStats(target.id(), attributions);
            boolean historyComplete = batchCommitCount < properties.getMaxCommitsPerSync();
            long historyOffset = range.offset() + batchCommitCount;

            progress.onProgress(SyncStage.FINALIZING, batchCommitCount, batchCommitCount);
            persistRangeProgress(target.id(), range, historyOffset, historyComplete);
            jdbc.update("update repositories set last_synced_at = CURRENT_TIMESTAMP, last_sync_status = 'SUCCESS', last_sync_error = null where id = ?", repositoryId);
            long aiLines = attributions.stream().mapToLong(CommitAttribution::aiLines).sum();
            long humanLines = attributions.stream().mapToLong(CommitAttribution::humanLines).sum();
            long unknownLines = attributions.stream().mapToLong(CommitAttribution::unknownLines).sum();
            log.info("repository sync completed repositoryId={} repository={} status=SUCCESS commits={} aiLines={} humanLines={} unknownLines={} historyComplete={} nextOffset={} durationMs={}",
                    target.id(), target.name(), attributions.size(), aiLines, humanLines, unknownLines, historyComplete,
                    historyOffset, elapsedMs(started));
            return new SyncResult(target.id(), target.name(), "SUCCESS", attributions.size(), null, historyComplete, historyOffset);
        } catch (Exception exception) {
            String message = abbreviate(rootMessage(exception));
            jdbc.update("update repositories set last_sync_status = 'FAILED', last_sync_error = ? where id = ?", message, repositoryId);
            log.error("repository sync failed repositoryId={} repository={} status=FAILED durationMs={} errorType={} error={}",
                    target.id(), target.name(), elapsedMs(started), exception.getClass().getSimpleName(),
                    LogSupport.safeExceptionMessage(exception), exception);
            return new SyncResult(target.id(), target.name(), "FAILED", 0, message, target.historyComplete(), target.historyOffset());
        }
    }

    private RepositoryTarget findRepository(long repositoryId) {
        List<RepositoryTarget> targets = jdbc.query("""
                select id, name, git_url, default_branch, mirror_path, history_base_sha, history_since_sha,
                       history_offset, history_complete, synced_head_sha
                from repositories where id = ?
                """, (rs, row) -> new RepositoryTarget(rs.getLong("id"), rs.getString("name"),
                rs.getString("git_url"), rs.getString("default_branch"), rs.getString("mirror_path"),
                rs.getString("history_base_sha"), rs.getString("history_since_sha"), rs.getLong("history_offset"),
                rs.getBoolean("history_complete"), rs.getString("synced_head_sha")), repositoryId);
        if (targets.isEmpty()) throw new IllegalArgumentException("Repository was not found: " + repositoryId);
        return targets.getFirst();
    }

    private Path resolveMirrorPath(RepositoryTarget target) {
        if (target.mirrorPath() != null && !target.mirrorPath().isBlank()) {
            return Path.of(target.mirrorPath()).toAbsolutePath().normalize();
        }
        String safeName = target.name().replaceAll("[^A-Za-z0-9._-]", "-");
        return Path.of(properties.getMirrorRoot()).resolve(target.id() + "-" + safeName + ".git").toAbsolutePath().normalize();
    }

    private void ensureMirror(RepositoryTarget target, Path mirrorPath) {
        if (Files.isDirectory(mirrorPath) && Files.exists(mirrorPath.resolve("HEAD"))) {
            log.info("git mirror fetch started repositoryId={} repository={} mirrorPath={}", target.id(), target.name(), mirrorPath);
            long started = System.nanoTime();
            git.runGit(mirrorPath, "fetch", "--prune", "origin", "+refs/heads/*:refs/heads/*", "+refs/notes/*:refs/notes/*");
            log.info("git mirror fetch completed repositoryId={} durationMs={}", target.id(), elapsedMs(started));
            return;
        }
        if (target.gitUrl() == null || target.gitUrl().isBlank()) {
            throw new IllegalArgumentException("Repository has no Git URL and no usable local mirror.");
        }
        try {
            Files.createDirectories(mirrorPath.getParent());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create mirror directory: " + mirrorPath.getParent(), exception);
        }
        // Clone into an owned staging directory. A network timeout never leaves a half-cloned directory that blocks retries.
        if (Files.exists(mirrorPath)) {
            if (isManagedMirrorPath(mirrorPath)) deleteRecursively(mirrorPath);
            else throw new IllegalStateException("The configured mirror path exists but is not a valid Git mirror: " + mirrorPath);
        }
        Path staging = mirrorPath.resolveSibling(mirrorPath.getFileName() + ".partial-" + java.util.UUID.randomUUID());
        log.info("git mirror clone started repositoryId={} repository={} remote={} stagingPath={}", target.id(), target.name(),
                LogSupport.safeRemote(target.gitUrl()), staging);
        long started = System.nanoTime();
        try {
            git.cloneMirror(target.gitUrl(), staging);
            Files.move(staging, mirrorPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            try {
                Files.move(staging, mirrorPath);
            } catch (Exception moveException) {
                deleteRecursively(staging);
                throw new IllegalStateException("Unable to finalize the Git mirror: " + mirrorPath, moveException);
            }
        } catch (Exception exception) {
            deleteRecursively(staging);
            log.error("git mirror clone failed repositoryId={} repository={} durationMs={} error={}", target.id(), target.name(),
                    elapsedMs(started), LogSupport.safeExceptionMessage(exception), exception);
            if (exception instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("Unable to clone Git mirror: " + mirrorPath, exception);
        }
        log.info("git mirror clone completed repositoryId={} repository={} durationMs={}", target.id(), target.name(), elapsedMs(started));
    }

    private boolean isManagedMirrorPath(Path mirrorPath) {
        return mirrorPath.startsWith(Path.of(properties.getMirrorRoot()).toAbsolutePath().normalize());
    }

    private void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (Exception exception) { throw new IllegalStateException("Unable to remove incomplete mirror: " + directory, exception); }
            });
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Unable to remove incomplete mirror: " + directory, exception);
        }
    }
    private SyncRange selectRange(RepositoryTarget target, Path mirrorPath, String currentHead) {
        if (!target.historyComplete()) {
            return new SyncRange(blankToNull(target.historyBaseSha()) == null ? currentHead : target.historyBaseSha(),
                    blankToNull(target.historySinceSha()), target.historyOffset());
        }
        if (blankToNull(target.syncedHeadSha()) == null || target.syncedHeadSha().equals(currentHead)) {
            if (target.syncedHeadSha() != null && target.syncedHeadSha().equals(currentHead)) {
                return new SyncRange(currentHead, currentHead, 0); // an empty, bounded no-op range
            }
            return new SyncRange(currentHead, null, 0);
        }

        GitCommandService.CommandResult ancestor = git.runGitAllowFailure(mirrorPath, "merge-base", "--is-ancestor", target.syncedHeadSha(), currentHead);
        if (ancestor.exitCode() == 0) return new SyncRange(currentHead, target.syncedHeadSha(), 0);

        // A force-push invalidates branch-based aggregates. Reset only this repository, then rebuild it in bounded batches.
        clearRepositoryStats(target.id());
        return new SyncRange(currentHead, null, 0);
    }

    private List<CommitInfo> readCommits(Path mirrorPath, SyncRange range) {
        if (range.sinceSha() != null && range.sinceSha().equals(range.baseSha())) return List.of();
        List<String> args = new ArrayList<>(List.of("log", "--format=%H%x1f%cI%x1f%an%x1f%s",
                "--max-count=" + properties.getMaxCommitsPerSync(), "--skip=" + range.offset()));
        args.add(range.sinceSha() == null ? range.baseSha() : range.sinceSha() + ".." + range.baseSha());
        String output = git.runGit(mirrorPath, args.toArray(String[]::new));
        List<CommitInfo> commits = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\u001f", 4);
            if (parts.length < 4) throw new IllegalStateException("Unable to parse Git commit metadata: " + line);
            commits.add(new CommitInfo(parts[0], commitDate(parts[1]), parts[2], parts[3]));
        }
        return commits;
    }

    private LocalDate commitDate(String value) {
        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("Unable to parse commit timestamp: " + value, exception);
        }
    }

    private CommitAttribution readAttribution(Path mirrorPath, long repositoryId, CommitInfo commit) {
        GitCommandService.CommandResult noteLookup = git.runGitAllowFailure(mirrorPath, "notes", "--ref=" + NOTES_REF, "list", commit.sha());
        if (noteLookup.exitCode() != 0) return readNativeDiffAttribution(mirrorPath, repositoryId, commit);

        String noteObjectSha = noteLookup.output().trim().split("\\s+", 2)[0];
        String rawNote = git.runGit(mirrorPath, "notes", "--ref=" + NOTES_REF, "show", commit.sha());
        GitAiStats stats = parseStats(git.runGit(mirrorPath, "ai", "stats", commit.sha(), "--json"));
        GitAiNoteParser.ParsedNote parsedNote = noteParser.parse(rawNote);
        long mixedLines = Math.min(parsedNote.mixedLines(), Math.min(stats.aiLines(), stats.humanLines()));
        long aiOnlyLines = Math.max(0, stats.aiLines() - mixedLines);
        long humanOnlyLines = Math.max(0, stats.humanLines() - mixedLines);
        return new CommitAttribution(repositoryId, commit, noteObjectSha, aiOnlyLines, humanOnlyLines, mixedLines,
                stats.unknownLines(), stats.additions(), stats.deletions(), mergeAgentStats(stats, parsedNote));
    }

    /** A normal Git repository has no Git AI Note. Its additions remain explicitly unknown, never inferred as human. */
    private CommitAttribution readNativeDiffAttribution(Path mirrorPath, long repositoryId, CommitInfo commit) {
        long additions = 0;
        long deletions = 0;
        String output = git.runGit(mirrorPath, "show", "--format=", "--numstat", commit.sha());
        for (String line : output.split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\t", 3);
            if (parts.length < 2) continue;
            additions += parseNumstat(parts[0]);
            deletions += parseNumstat(parts[1]);
        }
        return new CommitAttribution(repositoryId, commit, null, 0, 0, 0, additions, additions, deletions, Map.of());
    }

    private long parseNumstat(String value) {
        if (value == null || value.equals("-")) return 0;
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private GitAiStats parseStats(String output) {
        try {
            JsonNode root = objectMapper.readTree(output);
            Map<GitAiNoteParser.AgentIdentity, ToolStat> toolStats = new LinkedHashMap<>();
            JsonNode breakdown = root.path("tool_model_breakdown");
            if (breakdown.isObject()) {
                breakdown.fields().forEachRemaining(entry -> {
                    String[] identity = splitIdentity(entry.getKey());
                    JsonNode values = entry.getValue();
                    toolStats.put(new GitAiNoteParser.AgentIdentity(identity[0], identity[1]),
                            new ToolStat(values.path("ai_additions").asLong(0), values.path("ai_accepted").asLong(0)));
                });
            }
            return new GitAiStats(root.path("ai_additions").asLong(0), root.path("human_additions").asLong(0),
                    root.path("unknown_additions").asLong(0), root.path("git_diff_added_lines").asLong(0),
                    root.path("git_diff_deleted_lines").asLong(0), toolStats);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to parse git ai stats JSON output: " + output, exception);
        }
    }

    private String[] splitIdentity(String value) {
        int separator = value.lastIndexOf("::");
        if (separator < 0) return new String[]{value.isBlank() ? "unknown" : value, "unknown"};
        String tool = value.substring(0, separator).trim();
        String model = value.substring(separator + 2).trim();
        return new String[]{tool.isBlank() ? "unknown" : tool, model.isBlank() ? "unknown" : model};
    }

    private Map<GitAiNoteParser.AgentIdentity, AgentStat> mergeAgentStats(GitAiStats stats, GitAiNoteParser.ParsedNote parsedNote) {
        Map<GitAiNoteParser.AgentIdentity, AgentStat> result = new LinkedHashMap<>();
        stats.toolStats().forEach((identity, value) -> result.put(identity, new AgentStat(value.aiLines(), value.acceptedLines(), 0)));
        parsedNote.sessionCounts().forEach((identity, sessions) -> {
            AgentStat existing = result.getOrDefault(identity, new AgentStat(0, 0, 0));
            result.put(identity, new AgentStat(existing.aiLines(), existing.acceptedLines(), sessions));
        });
        return result;
    }

    private void upsertRepositoryStats(long repositoryId, List<CommitAttribution> rows) {
        if (rows.isEmpty()) return;
        for (CommitAttribution row : rows) {
            jdbc.update("delete from commit_agent_stats where repository_id = ? and commit_sha = ?", repositoryId, row.commit().sha());
            jdbc.update("""
                    insert into commit_attribution_stats (repository_id, commit_sha, commit_date, commit_author, commit_subject,
                    note_object_sha, source_note_ref, ai_lines, human_lines, mixed_lines, unknown_lines, additions, deletions)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on duplicate key update commit_date = values(commit_date), commit_author = values(commit_author),
                    commit_subject = values(commit_subject), note_object_sha = values(note_object_sha), source_note_ref = values(source_note_ref),
                    ai_lines = values(ai_lines), human_lines = values(human_lines), mixed_lines = values(mixed_lines),
                    unknown_lines = values(unknown_lines), additions = values(additions), deletions = values(deletions)
                    """, row.repositoryId(), row.commit().sha(), row.commit().date(), row.commit().author(), row.commit().subject(),
                    row.noteObjectSha(), row.noteObjectSha() == null ? null : "refs/notes/ai", row.aiLines(), row.humanLines(),
                    row.mixedLines(), row.unknownLines(), row.additions(), row.deletions());
            for (Map.Entry<GitAiNoteParser.AgentIdentity, AgentStat> entry : row.agents().entrySet()) {
                AgentStat stat = entry.getValue();
                jdbc.update("""
                        insert into commit_agent_stats (repository_id, commit_sha, agent, model, ai_lines, accepted_lines, session_count)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on duplicate key update ai_lines = values(ai_lines), accepted_lines = values(accepted_lines), session_count = values(session_count)
                        """, row.repositoryId(), row.commit().sha(), entry.getKey().tool(), entry.getKey().model(),
                        stat.aiLines(), stat.acceptedLines(), stat.sessionCount());
            }
        }
        rebuildAffectedAggregates(repositoryId, rows);
    }

    private void rebuildAffectedAggregates(long repositoryId, List<CommitAttribution> rows) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (CommitAttribution row : rows) dates.add(row.commit().date());
        for (LocalDate date : dates) {
            jdbc.update("delete from daily_attribution_stats where repository_id = ? and stat_date = ?", repositoryId, date);
            jdbc.update("""
                    insert into daily_attribution_stats (repository_id, stat_date, ai_lines, human_lines, mixed_lines, unknown_lines, commit_count, synced_at)
                    select repository_id, commit_date, sum(ai_lines), sum(human_lines), sum(mixed_lines), sum(unknown_lines), count(*), CURRENT_TIMESTAMP
                    from commit_attribution_stats where repository_id = ? and commit_date = ?
                    group by repository_id, commit_date
                    """, repositoryId, date);
            jdbc.update("delete from agent_daily_stats where repository_id = ? and stat_date = ?", repositoryId, date);
            jdbc.update("""
                    insert into agent_daily_stats (repository_id, stat_date, agent, model, ai_lines, session_count)
                    select c.repository_id, c.commit_date, a.agent, a.model, sum(a.ai_lines), sum(a.session_count)
                    from commit_agent_stats a join commit_attribution_stats c
                    on c.repository_id = a.repository_id and c.commit_sha = a.commit_sha
                    where c.repository_id = ? and c.commit_date = ?
                    group by c.repository_id, c.commit_date, a.agent, a.model
                    """, repositoryId, date);
        }
    }

    private void persistRangeProgress(long repositoryId, SyncRange range, long offset, boolean completed) {
        jdbc.update("""
                update repositories set history_base_sha = ?, history_since_sha = ?, history_offset = ?, history_complete = ?,
                synced_head_sha = case when ? then ? else synced_head_sha end
                where id = ?
                """, range.baseSha(), range.sinceSha(), offset, completed, completed, range.baseSha(), repositoryId);
    }

    private void clearRepositoryStats(long repositoryId) {
        jdbc.update("delete from commit_agent_stats where repository_id = ?", repositoryId);
        jdbc.update("delete from commit_attribution_stats where repository_id = ?", repositoryId);
        jdbc.update("delete from agent_daily_stats where repository_id = ?", repositoryId);
        jdbc.update("delete from daily_attribution_stats where repository_id = ?", repositoryId);
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? exception.getClass().getSimpleName() : current.getMessage();
    }
    private String abbreviate(String value) { return value == null ? "" : value.length() <= 2_000 ? value : value.substring(0, 2_000) + "..."; }

    private String abbreviateSha(String value) {
        if (value == null || value.isBlank()) return "<none>";
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private record RepositoryTarget(long id, String name, String gitUrl, String defaultBranch, String mirrorPath,
                                    String historyBaseSha, String historySinceSha, long historyOffset,
                                    boolean historyComplete, String syncedHeadSha) {}
    private record SyncRange(String baseSha, String sinceSha, long offset) {}
    private record CommitInfo(String sha, LocalDate date, String author, String subject) {}
    private record ToolStat(long aiLines, long acceptedLines) {}
    private record AgentStat(long aiLines, long acceptedLines, int sessionCount) {}
    private record GitAiStats(long aiLines, long humanLines, long unknownLines, long additions, long deletions,
                              Map<GitAiNoteParser.AgentIdentity, ToolStat> toolStats) {}
    private record CommitAttribution(long repositoryId, CommitInfo commit, String noteObjectSha, long aiLines,
                                     long humanLines, long mixedLines, long unknownLines, long additions, long deletions,
                                     Map<GitAiNoteParser.AgentIdentity, AgentStat> agents) {}

    @FunctionalInterface
    public interface SyncProgressListener {
        void onProgress(SyncStage stage, long processedCommits, long batchCommitCount);
    }

    public enum SyncStage {
        PREPARING_MIRROR,
        READING_COMMITS,
        READING_ATTRIBUTION,
        WRITING_STATS,
        FINALIZING
    }

    public record SyncResult(long repositoryId, String repositoryName, String status, int commits, String error,
                             boolean historyComplete, long historyOffset) {}
}
