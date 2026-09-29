CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    external_id text NOT NULL UNIQUE,
    display_name text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    session_version bigint NOT NULL DEFAULT 0 CHECK (session_version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_user_external_id_nonempty CHECK (length(btrim(external_id)) > 0)
);

CREATE TABLE org_unit (
    id uuid PRIMARY KEY,
    external_id text NOT NULL UNIQUE,
    parent_id uuid REFERENCES org_unit(id),
    name text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT org_unit_external_id_nonempty CHECK (length(btrim(external_id)) > 0),
    CONSTRAINT org_unit_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE TABLE org_membership (
    user_id uuid NOT NULL REFERENCES app_user(id),
    org_unit_id uuid NOT NULL REFERENCES org_unit(id),
    snapshot_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, org_unit_id)
);
CREATE INDEX org_membership_org_unit_idx ON org_membership (org_unit_id, user_id);

CREATE TABLE knowledge_space (
    id uuid PRIMARY KEY,
    name text NOT NULL,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE document (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES knowledge_space(id),
    title text NOT NULL,
    current_published_version_id uuid,
    archived boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (space_id, id)
);
CREATE INDEX document_space_idx ON document (space_id, archived);

CREATE TABLE document_version (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES document(id),
    version_number integer NOT NULL CHECK (version_number > 0),
    source_blob_id text NOT NULL CHECK (length(btrim(source_blob_id)) > 0),
    status text NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'ARCHIVED')),
    parse_status text NOT NULL DEFAULT 'PENDING'
        CHECK (parse_status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    status_changed_by uuid REFERENCES app_user(id),
    status_changed_at timestamptz,
    UNIQUE (document_id, version_number),
    UNIQUE (document_id, id)
);
ALTER TABLE document ADD CONSTRAINT document_current_version_same_document_fk
    FOREIGN KEY (id, current_published_version_id)
    REFERENCES document_version(document_id, id);

CREATE TABLE active_blob_reservation (
    space_id uuid NOT NULL,
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    document_id uuid NOT NULL,
    version_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, sha256),
    FOREIGN KEY (space_id, document_id) REFERENCES document(space_id, id),
    FOREIGN KEY (document_id, version_id) REFERENCES document_version(document_id, id),
    UNIQUE (version_id)
);

CREATE TABLE acl_entry (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES knowledge_space(id),
    document_id uuid,
    scope text NOT NULL CHECK (scope IN ('SPACE', 'DOCUMENT')),
    principal_type text NOT NULL CHECK (principal_type IN ('USER', 'DEPARTMENT', 'ROLE', 'PROJECT_GROUP')),
    principal_id uuid NOT NULL,
    expires_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT acl_scope_consistent CHECK ((scope = 'SPACE' AND document_id IS NULL)
        OR (scope = 'DOCUMENT' AND document_id IS NOT NULL)),
    FOREIGN KEY (space_id, document_id) REFERENCES document(space_id, id)
);
CREATE UNIQUE INDEX acl_active_space_unique ON acl_entry (space_id, principal_type, principal_id)
    WHERE scope = 'SPACE' AND revoked_at IS NULL;
CREATE UNIQUE INDEX acl_active_document_unique ON acl_entry (document_id, principal_type, principal_id)
    WHERE scope = 'DOCUMENT' AND revoked_at IS NULL;
CREATE INDEX acl_principal_lookup_idx ON acl_entry (principal_type, principal_id)
    WHERE revoked_at IS NULL;

CREATE TABLE ingestion_job (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL,
    version_id uuid NOT NULL,
    requested_by uuid NOT NULL REFERENCES app_user(id),
    source_blob_id text NOT NULL,
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    source_filename text NOT NULL,
    media_type text NOT NULL,
    byte_size bigint NOT NULL CHECK (byte_size >= 0),
    status text NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'LEASED', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_token uuid,
    lease_worker text,
    lease_expires_at timestamptz,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (document_id, version_id) REFERENCES document_version(document_id, id),
    CONSTRAINT ingestion_lease_consistent CHECK
        ((status = 'LEASED' AND lease_token IS NOT NULL AND lease_worker IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'LEASED' AND lease_token IS NULL AND lease_worker IS NULL AND lease_expires_at IS NULL))
);
CREATE INDEX ingestion_job_claim_idx ON ingestion_job (status, next_attempt_at)
    WHERE status IN ('QUEUED', 'RETRY_WAIT');
CREATE INDEX ingestion_job_expired_lease_idx ON ingestion_job (lease_expires_at)
    WHERE status = 'LEASED';

CREATE TABLE canonical_document (
    version_id uuid PRIMARY KEY REFERENCES document_version(id),
    source_filename text NOT NULL,
    media_type text NOT NULL,
    page_count integer NOT NULL CHECK (page_count >= 0),
    parsed_at timestamptz NOT NULL
);

CREATE TABLE canonical_node (
    version_id uuid NOT NULL REFERENCES canonical_document(version_id) ON DELETE CASCADE,
    node_id text NOT NULL,
    kind text NOT NULL,
    body_text text NOT NULL,
    parent_node_id text,
    page integer CHECK (page IS NULL OR page >= 1),
    bounding_box jsonb,
    table_cells jsonb,
    confidence double precision CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    stable_order integer NOT NULL CHECK (stable_order >= 0),
    PRIMARY KEY (version_id, node_id),
    UNIQUE (version_id, stable_order)
);
CREATE INDEX canonical_node_version_order_idx ON canonical_node (version_id, stable_order);

CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    action text NOT NULL,
    resource_type text NOT NULL,
    resource_id uuid NOT NULL,
    result text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_event_resource_idx ON audit_event (resource_type, resource_id, occurred_at);

CREATE TABLE blob_cleanup_candidate (
    id uuid PRIMARY KEY,
    bucket text NOT NULL,
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    object_key text NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT now(),
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DEFERRED', 'DELETED', 'FAILED')),
    last_checked_at timestamptz,
    UNIQUE (bucket, object_key)
);

-- The init script records the runtime role on this database before Flyway connects.
-- Grant only application tables and transaction-local TEMPORARY storage;
-- never grant runtime access to Flyway history or permanent schema DDL.
DO $$
DECLARE
    runtime_role text := current_setting('rag.runtime_role', true);
    writable_table text;
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN
        RAISE EXCEPTION 'rag.runtime_role must be configured before migration';
    END IF;
    FOREACH writable_table IN ARRAY ARRAY[
        'app_user', 'org_unit', 'org_membership', 'knowledge_space', 'document',
        'document_version', 'active_blob_reservation', 'acl_entry', 'ingestion_job',
        'canonical_document', 'canonical_node', 'blob_cleanup_candidate'
    ] LOOP
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE %I TO %I', writable_table, runtime_role);
    END LOOP;
    EXECUTE format('GRANT SELECT, INSERT ON TABLE audit_event TO %I', runtime_role);
    EXECUTE format('GRANT TEMPORARY ON DATABASE %I TO %I', current_database(), runtime_role);
END $$;
