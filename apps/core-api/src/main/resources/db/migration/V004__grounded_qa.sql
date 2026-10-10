CREATE TABLE qa_request (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES app_user(id),
    question_digest text NOT NULL CHECK (question_digest ~ '^[0-9a-f]{64}$'),
    status text NOT NULL CHECK (status IN (
        'RECEIVED','RETRIEVING','SANITIZING','GENERATING','VERIFYING','ANSWERED','REFUSED','FAILED')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    degraded boolean NOT NULL DEFAULT false,
    refusal_reason text CHECK (refusal_reason IN (
        'NO_AUTHORIZED_SPACE','INSUFFICIENT_EVIDENCE','CONFLICTING_EVIDENCE',
        'UNSAFE_TO_SEND','INVALID_MODEL_OUTPUT','UNSUPPORTED_CLAIM')),
    failure_code text CHECK (failure_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT qa_request_terminal_fields CHECK (
        (status IN ('RECEIVED','RETRIEVING','SANITIZING','GENERATING','VERIFYING')
            AND refusal_reason IS NULL AND failure_code IS NULL AND completed_at IS NULL)
        OR (status = 'ANSWERED' AND refusal_reason IS NULL AND failure_code IS NULL AND completed_at IS NOT NULL)
        OR (status = 'REFUSED' AND refusal_reason IS NOT NULL AND failure_code IS NULL AND completed_at IS NOT NULL)
        OR (status = 'FAILED' AND refusal_reason IS NULL AND failure_code IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX qa_request_actor_created_idx ON qa_request (actor_id, created_at DESC, id);

CREATE TABLE qa_evidence_snapshot (
    request_id uuid NOT NULL REFERENCES qa_request(id) ON DELETE CASCADE,
    evidence_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    space_id uuid NOT NULL,
    document_id uuid NOT NULL,
    version_id uuid NOT NULL,
    node_id text NOT NULL CHECK (length(btrim(node_id)) BETWEEN 1 AND 200),
    content_digest text NOT NULL CHECK (content_digest ~ '^[0-9a-f]{64}$'),
    title text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 500),
    score double precision NOT NULL CHECK (score > '-Infinity'::double precision
        AND score < 'Infinity'::double precision),
    locator_type text NOT NULL CHECK (locator_type IN ('PAGE','SLIDE','TABLE','MARKDOWN')),
    locator jsonb NOT NULL CHECK (jsonb_typeof(locator) = 'object'),
    PRIMARY KEY (request_id, evidence_id),
    UNIQUE (request_id, ordinal),
    FOREIGN KEY (space_id, document_id) REFERENCES document(space_id, id),
    FOREIGN KEY (document_id, version_id) REFERENCES document_version(document_id, id),
    FOREIGN KEY (version_id, node_id) REFERENCES canonical_node(version_id, node_id)
);
CREATE INDEX qa_evidence_document_idx ON qa_evidence_snapshot (document_id, version_id, node_id);

CREATE TABLE qa_answer (
    request_id uuid PRIMARY KEY REFERENCES qa_request(id) ON DELETE CASCADE,
    answer_text text NOT NULL CHECK (length(btrim(answer_text)) BETWEEN 1 AND 2000),
    claims_schema_version smallint NOT NULL CHECK (claims_schema_version = 1),
    claims jsonb NOT NULL CHECK (jsonb_typeof(claims) = 'array'),
    cited_evidence_ids uuid[] NOT NULL CHECK (
        cardinality(cited_evidence_ids) BETWEEN 1 AND 225
        AND array_position(cited_evidence_ids, NULL) IS NULL),
    created_at timestamptz NOT NULL
);

CREATE TABLE qa_model_call_audit (
    request_id uuid PRIMARY KEY REFERENCES qa_request(id) ON DELETE CASCADE,
    request_digest text NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    response_digest text NOT NULL CHECK (response_digest ~ '^[0-9a-f]{64}$'),
    input_tokens integer NOT NULL CHECK (input_tokens >= 0),
    output_tokens integer NOT NULL CHECK (output_tokens >= 0),
    latency_ms bigint NOT NULL CHECK (latency_ms >= 0),
    sanitization_hit_count integer NOT NULL CHECK (sanitization_hit_count >= 0),
    retry_count integer NOT NULL CHECK (retry_count BETWEEN 0 AND 1),
    result_code text NOT NULL CHECK (result_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    occurred_at timestamptz NOT NULL
);
CREATE INDEX qa_model_call_occurred_idx ON qa_model_call_audit (occurred_at DESC, request_id);

DO $$
DECLARE
    runtime_role text := current_setting('rag.runtime_role', true);
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN
        RAISE EXCEPTION 'rag.runtime_role must be configured before migration';
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON TABLE qa_request TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT ON TABLE qa_evidence_snapshot TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT ON TABLE qa_answer TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT ON TABLE qa_model_call_audit TO %I', runtime_role);
END $$;
