-- V3: projects, tasks, typed task links and file attachments.

CREATE TABLE projects (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    description VARCHAR(2000),
    manager_id  BIGINT        NOT NULL REFERENCES users (id),
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE project_members (
    project_id BIGINT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    PRIMARY KEY (project_id, user_id)
);

CREATE TABLE tasks (
    id            BIGSERIAL PRIMARY KEY,
    project_id    BIGINT       NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    title         VARCHAR(200) NOT NULL,
    description   VARCHAR(2000),
    status        VARCHAR(20)  NOT NULL DEFAULT 'TODO',
    priority      VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM',
    due_date      DATE,
    assignee_id   BIGINT       REFERENCES users (id) ON DELETE SET NULL,
    created_by_id BIGINT       NOT NULL REFERENCES users (id),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_tasks_status   CHECK (status   IN ('TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE')),
    CONSTRAINT ck_tasks_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT'))
);

CREATE INDEX ix_tasks_project  ON tasks (project_id);
CREATE INDEX ix_tasks_assignee ON tasks (assignee_id);

CREATE TABLE task_links (
    id             BIGSERIAL PRIMARY KEY,
    source_task_id BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    target_task_id BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    link_type      VARCHAR(20) NOT NULL,
    CONSTRAINT ck_task_links_type     CHECK (link_type IN ('BLOCKS', 'RELATES_TO', 'DUPLICATES')),
    CONSTRAINT ck_task_links_not_self CHECK (source_task_id <> target_task_id),
    CONSTRAINT uq_task_links          UNIQUE (source_task_id, target_task_id, link_type)
);

CREATE INDEX ix_task_links_target ON task_links (target_task_id);

CREATE TABLE attachments (
    id                BIGSERIAL PRIMARY KEY,
    task_id           BIGINT       NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    uploaded_by_id    BIGINT       NOT NULL REFERENCES users (id),
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100),
    size_bytes        BIGINT       NOT NULL,
    storage_key       VARCHAR(64)  NOT NULL UNIQUE,
    uploaded_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_attachments_task ON attachments (task_id);
