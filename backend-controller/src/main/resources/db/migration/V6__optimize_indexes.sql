-- Optimize indexes for common access patterns

-- 1) For queries: findByUserIdOrderBySubmitTimeDesc(userId)
-- Composite index supports filtering by user_id and sorting by submit_time without extra sort
CREATE INDEX IF NOT EXISTS idx_compilation_jobs_user_submit_time
    ON compilation_jobs (user_id, submit_time DESC);

CREATE INDEX IF NOT EXISTS idx_execution_jobs_user_submit_time
    ON execution_jobs (user_id, submit_time DESC);

-- 2) For queries fetching job IDs by (session_id, requesting_user_id)
-- Replace prior index ordering to better match equality predicates
-- Old: (session_id, tick_number, requesting_user_id)
-- New: (session_id, requesting_user_id, tick_number) and cover job_id for index-only scans
DROP INDEX IF EXISTS idx_execution_jobs_session_tick_requesting_user;

CREATE INDEX IF NOT EXISTS idx_execution_jobs_session_requesting_user_tick
    ON execution_jobs (session_id, requesting_user_id, tick_number)
    INCLUDE (job_id);
