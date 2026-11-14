-- V5: @mentions in comments, and meaningful sorting of tasks by priority and status.

-- Who a comment mentions. Rows disappear automatically with the comment or the user.
CREATE TABLE task_comment_mentions (
    comment_id BIGINT NOT NULL REFERENCES task_comments (id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    PRIMARY KEY (comment_id, user_id)
);

-- Powers the "comments that mention me" inbox
CREATE INDEX ix_task_comment_mentions_user ON task_comment_mentions (user_id);

-- Priority and status are stored as words ('LOW', 'URGENT'...), which sort alphabetically
-- (HIGH, LOW, MEDIUM, URGENT) and not by importance. These columns are calculated by PostgreSQL
-- from the word, so sorting by them gives the real order. They are read-only for the application.
ALTER TABLE tasks ADD COLUMN priority_rank INTEGER GENERATED ALWAYS AS (
    CASE priority WHEN 'LOW' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'HIGH' THEN 3 WHEN 'URGENT' THEN 4 ELSE 0 END
) STORED;

ALTER TABLE tasks ADD COLUMN status_rank INTEGER GENERATED ALWAYS AS (
    CASE status WHEN 'TODO' THEN 1 WHEN 'IN_PROGRESS' THEN 2 WHEN 'IN_REVIEW' THEN 3 WHEN 'DONE' THEN 4 ELSE 0 END
) STORED;
