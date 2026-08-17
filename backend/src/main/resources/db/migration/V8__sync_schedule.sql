-- Global, database-backed automatic synchronization configuration. Disabled by default so upgrades never start an unexpected clone.
CREATE TABLE sync_schedule_settings (
    id INT NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    interval_minutes INT NOT NULL DEFAULT 60,
    last_triggered_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO sync_schedule_settings (id, enabled, interval_minutes, last_triggered_at, updated_at)
VALUES (1, FALSE, 60, NULL, CURRENT_TIMESTAMP);
