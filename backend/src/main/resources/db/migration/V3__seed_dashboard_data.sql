INSERT INTO daily_attribution_stats (repository_id, stat_date, ai_lines, human_lines, mixed_lines, unknown_lines, commit_count, synced_at)
SELECT r.id,
       CURRENT_DATE - x.day_offset,
       (r.id * 11 + x.day_offset * 7) % 95 + 18,
       (r.id * 17 + x.day_offset * 5) % 110 + 32,
       (r.id * 3 + x.day_offset * 2) % 18 + 2,
       (r.id * 19 + x.day_offset * 11) % 180 + 60,
       (r.id + x.day_offset) % 5 + 1,
       CURRENT_TIMESTAMP
FROM repositories r
CROSS JOIN (
    SELECT 0 AS day_offset UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
    UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14
    UNION ALL SELECT 15 UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19
    UNION ALL SELECT 20 UNION ALL SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23 UNION ALL SELECT 24
    UNION ALL SELECT 25 UNION ALL SELECT 26 UNION ALL SELECT 27 UNION ALL SELECT 28 UNION ALL SELECT 29
) x;

INSERT INTO agent_daily_stats (repository_id, stat_date, agent, model, ai_lines, session_count)
SELECT r.id, CURRENT_DATE - x.day_offset,
       CASE WHEN MOD(r.id + x.day_offset, 3) = 0 THEN 'OpenCode'
            WHEN MOD(r.id + x.day_offset, 3) = 1 THEN 'Codex'
            ELSE 'Claude Code' END,
       CASE WHEN MOD(r.id + x.day_offset, 3) = 0 THEN 'unknown'
            WHEN MOD(r.id + x.day_offset, 3) = 1 THEN 'gpt-5'
            ELSE 'claude-sonnet' END,
       (r.id * 7 + x.day_offset * 3) % 55 + 8,
       MOD(r.id + x.day_offset, 4) + 1
FROM repositories r
CROSS JOIN (
    SELECT 0 AS day_offset UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
    UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14
    UNION ALL SELECT 15 UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19
    UNION ALL SELECT 20 UNION ALL SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23 UNION ALL SELECT 24
    UNION ALL SELECT 25 UNION ALL SELECT 26 UNION ALL SELECT 27 UNION ALL SELECT 28 UNION ALL SELECT 29
) x;
