package com.gitai.dashboard.sync;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncScheduleServiceTests {
    private JdbcTemplate jdbc;
    private SyncJobService syncJobs;
    private SyncScheduleService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:sync_schedule_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        syncJobs = mock(SyncJobService.class);
        service = new SyncScheduleService(jdbc, syncJobs);
    }

    @Test
    void isDisabledByDefaultAndDoesNotQueueWork() {
        SyncScheduleService.SyncScheduleView schedule = service.getSchedule();

        assertFalse(schedule.enabled());
        assertEquals(60, schedule.intervalMinutes());
        assertFalse(service.triggerDueSyncs());
        verify(syncJobs, times(0)).requestJobs(isNull());
    }

    @Test
    void enabledScheduleQueuesOnlyOncePerIntervalAndResetsOnUpdate() {
        when(syncJobs.requestJobs(isNull())).thenReturn(new SyncJobService.SyncJobBatch(java.util.List.of()));
        SyncScheduleService.SyncScheduleView saved = service.updateSchedule(
                new SyncScheduleService.UpdateSyncScheduleRequest(true, 5));

        assertTrue(saved.enabled());
        assertEquals(5, saved.intervalMinutes());
        assertNull(saved.lastTriggeredAt());
        assertTrue(service.triggerDueSyncs());
        assertFalse(service.triggerDueSyncs(), "the durable due claim prevents duplicate runs in the same interval");
        verify(syncJobs, times(1)).requestJobs(isNull());

        service.updateSchedule(new SyncScheduleService.UpdateSyncScheduleRequest(true, 10));
        assertTrue(service.triggerDueSyncs(), "saving a new interval intentionally starts a fresh schedule window");
        verify(syncJobs, times(2)).requestJobs(isNull());
    }
}
