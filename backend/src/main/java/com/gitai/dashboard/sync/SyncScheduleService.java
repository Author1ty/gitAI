package com.gitai.dashboard.sync;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Database-backed global schedule for repository synchronization. The setting is disabled by default and a conditional
 * update claims each due run, so duplicate scheduler ticks (or a second application instance) do not enqueue the same
 * scheduled round twice. Existing per-repository active-job protection remains the final safety boundary.
 */
@Service
public class SyncScheduleService {
    private static final Logger log = LoggerFactory.getLogger(SyncScheduleService.class);
    private static final int MIN_INTERVAL_MINUTES = 5;
    private static final int MAX_INTERVAL_MINUTES = 10_080; // seven days

    private final JdbcTemplate jdbc;
    private final SyncJobService syncJobs;

    public SyncScheduleService(JdbcTemplate jdbc, SyncJobService syncJobs) {
        this.jdbc = jdbc;
        this.syncJobs = syncJobs;
    }

    public SyncScheduleView getSchedule() {
        return toView(loadSettings());
    }

    /**
     * Changing either switch or interval deliberately resets the clock. When enabled, the next scheduler check queues
     * a fresh bounded batch; this is useful for finishing long initial history imports without waiting an old interval.
     */
    public SyncScheduleView updateSchedule(UpdateSyncScheduleRequest request) {
        log.info("sync schedule update requested enabled={} intervalMinutes={}", request.enabled(), request.intervalMinutes());
        jdbc.update("""
                update sync_schedule_settings
                set enabled = ?, interval_minutes = ?, last_triggered_at = null, updated_at = ?
                where id = 1
                """, request.enabled(), request.intervalMinutes(), Timestamp.valueOf(LocalDateTime.now()));
        SyncScheduleView view = getSchedule();
        log.info("sync schedule updated enabled={} intervalMinutes={} nextRunAt={} due={}",
                view.enabled(), view.intervalMinutes(), view.nextRunAt(), view.due());
        return view;
    }

    /** Runs every configured check interval; the durable due check makes the exact in-memory timer non-authoritative. */
    @Scheduled(fixedDelayString = "${git-ai.sync.schedule-check-delay:PT1M}")
    public void triggerDueSyncsOnSchedule() {
        try {
            triggerDueSyncs();
        } catch (Exception exception) {
            // Keep one transient database or executor failure from disabling future scheduled checks.
            log.error("Unable to run scheduled Git AI synchronization check", exception);
        }
    }

    /** Visible for tests and safe for a one-off administrative invocation. Returns true only when this call claimed a due run. */
    public boolean triggerDueSyncs() {
        ScheduleSettings settings = loadSettings();
        if (!settings.enabled()) {
            log.debug("scheduled sync check skipped reason=disabled intervalMinutes={}", settings.intervalMinutes());
            return false;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(settings.intervalMinutes());
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int claimed = jdbc.update("""
                update sync_schedule_settings
                set last_triggered_at = ?, updated_at = ?
                where id = 1 and enabled = TRUE and interval_minutes = ?
                  and (last_triggered_at is null or last_triggered_at <= ?)
                """, now, now, settings.intervalMinutes(), Timestamp.valueOf(cutoff));
        if (claimed != 1) {
            log.debug("scheduled sync check skipped reason=not-due intervalMinutes={} lastTriggeredAt={}",
                    settings.intervalMinutes(), settings.lastTriggeredAt());
            return false;
        }

        try {
            SyncJobService.SyncJobBatch batch = syncJobs.requestJobs(null);
            long reused = batch.jobs().stream().filter(SyncJobService.SyncJobView::reused).count();
            log.info("scheduled sync triggered repositoryJobs={} reused={} intervalMinutes={}",
                    batch.jobs().size(), reused, settings.intervalMinutes());
            return true;
        } catch (RuntimeException exception) {
            // Do not burn the interval if the queue could not be populated. A later scheduler tick may retry safely.
            jdbc.update("update sync_schedule_settings set last_triggered_at = null, updated_at = ? where id = 1", Timestamp.valueOf(LocalDateTime.now()));
            log.error("scheduled sync enqueue failed intervalMinutes={}, clock reset for retry", settings.intervalMinutes(), exception);
            throw exception;
        }
    }

    private ScheduleSettings loadSettings() {
        List<ScheduleSettings> settings = jdbc.query("""
                select enabled, interval_minutes, last_triggered_at
                from sync_schedule_settings where id = 1
                """, (rs, rowNum) -> new ScheduleSettings(rs.getBoolean("enabled"), rs.getInt("interval_minutes"),
                timestamp(rs.getTimestamp("last_triggered_at"))));
        if (settings.isEmpty()) throw new IllegalStateException("Sync schedule settings are missing");
        return settings.getFirst();
    }

    private SyncScheduleView toView(ScheduleSettings settings) {
        LocalDateTime now = LocalDateTime.now();
        boolean due = settings.enabled() && (settings.lastTriggeredAt() == null
                || !settings.lastTriggeredAt().plusMinutes(settings.intervalMinutes()).isAfter(now));
        LocalDateTime nextRun = !settings.enabled() ? null : due ? now : settings.lastTriggeredAt().plusMinutes(settings.intervalMinutes());
        return new SyncScheduleView(settings.enabled(), settings.intervalMinutes(), asText(settings.lastTriggeredAt()), asText(nextRun), due);
    }

    private LocalDateTime timestamp(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private String asText(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private record ScheduleSettings(boolean enabled, int intervalMinutes, LocalDateTime lastTriggeredAt) {}

    public record UpdateSyncScheduleRequest(@NotNull Boolean enabled,
                                            @NotNull @Min(MIN_INTERVAL_MINUTES) @Max(MAX_INTERVAL_MINUTES) Integer intervalMinutes) {}

    public record SyncScheduleView(boolean enabled, int intervalMinutes, String lastTriggeredAt, String nextRunAt, boolean due) {}
}
