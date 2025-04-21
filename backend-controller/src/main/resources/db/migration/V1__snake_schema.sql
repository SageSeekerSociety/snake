CREATE TABLE compilation_jobs
(
    job_id              UUID                        NOT NULL,
    user_id             BIGINT                      NOT NULL,
    status              VARCHAR(255)                NOT NULL,
    submit_time         TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    source_code_ref     VARCHAR(1024)               NOT NULL,
    receive_time        TIMESTAMP WITHOUT TIME ZONE,
    start_compile_time  TIMESTAMP WITHOUT TIME ZONE,
    end_compile_time    TIMESTAMP WITHOUT TIME ZONE,
    compiler_output     TEXT,
    program_storage_ref VARCHAR(1024),
    worker_node_id      VARCHAR(255),
    error_details       TEXT,
    CONSTRAINT pk_compilation_jobs PRIMARY KEY (job_id)
);

CREATE TABLE execution_jobs
(
    job_id               UUID                        NOT NULL,
    user_id              BIGINT                      NOT NULL,
    status               VARCHAR(255)                NOT NULL,
    submit_time          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    receive_time         TIMESTAMP WITHOUT TIME ZONE,
    start_execution_time TIMESTAMP WITHOUT TIME ZONE,
    end_execution_time   TIMESTAMP WITHOUT TIME ZONE,
    program_output       TEXT,
    cpu_time_seconds     DOUBLE PRECISION,
    memory_kb            BIGINT,
    exit_code            INTEGER,
    sandbox_log_ref      VARCHAR(1024),
    worker_node_id       VARCHAR(255),
    error_details        TEXT,
    CONSTRAINT pk_execution_jobs PRIMARY KEY (job_id)
);

CREATE TABLE players
(
    user_id                        INTEGER                     NOT NULL,
    nickname                       VARCHAR(255)                NOT NULL,
    last_successful_compile_job_id UUID,
    last_successful_compile_time   TIMESTAMP WITHOUT TIME ZONE,
    compiled_program_ref           VARCHAR(1024)               NOT NULL,
    is_active                      BOOLEAN                     NOT NULL,
    created_at                     TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at                     TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_players PRIMARY KEY (user_id)
);

CREATE INDEX idx_compilation_jobs_status ON compilation_jobs (status);

CREATE INDEX idx_compilation_jobs_submit_time ON compilation_jobs (submit_time);

CREATE INDEX idx_compilation_jobs_user_id ON compilation_jobs (user_id);

CREATE INDEX idx_execution_jobs_status ON execution_jobs (status);

CREATE INDEX idx_execution_jobs_submit_time ON execution_jobs (submit_time);

CREATE INDEX idx_execution_jobs_user_id ON execution_jobs (user_id);