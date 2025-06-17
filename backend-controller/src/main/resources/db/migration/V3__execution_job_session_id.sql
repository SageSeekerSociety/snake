ALTER TABLE execution_jobs ADD COLUMN session_id UUID;
CREATE INDEX idx_execution_jobs_session_user ON execution_jobs (session_id, user_id);