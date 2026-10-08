ALTER TABLE knowledge_space
    ADD COLUMN description text NOT NULL DEFAULT '',
    ADD COLUMN revision bigint NOT NULL DEFAULT 0,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD CONSTRAINT knowledge_space_description_length CHECK (length(description) <= 2000),
    ADD CONSTRAINT knowledge_space_revision_nonnegative CHECK (revision >= 0);

ALTER TABLE document
    ADD COLUMN classification text,
    ADD COLUMN tags text[] NOT NULL DEFAULT '{}'::text[],
    ADD CONSTRAINT document_classification_valid CHECK (
        classification IS NULL OR length(btrim(classification)) BETWEEN 1 AND 100),
    ADD CONSTRAINT document_tags_valid CHECK (
        cardinality(tags) <= 20 AND array_position(tags, NULL) IS NULL);

CREATE TABLE external_identity (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    provider text NOT NULL,
    subject text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT external_identity_provider_valid CHECK (
        length(btrim(provider)) BETWEEN 1 AND 50 AND provider = upper(btrim(provider))),
    CONSTRAINT external_identity_subject_valid CHECK (length(btrim(subject)) BETWEEN 1 AND 255),
    CONSTRAINT external_identity_provider_subject_unique UNIQUE (provider, subject),
    CONSTRAINT external_identity_user_provider_unique UNIQUE (user_id, provider)
);

CREATE TABLE local_credential (
    user_id uuid PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    username_normalized text NOT NULL UNIQUE,
    password_hash text NOT NULL,
    failed_attempts integer NOT NULL DEFAULT 0,
    failure_window_started_at timestamptz,
    locked_until timestamptz,
    password_changed_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT local_credential_username_valid CHECK (
        length(username_normalized) BETWEEN 1 AND 255
        AND username_normalized = lower(btrim(username_normalized))),
    CONSTRAINT local_credential_password_hash_nonempty CHECK (length(btrim(password_hash)) > 0),
    CONSTRAINT local_credential_failures_valid CHECK (
        failed_attempts >= 0
        AND ((failed_attempts = 0 AND failure_window_started_at IS NULL)
            OR (failed_attempts > 0 AND failure_window_started_at IS NOT NULL)))
);

CREATE TABLE login_source_attempt (
    source_hash text PRIMARY KEY,
    failed_attempts integer NOT NULL DEFAULT 0,
    window_started_at timestamptz NOT NULL,
    blocked_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT login_source_hash_valid CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT login_source_failures_nonnegative CHECK (failed_attempts >= 0)
);
CREATE INDEX login_source_attempt_expiry_idx
    ON login_source_attempt (blocked_until, window_started_at);

CREATE TABLE system_role_assignment (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role text NOT NULL CHECK (role = 'SYSTEM_ADMIN'),
    granted_by uuid NOT NULL REFERENCES app_user(id),
    granted_at timestamptz NOT NULL DEFAULT now(),
    revoked_by uuid REFERENCES app_user(id),
    revoked_at timestamptz,
    CONSTRAINT system_role_revocation_consistent CHECK (
        (revoked_at IS NULL AND revoked_by IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL))
);
CREATE UNIQUE INDEX system_role_active_unique
    ON system_role_assignment (user_id, role)
    WHERE revoked_at IS NULL;

CREATE TABLE space_role_assignment (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES knowledge_space(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role text NOT NULL CHECK (role IN ('SPACE_ADMIN', 'EDITOR', 'REVIEWER_PUBLISHER', 'READER')),
    granted_by uuid NOT NULL REFERENCES app_user(id),
    granted_at timestamptz NOT NULL DEFAULT now(),
    revoked_by uuid REFERENCES app_user(id),
    revoked_at timestamptz,
    CONSTRAINT space_role_revocation_consistent CHECK (
        (revoked_at IS NULL AND revoked_by IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL))
);
CREATE UNIQUE INDEX space_role_active_unique
    ON space_role_assignment (space_id, user_id, role)
    WHERE revoked_at IS NULL;
CREATE INDEX space_role_user_lookup_idx
    ON space_role_assignment (user_id, space_id)
    WHERE revoked_at IS NULL;

CREATE TABLE idempotency_record (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    operation text NOT NULL,
    idempotency_key text NOT NULL,
    request_hash text NOT NULL,
    status text NOT NULL DEFAULT 'IN_PROGRESS'
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    http_status integer,
    response_body jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CONSTRAINT idempotency_operation_valid CHECK (length(btrim(operation)) BETWEEN 1 AND 100),
    CONSTRAINT idempotency_key_valid CHECK (
        length(idempotency_key) BETWEEN 1 AND 128 AND idempotency_key ~ '^[ -~]+$'),
    CONSTRAINT idempotency_request_hash_valid CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotency_expiry_valid CHECK (expires_at > created_at),
    CONSTRAINT idempotency_result_consistent CHECK (
        (status = 'IN_PROGRESS' AND http_status IS NULL AND response_body IS NULL)
        OR (status = 'COMPLETED' AND http_status BETWEEN 100 AND 599 AND response_body IS NOT NULL)),
    CONSTRAINT idempotency_scope_unique UNIQUE (actor_id, operation, idempotency_key)
);
CREATE INDEX idempotency_record_expiry_idx ON idempotency_record (expires_at);

CREATE TABLE document_review (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL,
    version_id uuid NOT NULL,
    reviewer_id uuid NOT NULL REFERENCES app_user(id),
    decision text NOT NULL CHECK (decision IN ('REJECTED', 'PUBLISHED')),
    comment text,
    reviewed_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (document_id, version_id) REFERENCES document_version(document_id, id),
    CONSTRAINT document_review_comment_valid CHECK (
        (decision = 'REJECTED' AND length(btrim(comment)) BETWEEN 1 AND 1000)
        OR (decision = 'PUBLISHED' AND (comment IS NULL OR length(btrim(comment)) BETWEEN 1 AND 1000)))
);
CREATE INDEX document_review_version_idx
    ON document_review (document_id, version_id, reviewed_at);

DO $$
DECLARE
    runtime_role text := current_setting('rag.runtime_role', true);
    writable_table text;
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN
        RAISE EXCEPTION 'rag.runtime_role must be configured before migration';
    END IF;
    FOREACH writable_table IN ARRAY ARRAY[
        'external_identity', 'local_credential', 'login_source_attempt',
        'system_role_assignment', 'space_role_assignment', 'idempotency_record'
    ] LOOP
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE %I TO %I', writable_table, runtime_role);
    END LOOP;
    EXECUTE format('GRANT SELECT, INSERT ON TABLE document_review TO %I', runtime_role);
END $$;
