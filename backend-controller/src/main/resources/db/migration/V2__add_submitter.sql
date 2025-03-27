-- V2__add_submitter.sql

CREATE SEQUENCE submitter_id_seq;

CREATE TABLE submitter
(
    id         BIGINT                      NOT NULL DEFAULT nextval('submitter_id_seq'),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    deleted_at TIMESTAMP WITHOUT TIME ZONE,
    user_id    BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_submitter_user FOREIGN KEY (user_id) REFERENCES "user" (id)
);

CREATE INDEX idx_submitter_deleted_at ON submitter (deleted_at);
