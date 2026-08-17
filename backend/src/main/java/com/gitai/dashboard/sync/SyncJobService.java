package com.gitai.dashboard.sync;

import com.gitai.dashboard.git.GitAiSyncService;
import com.gitai.dashboard.logging.LogSupport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Persistent, bounded job queue for Git AI imports.  Git commands only run on the worker executor, never on an HTTP
 * request thread.  The nullable active_repository_id has a unique index, which gives one repository one active job
 * across concurrent requests in both MySQL and H2's MySQL compatibility mode.
 */
@Service
public class SyncJobService {
    private static final List<String> ACTIVE_STATUSES = List.of("QUEUED", "RUNNING");
    private static final Logger log = LoggerFactory.getLogger(SyncJobService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final GitAiSyncService gitAiSyncService;
    private final Executor executor;
    private final SyncJobProperties properties;
    private final Object dispatchMonitor = new Object();

    public SyncJobService(JdbcTemplate jdbc, TransactionTemplate transactions, GitAiSyncService gitAiSyncService,
                          @Qualifier("syncJobExecutor") Executor executor, SyncJobProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.gitAiSyncService = gitAiSyncService;
        this.executor = executor;
        this.properties = properties;
    }

    public SyncJobBatch requestJobs(Long repositoryId) {
        return requestJobs(repositoryId, null, null, null, null);
    }

    /** Requests repositories in the allowed department, optionally narrowed to an explicit UI scope. */
    public SyncJobBatch requestJobs(Long repositoryId, Long allowedDepartmentId) {
        return requestJobs(repositoryId, allowedDepartmentId, null, null, null);
    }

    /**
     * Queues only the repositories represented by the current organization/project/group/repository scope.
     * The SQL predicates are intentionally applied on the server so a client cannot accidentally sync all
     * repositories when the UI is showing a narrower scope.
     */
    public SyncJobBatch requestJobs(Long repositoryId, Long allowedDepartmentId, Long requestedDepartmentId,
                                    Long projectId, Long groupId) {
        log.info("sync jobs requested repositoryId={} allowedDepartmentId={} requestedDepartmentId={} projectId={} groupId={}",
                repositoryId, allowedDepartmentId, requestedDepartmentId, projectId, groupId);
        if (allowedDepartmentId != null && requestedDepartmentId != null && !allowedDepartmentId.equals(requestedDepartmentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Synchronization scope is outside your department");
        }
        validateScopeHierarchy(requestedDepartmentId, projectId, groupId);
        Long departmentId = requestedDepartmentId != null ? requestedDepartmentId : allowedDepartmentId;
        List<SyncJobView> jobs = transactions.execute(status -> {
            StringBuilder sql = new StringBuilder("""
                    select r.id from repositories r
                    join projects p on p.id = r.project_id
                    where 1 = 1
                    """);
            List<Object> arguments = new ArrayList<>();
            if (repositoryId != null) { sql.append(" and r.id = ?"); arguments.add(repositoryId); }
            if (departmentId != null) { sql.append(" and p.department_id = ?"); arguments.add(departmentId); }
            if (projectId != null) { sql.append(" and p.id = ?"); arguments.add(projectId); }
            if (groupId != null) { sql.append(" and r.group_id = ?"); arguments.add(groupId); }
            sql.append(" order by r.id");
            List<Long> repositoryIds = jdbc.queryForList(sql.toString(), Long.class, arguments.toArray());
            if (repositoryId != null && repositoryIds.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Repository is outside the selected synchronization scope");
            }
            if (repositoryIds.isEmpty()) return List.<SyncJobView>of();
            List<SyncJobView> created = new ArrayList<>();
            for (Long id : repositoryIds) created.add(createOrReuseJob(id));
            return created;
        });
        dispatchQueuedJobs();
        List<SyncJobView> result = jobs == null ? List.of() : jobs;
        long reused = result.stream().filter(SyncJobView::reused).count();
        log.info("sync jobs accepted jobCount={} reused={} repositoryId={} departmentId={} projectId={} groupId={}",
                result.size(), reused, repositoryId, departmentId, projectId, groupId);
        return new SyncJobBatch(result);
    }

    private void validateScopeHierarchy(Long departmentId, Long projectId, Long groupId) {
        if (projectId != null) {
            List<Long> departments = jdbc.queryForList("select department_id from projects where id = ?", Long.class, projectId);
            if (departments.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: " + projectId);
            if (departmentId != null && !departmentId.equals(departments.getFirst())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project is outside the selected department");
            }
        }
        if (groupId != null) {
            List<Long> projects = jdbc.queryForList("select project_id from repository_groups where id = ?", Long.class, groupId);
            if (projects.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository group not found: " + groupId);
            if (projectId != null && !projectId.equals(projects.getFirst())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Repository group is outside the selected project");
            }
        }
    }

    public SyncJobView requestRepositoryJob(long repositoryId) {
        return requestJobs(repositoryId).jobs().getFirst();
    }

    public List<SyncJobView> listJobs(boolean activeOnly, int requestedLimit) {
        return listJobs(activeOnly, requestedLimit, null);
    }

    public List<SyncJobView> listJobs(boolean activeOnly, int requestedLimit, Long departmentId) {
        int limit = Math.clamp(requestedLimit, 1, 100);
        String where = "where " + (activeOnly ? "j.status in ('QUEUED', 'RUNNING')" : "1 = 1")
                + (departmentId == null ? "" : " and p.department_id = ?");
        String sql = """
                select j.id, j.repository_id, r.name repository_name, j.status, j.requested_at, j.started_at, j.finished_at,
                       j.processed_commits, j.batch_commit_count, j.history_complete, j.history_offset, j.phase, j.phase_updated_at, j.message, j.error_message
                from sync_jobs j join repositories r on r.id = j.repository_id join projects p on p.id = r.project_id
                %s order by j.requested_at desc, j.id desc limit ?
                """.formatted(where);
        Object[] arguments = departmentId == null ? new Object[]{limit} : new Object[]{departmentId, limit};
        return jdbc.query(sql, (rs, rowNum) -> toView(rs, false), arguments);
    }

    /** Lightweight queue telemetry for the UI and production monitoring. */
    public SyncQueueView queueStatus() { return queueStatus(null); }

    public SyncQueueView queueStatus(Long departmentId) {
        String source = " from sync_jobs j join repositories r on r.id = j.repository_id join projects p on p.id = r.project_id";
        String scope = departmentId == null ? "" : " and p.department_id = ?";
        Long running = jdbc.queryForObject("select count(*)" + source + " where j.status = 'RUNNING'" + scope, Long.class,
                departmentId == null ? new Object[]{} : new Object[]{departmentId});
        Long queued = jdbc.queryForObject("select count(*)" + source + " where j.status = 'QUEUED'" + scope, Long.class,
                departmentId == null ? new Object[]{} : new Object[]{departmentId});
        return new SyncQueueView(properties.getWorkerCount(), running == null ? 0 : running, queued == null ? 0 : queued);
    }

    public SyncJobView getJob(long jobId) { return getJob(jobId, null); }

    public SyncJobView getJob(long jobId, Long departmentId) {
        String scope = departmentId == null ? "" : " and p.department_id = ?";
        Object[] arguments = departmentId == null ? new Object[]{jobId} : new Object[]{jobId, departmentId};
        List<SyncJobView> jobs = jdbc.query(("""
                select j.id, j.repository_id, r.name repository_name, j.status, j.requested_at, j.started_at, j.finished_at,
                       j.processed_commits, j.batch_commit_count, j.history_complete, j.history_offset, j.phase, j.phase_updated_at, j.message, j.error_message
                from sync_jobs j join repositories r on r.id = j.repository_id join projects p on p.id = r.project_id where j.id = ?
                """ + scope), (rs, rowNum) -> toView(rs, false), arguments);
        if (jobs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sync job not found: " + jobId);
        return jobs.getFirst();
    }

    /** Only queued work can be cancelled; a running Git process is intentionally not force-killed in this first version. */
    public SyncJobView cancelQueuedJob(long jobId) { return cancelQueuedJob(jobId, null); }

    public SyncJobView cancelQueuedJob(long jobId, Long departmentId) {
        log.info("sync job cancellation requested jobId={} departmentId={}", jobId, departmentId);
        getJob(jobId, departmentId);
        int updated = jdbc.update("""
                update sync_jobs set status = 'CANCELLED', finished_at = CURRENT_TIMESTAMP, active_repository_id = null,
                phase = 'CANCELLED', phase_updated_at = CURRENT_TIMESTAMP, message = 'Cancelled before execution'
                where id = ? and status = 'QUEUED'
                """, jobId);
        if (updated == 0) {
            SyncJobView job = getJob(jobId, departmentId);
            if ("RUNNING".equals(job.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A running Git process cannot be cancelled");
            }
            return job;
        }
        SyncJobView cancelled = getJob(jobId, departmentId);
        log.info("sync job cancelled jobId={} repositoryId={} repository={}", jobId, cancelled.repositoryId(), cancelled.repositoryName());
        return cancelled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverJobsAfterRestart() {
        Integer recovered = transactions.execute(status -> jdbc.update("""
                update sync_jobs set status = 'QUEUED', started_at = null, phase = 'QUEUED', phase_updated_at = CURRENT_TIMESTAMP,
                message = 'Recovered into queue after service restart' where status = 'RUNNING'
                """));
        log.info("sync jobs recovered after restart recoveredCount={}", recovered == null ? 0 : recovered);
        dispatchQueuedJobs();
    }

    private SyncJobView createOrReuseJob(long repositoryId) {
        repositoryName(repositoryId);
        List<SyncJobView> active = jdbc.query("""
                select j.id, j.repository_id, r.name repository_name, j.status, j.requested_at, j.started_at, j.finished_at,
                       j.processed_commits, j.batch_commit_count, j.history_complete, j.history_offset, j.phase, j.phase_updated_at, j.message, j.error_message
                from sync_jobs j join repositories r on r.id = j.repository_id
                where j.repository_id = ? and j.status in ('QUEUED', 'RUNNING')
                order by j.id desc limit 1
                """, (rs, rowNum) -> toView(rs, true), repositoryId);
        if (!active.isEmpty()) return active.getFirst();
        try {
            jdbc.update("""
                    insert into sync_jobs (repository_id, active_repository_id, status, phase, phase_updated_at, message)
                    values (?, ?, 'QUEUED', 'QUEUED', CURRENT_TIMESTAMP, 'Waiting for a Git worker')
                    """, repositoryId, repositoryId);
        } catch (DuplicateKeyException exception) {
            List<SyncJobView> existing = jdbc.query("""
                    select j.id, j.repository_id, r.name repository_name, j.status, j.requested_at, j.started_at, j.finished_at,
                           j.processed_commits, j.batch_commit_count, j.history_complete, j.history_offset, j.phase, j.phase_updated_at, j.message, j.error_message
                    from sync_jobs j join repositories r on r.id = j.repository_id
                    where j.repository_id = ? and j.status in ('QUEUED', 'RUNNING') order by j.id desc limit 1
                    """, (rs, rowNum) -> toView(rs, true), repositoryId);
            if (!existing.isEmpty()) return existing.getFirst();
            throw exception;
        }
        Long jobId = jdbc.queryForObject("select max(id) from sync_jobs where repository_id = ?", Long.class, repositoryId);
        return getJob(jobId);
    }

    private String repositoryName(long repositoryId) {
        List<String> names = jdbc.queryForList("select name from repositories where id = ?", String.class, repositoryId);
        if (names.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found: " + repositoryId);
        return names.getFirst();
    }

    private void dispatchQueuedJobs() {
        synchronized (dispatchMonitor) {
            List<Long> claimed = transactions.execute(status -> claimQueuedJobs());
            if (claimed == null) return;
            if (!claimed.isEmpty()) log.info("sync jobs dispatched claimedCount={} workerCount={}", claimed.size(), properties.getWorkerCount());
            for (Long jobId : claimed) {
                try {
                    executor.execute(() -> runClaimedJob(jobId));
                } catch (RuntimeException exception) {
                    failRejectedJob(jobId, exception);
                }
            }
        }
    }

    private List<Long> claimQueuedJobs() {
        Integer running = jdbc.queryForObject("select count(*) from sync_jobs where status = 'RUNNING'", Integer.class);
        int capacity = Math.max(0, properties.getWorkerCount() - (running == null ? 0 : running));
        if (capacity == 0) return List.of();
        List<Long> candidates = jdbc.queryForList("select id from sync_jobs where status = 'QUEUED' order by requested_at, id limit ?", Long.class, capacity);
        List<Long> claimed = new ArrayList<>();
        for (Long jobId : candidates) {
            if (jdbc.update("""
                    update sync_jobs set status = 'RUNNING', started_at = CURRENT_TIMESTAMP, phase = 'PREPARING_MIRROR',
                    phase_updated_at = CURRENT_TIMESTAMP, message = 'Preparing Git mirror'
                    where id = ? and status = 'QUEUED'
                    """, jobId) == 1) {
                claimed.add(jobId);
            }
        }
        if (!claimed.isEmpty()) log.debug("sync jobs claimed jobIds={}", claimed);
        return claimed;
    }

    private void runClaimedJob(long jobId) {
        long repositoryId;
        try {
            repositoryId = jdbc.queryForObject("select repository_id from sync_jobs where id = ?", Long.class, jobId);
        } catch (Exception exception) {
            failJob(jobId, 0, false, 0, "同步任务读取失败: " + rootMessage(exception));
            dispatchQueuedJobs();
            return;
        }
        String repositoryName = repositoryName(repositoryId);
        long started = System.nanoTime();
        log.info("sync worker started jobId={} repositoryId={} repository={} thread={}", jobId, repositoryId, repositoryName,
                Thread.currentThread().getName());
        try {
            GitAiSyncService.SyncResult result = gitAiSyncService.syncRepository(repositoryId,
                    (stage, processedCommits, batchCommitCount) -> reportProgress(jobId, stage, processedCommits, batchCommitCount));
            if ("SUCCESS".equals(result.status())) {
                String message = result.historyComplete()
                        ? "本批同步完成，仓库历史已同步"
                        : "本批同步完成，可继续回溯历史";
                completeJob(jobId, "SUCCESS", result.commits(), result.historyComplete(), result.historyOffset(), message, null);
                log.info("sync worker completed jobId={} repositoryId={} repository={} status=SUCCESS commits={} historyComplete={} nextOffset={} durationMs={}",
                        jobId, repositoryId, repositoryName, result.commits(), result.historyComplete(), result.historyOffset(), elapsedMs(started));
            } else {
                failJob(jobId, result.commits(), result.historyComplete(), result.historyOffset(), result.error());
                log.error("sync worker completed jobId={} repositoryId={} repository={} status=FAILED commits={} durationMs={} error={}",
                        jobId, repositoryId, repositoryName, result.commits(), elapsedMs(started), LogSupport.safeRemote(result.error()));
            }
        } catch (Exception exception) {
            failJob(jobId, 0, false, 0, rootMessage(exception));
            log.error("sync worker failed jobId={} repositoryId={} repository={} durationMs={} errorType={} error={}",
                    jobId, repositoryId, repositoryName, elapsedMs(started), exception.getClass().getSimpleName(),
                    LogSupport.safeExceptionMessage(exception), exception);
        } finally {
            dispatchQueuedJobs();
        }
    }

    private void completeJob(long jobId, String status, long processedCommits, boolean historyComplete, long historyOffset,
                             String message, String error) {
        jdbc.update("""
                update sync_jobs set status = ?, finished_at = CURRENT_TIMESTAMP, processed_commits = ?, batch_commit_count = ?,
                history_complete = ?, history_offset = ?, phase = ?, phase_updated_at = CURRENT_TIMESTAMP, message = ?,
                error_message = ?, active_repository_id = null where id = ?
                """, status, processedCommits, processedCommits, historyComplete, historyOffset,
                "SUCCESS".equals(status) ? "COMPLETED" : "FAILED", abbreviate(message, 500), abbreviate(error, 2000), jobId);
    }

    /** Writes at most a few dozen checkpoints per bounded batch, keeping job visibility inexpensive for large repositories. */
    private void reportProgress(long jobId, GitAiSyncService.SyncStage stage, long processedCommits, long batchCommitCount) {
        jdbc.update("""
                update sync_jobs set phase = ?, phase_updated_at = CURRENT_TIMESTAMP, processed_commits = ?,
                batch_commit_count = ?, message = ? where id = ? and status = 'RUNNING'
                """, stage.name(), processedCommits, batchCommitCount, progressMessage(stage, processedCommits, batchCommitCount), jobId);
        if (stage != GitAiSyncService.SyncStage.READING_ATTRIBUTION || processedCommits == 0 || processedCommits == batchCommitCount) {
            log.info("sync worker progress jobId={} stage={} processedCommits={} batchCommitCount={}", jobId, stage, processedCommits, batchCommitCount);
        } else if (processedCommits % 100 == 0) {
            log.debug("sync worker progress jobId={} stage={} processedCommits={} batchCommitCount={}", jobId, stage, processedCommits, batchCommitCount);
        }
    }

    private String progressMessage(GitAiSyncService.SyncStage stage, long processedCommits, long batchCommitCount) {
        return switch (stage) {
            case PREPARING_MIRROR -> "Preparing or fetching the Git mirror";
            case READING_COMMITS -> "Reading the bounded commit batch";
            case READING_ATTRIBUTION -> batchCommitCount == 0
                    ? "Reading Git AI attribution"
                    : "Reading Git AI attribution: " + processedCommits + " / " + batchCommitCount + " commits";
            case WRITING_STATS -> "Writing attribution statistics";
            case FINALIZING -> "Saving repository progress";
        };
    }

    private void failJob(long jobId, long processedCommits, boolean historyComplete, long historyOffset, String error) {
        completeJob(jobId, "FAILED", processedCommits, historyComplete, historyOffset, "同步失败", error);
    }

    private void failRejectedJob(long jobId, RuntimeException exception) {
        failJob(jobId, 0, false, 0, "同步执行器不可用: " + rootMessage(exception));
    }

    private SyncJobView toView(java.sql.ResultSet rs, boolean reused) throws java.sql.SQLException {
        boolean historyComplete = rs.getBoolean("history_complete");
        Boolean history = rs.wasNull() ? null : historyComplete;
        return new SyncJobView(rs.getLong("id"), rs.getLong("repository_id"), rs.getString("repository_name"),
                rs.getString("status"), rs.getString("phase"), timestamp(rs.getTimestamp("phase_updated_at")),
                timestamp(rs.getTimestamp("requested_at")), timestamp(rs.getTimestamp("started_at")), timestamp(rs.getTimestamp("finished_at")),
                rs.getLong("processed_commits"), rs.getLong("batch_commit_count"), history, rs.getLong("history_offset"),
                rs.getString("message"), rs.getString("error_message"), reused);
    }

    private String timestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toString();
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? exception.getClass().getSimpleName() : current.getMessage();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    public record CreateSyncJobsRequest(Long repositoryId, Long departmentId, Long projectId, Long groupId) {}
    public record SyncJobBatch(List<SyncJobView> jobs) {}
    public record SyncQueueView(int workerCount, long runningJobs, long queuedJobs) {}
    public record SyncJobView(long id, long repositoryId, String repositoryName, String status, String phase,
                              String phaseUpdatedAt, String requestedAt, String startedAt, String finishedAt,
                              long processedCommits, long batchCommitCount, Boolean historyComplete, long historyOffset,
                              String message, String error, boolean reused) {}
}
