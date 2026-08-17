-- Repair jobs that were already terminal before V7 introduced durable phase and batch-progress fields.
-- V7 gave those historical rows the column defaults (QUEUED / 0), which is misleading in the task list.
UPDATE sync_jobs
SET phase = CASE
        WHEN status = 'SUCCESS' THEN 'COMPLETED'
        WHEN status = 'FAILED' THEN 'FAILED'
        WHEN status = 'CANCELLED' THEN 'CANCELLED'
        ELSE phase
    END,
    phase_updated_at = COALESCE(phase_updated_at, finished_at, started_at, requested_at)
WHERE status IN ('SUCCESS', 'FAILED', 'CANCELLED')
  AND (phase IS NULL OR phase = 'QUEUED');

-- A pre-V7 terminal job already had its processed commit count, but no batch count.
UPDATE sync_jobs
SET batch_commit_count = processed_commits
WHERE status = 'SUCCESS'
  AND batch_commit_count = 0
  AND processed_commits > 0;