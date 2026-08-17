-- MySQL production baseline intentionally does not seed synthetic attribution statistics.
-- This also avoids the legacy H2-only date arithmetic expression (date minus integer).
SELECT 1;
