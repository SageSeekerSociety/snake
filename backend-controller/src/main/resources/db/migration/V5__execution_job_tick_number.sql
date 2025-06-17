ALTER TABLE execution_jobs
    ADD COLUMN tick_number INTEGER;
DROP INDEX IF EXISTS idx_execution_jobs_session_requesting_user;
CREATE INDEX idx_execution_jobs_session_tick_requesting_user ON execution_jobs (session_id, tick_number, requesting_user_id);
