ALTER TABLE execution_jobs
    ADD COLUMN requesting_user_id BIGINT;
CREATE INDEX idx_execution_jobs_session_requesting_user ON execution_jobs (session_id, requesting_user_id);