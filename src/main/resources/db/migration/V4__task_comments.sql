-- V4: comments on tasks.

CREATE TABLE task_comments (
    id         BIGSERIAL PRIMARY KEY,
    task_id    BIGINT        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    author_id  BIGINT        NOT NULL REFERENCES users (id),
    body       VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_task_comments_task ON task_comments (task_id);
