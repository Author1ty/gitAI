package com.gitai.dashboard.sync;

import com.gitai.dashboard.git.GitAiSyncService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncJobServiceTests {
    private JdbcTemplate jdbc;
    private GitAiSyncService gitAiSyncService;
    private SyncJobProperties properties;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:sync_jobs_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        gitAiSyncService = mock(GitAiSyncService.class);
        properties = new SyncJobProperties();
        properties.setWorkerCount(1);
    }

    @Test
    void reusesTheSameActiveJobForTheSameRepository() {
        List<Runnable> scheduled = new ArrayList<>();
        SyncJobService service = newService(scheduled::add);

        SyncJobService.SyncJobView first = service.requestRepositoryJob(1);
        SyncJobService.SyncJobView second = service.requestRepositoryJob(1);

        assertFalse(first.reused());
        assertNotNull(first.requestedAt());
        assertTrue(second.reused());
        assertEquals(first.id(), second.id());
        assertEquals("RUNNING", service.getJob(first.id()).status());
        assertEquals(1, scheduled.size(), "the second HTTP request must not schedule another Git task");
        assertEquals(1, jdbc.queryForObject("select count(*) from sync_jobs where active_repository_id = 1", Integer.class));
    }

    @Test
    void reportsOnlyActuallyClaimedWorkAsRunning() {
        List<Runnable> scheduled = new ArrayList<>();
        SyncJobService service = newService(scheduled::add);

        long secondRepositoryId = createSecondRepository();
        SyncJobService.SyncJobView first = service.requestRepositoryJob(1);
        SyncJobService.SyncJobView second = service.requestRepositoryJob(secondRepositoryId);
        SyncJobService.SyncQueueView queue = service.queueStatus();

        assertEquals("RUNNING", service.getJob(first.id()).status());
        assertEquals("QUEUED", service.getJob(second.id()).status());
        assertEquals(1, scheduled.size(), "only available worker slots are submitted to the executor");
        assertEquals(1, queue.workerCount());
        assertEquals(1, queue.runningJobs());
        assertEquals(1, queue.queuedJobs());
    }

    @Test
    void completesAJobAndStoresBoundedBatchProgress() {
        when(gitAiSyncService.syncRepository(eq(1L), any())).thenAnswer(invocation -> {
            GitAiSyncService.SyncProgressListener progress = invocation.getArgument(1);
            progress.onProgress(GitAiSyncService.SyncStage.READING_ATTRIBUTION, 125, 500);
            return new GitAiSyncService.SyncResult(1, "git-ai-attribution-sample", "SUCCESS", 500, null, false, 500);
        });
        SyncJobService service = newService(Runnable::run);

        SyncJobService.SyncJobView requested = service.requestRepositoryJob(1);
        SyncJobService.SyncJobView finished = service.getJob(requested.id());

        assertEquals("SUCCESS", finished.status());
        assertEquals(500, finished.processedCommits());
        assertEquals(500, finished.batchCommitCount());
        assertEquals("COMPLETED", finished.phase());
        assertEquals(Boolean.FALSE, finished.historyComplete());
        assertEquals(500, finished.historyOffset());
        assertNotNull(finished.finishedAt());
        assertNull(jdbc.queryForObject("select active_repository_id from sync_jobs where id = ?", Long.class, requested.id()));
        verify(gitAiSyncService).syncRepository(eq(1L), any());
    }

    @Test
    void queuesOnlyRepositoriesInSelectedDepartment() {
        addMultiDepartmentFixture();
        SyncJobService service = newService(command -> { });

        SyncJobService.SyncJobBatch batch = service.requestJobs(null, null, 10L, null, null);

        assertEquals(List.of(7L, 8L), batch.jobs().stream().map(SyncJobService.SyncJobView::repositoryId).toList());
        assertEquals(2, jdbc.queryForObject("select count(*) from sync_jobs", Integer.class));
    }

    @Test
    void queuesOnlyRepositoriesInSelectedProjectAndGroup() {
        addMultiDepartmentFixture();
        SyncJobService service = newService(command -> { });

        SyncJobService.SyncJobBatch projectBatch = service.requestJobs(null, null, null, 10L, null);
        SyncJobService.SyncJobBatch groupBatch = service.requestJobs(null, null, null, 10L, 10L);

        assertEquals(List.of(7L, 8L), projectBatch.jobs().stream().map(SyncJobService.SyncJobView::repositoryId).toList());
        assertEquals(List.of(7L), groupBatch.jobs().stream().map(SyncJobService.SyncJobView::repositoryId).toList());
    }

    @Test
    void rejectsInconsistentAndOutOfScopeSelections() {
        addMultiDepartmentFixture();
        SyncJobService service = newService(command -> { });

        assertThrows(ResponseStatusException.class, () -> service.requestJobs(null, 1L, 2L, null, null));
        assertThrows(ResponseStatusException.class, () -> service.requestJobs(null, null, 10L, 11L, null));
        assertThrows(ResponseStatusException.class, () -> service.requestJobs(null, null, null, 10L, 11L));
        assertThrows(ResponseStatusException.class, () -> service.requestJobs(9L, null, 10L, null, null));
    }

    @Test
    void singleRepositoryRequestCannotEscapeSelectedScope() {
        addMultiDepartmentFixture();
        SyncJobService service = newService(command -> { });

        assertThrows(ResponseStatusException.class, () -> service.requestJobs(9L, null, 10L, null, null));
        SyncJobService.SyncJobBatch batch = service.requestJobs(9L, null, 2L, null, null);
        assertEquals(List.of(9L), batch.jobs().stream().map(SyncJobService.SyncJobView::repositoryId).toList());
    }

    private void addMultiDepartmentFixture() {
        jdbc.update("insert into departments (id, name, description) values (10, 'department-a', 'A'), (2, 'department-b', 'B')");
        jdbc.update("insert into projects (id, department_id, name, description) values (10, 10, 'project-a2', 'A2'), (11, 2, 'project-b2', 'B2')");
        jdbc.update("insert into repository_groups (id, project_id, name) values (10, 10, 'group-a'), (11, 11, 'group-b')");
        jdbc.update("""
                insert into repositories (id, project_id, group_id, name, git_url, default_branch, last_sync_status)
                values (7, 10, 10, 'repo-a1', 'file:///repo-a1.git', 'main', 'NOT_SYNCED'),
                       (8, 10, null, 'repo-a2', 'file:///repo-a2.git', 'main', 'NOT_SYNCED'),
                       (9, 11, 11, 'repo-b1', 'file:///repo-b1.git', 'main', 'NOT_SYNCED')
                """);
    }

    @Test
    void cancelsOnlyQueuedWorkAndReleasesTheRepositorySlot() {
        jdbc.update("""
                insert into sync_jobs (repository_id, active_repository_id, status, message)
                values (1, 1, 'QUEUED', 'waiting')
                """);
        long jobId = jdbc.queryForObject("select max(id) from sync_jobs", Long.class);
        SyncJobService service = newService(command -> { });

        SyncJobService.SyncJobView cancelled = service.cancelQueuedJob(jobId);
        SyncJobService.SyncJobView repeated = service.cancelQueuedJob(jobId);

        assertEquals("CANCELLED", cancelled.status());
        assertNotNull(cancelled.finishedAt());
        assertEquals("CANCELLED", repeated.status());
        assertNull(jdbc.queryForObject("select active_repository_id from sync_jobs where id = ?", Long.class, jobId));
    }

    private long createSecondRepository() {
        jdbc.update("""
                insert into repositories (project_id, group_id, name, git_url, default_branch, last_sync_status)
                values (1, 1, 'second-test-repository', 'file:///second-test-repository.git', 'main', 'NOT_SYNCED')
                """);
        return jdbc.queryForObject("select max(id) from repositories", Long.class);
    }

    private SyncJobService newService(Executor executor) {
        return new SyncJobService(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                gitAiSyncService, executor, properties);
    }
}
