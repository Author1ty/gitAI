package com.gitai.dashboard.api;

import com.gitai.dashboard.auth.AccessPolicy;
import com.gitai.dashboard.auth.AuditService;
import com.gitai.dashboard.auth.CurrentUser;
import com.gitai.dashboard.sync.SyncJobService;
import com.gitai.dashboard.sync.SyncScheduleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class SyncController {
    private final SyncJobService syncJobs;
    private final SyncScheduleService syncSchedule;
    private final AccessPolicy access;
    private final AuditService audit;

    public SyncController(SyncJobService syncJobs, SyncScheduleService syncSchedule, AccessPolicy access, AuditService audit) {
        this.syncJobs = syncJobs;
        this.syncSchedule = syncSchedule;
        this.access = access;
        this.audit = audit;
    }

    @PostMapping("/repositories/{repositoryId}/sync")
    public SyncJobService.SyncJobView syncRepository(@PathVariable long repositoryId) { return queueRepository(repositoryId); }

    @PostMapping("/repositories/{repositoryId}/sync-jobs")
    public SyncJobService.SyncJobView createRepositorySyncJob(@PathVariable long repositoryId) { return queueRepository(repositoryId); }

    @PostMapping("/sync")
    public SyncJobService.SyncJobBatch syncAll() { return queueJobs(null); }

    @PostMapping("/sync-jobs")
    public SyncJobService.SyncJobBatch createSyncJobs(@RequestBody(required = false) SyncJobService.CreateSyncJobsRequest request) {
        return queueJobs(request);
    }

    @GetMapping("/sync-jobs")
    public List<SyncJobService.SyncJobView> listSyncJobs(@RequestParam(defaultValue = "false") boolean activeOnly,
                                                           @RequestParam(defaultValue = "30") int limit) {
        return syncJobs.listJobs(activeOnly, limit, access.allowedDepartmentId());
    }

    @GetMapping("/sync-jobs/status")
    public SyncJobService.SyncQueueView syncQueueStatus() { return syncJobs.queueStatus(access.allowedDepartmentId()); }

    @GetMapping("/sync-schedule")
    public SyncScheduleService.SyncScheduleView getSyncSchedule() { return syncSchedule.getSchedule(); }

    @PutMapping("/sync-schedule")
    public SyncScheduleService.SyncScheduleView updateSyncSchedule(@Valid @RequestBody SyncScheduleService.UpdateSyncScheduleRequest request) {
        access.requireSuperAdmin();
        SyncScheduleService.SyncScheduleView view = syncSchedule.updateSchedule(request);
        audit.record(access.currentUser(), "SYNC_SCHEDULE_UPDATED", "sync_schedule", 1, "enabled=" + request.enabled() + ", intervalMinutes=" + request.intervalMinutes());
        return view;
    }

    @GetMapping("/sync-jobs/{jobId}")
    public SyncJobService.SyncJobView getSyncJob(@PathVariable long jobId) { return syncJobs.getJob(jobId, access.allowedDepartmentId()); }

    @PostMapping("/sync-jobs/{jobId}/cancel")
    public SyncJobService.SyncJobView cancelQueuedJob(@PathVariable long jobId) {
        access.requireManage();
        SyncJobService.SyncJobView view = syncJobs.cancelQueuedJob(jobId, access.allowedDepartmentId());
        audit.record(access.currentUser(), "SYNC_JOB_CANCELLED", "sync_job", jobId, view.repositoryName());
        return view;
    }

    private SyncJobService.SyncJobView queueRepository(long repositoryId) {
        access.requireRepository(repositoryId);
        SyncJobService.SyncJobView job = syncJobs.requestJobs(repositoryId, access.allowedDepartmentId()).jobs().getFirst();
        audit.record(access.currentUser(), "SYNC_REQUESTED", "repository", repositoryId, job.status());
        return job;
    }

    private SyncJobService.SyncJobBatch queueJobs(SyncJobService.CreateSyncJobsRequest request) {
        access.requireManage();
        Long repositoryId = request == null ? null : request.repositoryId();
        Long departmentId = request == null ? null : request.departmentId();
        Long projectId = request == null ? null : request.projectId();
        Long groupId = request == null ? null : request.groupId();
        if (departmentId != null) access.requireDepartment(departmentId);
        if (projectId != null) access.requireProject(projectId);
        if (groupId != null) access.requireGroup(groupId);
        if (repositoryId != null) access.requireRepository(repositoryId);
        SyncJobService.SyncJobBatch batch = syncJobs.requestJobs(repositoryId, access.allowedDepartmentId(), departmentId, projectId, groupId);
        String target = repositoryId == null ? "repository_scope" : "repository";
        audit.record(access.currentUser(), "SYNC_REQUESTED", target, repositoryId,
                "departmentId=" + departmentId + ", projectId=" + projectId + ", groupId=" + groupId + ", jobs=" + batch.jobs().size());
        return batch;
    }
}
