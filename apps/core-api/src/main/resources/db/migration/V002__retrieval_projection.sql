CREATE TABLE document_retrieval_metadata (
    document_id uuid PRIMARY KEY REFERENCES document(id) ON DELETE CASCADE,
    industry text,
    product text,
    solution_type text,
    year integer CHECK (year IS NULL OR year > 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_retrieval_metadata_nonblank CHECK (
        (industry IS NULL OR length(btrim(industry)) > 0)
        AND (product IS NULL OR length(btrim(product)) > 0)
        AND (solution_type IS NULL OR length(btrim(solution_type)) > 0))
);

CREATE TABLE retrieval_projection_event (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    event_type text NOT NULL DEFAULT 'REFRESH_SCOPE'
        CHECK (event_type = 'REFRESH_SCOPE'),
    scope_type text NOT NULL CHECK (scope_type IN ('DOCUMENT', 'SPACE')),
    space_id uuid NOT NULL REFERENCES knowledge_space(id),
    document_id uuid,
    version_id uuid,
    document_revision bigint CHECK (document_revision IS NULL OR document_revision >= 0),
    progress_document_id uuid,
    status text NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'LEASED', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL,
    lease_token uuid,
    lease_worker text,
    lease_expires_at timestamptz,
    error_code text CHECK (error_code IS NULL OR (length(error_code) BETWEEN 1 AND 100)),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT retrieval_projection_scope_consistent CHECK (
        (scope_type = 'DOCUMENT' AND document_id IS NOT NULL AND progress_document_id IS NULL)
        OR (scope_type = 'SPACE' AND document_id IS NULL AND version_id IS NULL
            AND document_revision IS NULL)),
    CONSTRAINT retrieval_projection_lease_consistent CHECK (
        (status = 'LEASED' AND lease_token IS NOT NULL AND lease_worker IS NOT NULL
            AND length(btrim(lease_worker)) BETWEEN 1 AND 255 AND lease_expires_at IS NOT NULL)
        OR (status <> 'LEASED' AND lease_token IS NULL AND lease_worker IS NULL
            AND lease_expires_at IS NULL)),
    FOREIGN KEY (space_id, document_id) REFERENCES document(space_id, id),
    FOREIGN KEY (document_id, version_id) REFERENCES document_version(document_id, id)
);

CREATE INDEX retrieval_projection_claim_idx
    ON retrieval_projection_event (status, next_attempt_at, sequence)
    WHERE status IN ('PENDING', 'RETRY_WAIT');
CREATE INDEX retrieval_projection_expired_lease_idx
    ON retrieval_projection_event (lease_expires_at)
    WHERE status = 'LEASED';

DO $$
DECLARE
    runtime_role text := current_setting('rag.runtime_role', true);
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN
        RAISE EXCEPTION 'rag.runtime_role must be configured before migration';
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE document_retrieval_metadata TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE retrieval_projection_event TO %I', runtime_role);
    EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE retrieval_projection_event_sequence_seq TO %I', runtime_role);
END $$;
