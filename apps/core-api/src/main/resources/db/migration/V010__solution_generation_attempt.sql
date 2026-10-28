CREATE TABLE solution_generation_attempt (
    id uuid PRIMARY KEY,
    client_key text NOT NULL UNIQUE CHECK (length(btrim(client_key)) BETWEEN 1 AND 128),
    solution_id uuid NOT NULL REFERENCES solution(id),
    requirement_version_id uuid NOT NULL,
    outline_version_id uuid NOT NULL,
    section_id uuid NOT NULL,
    baseline_section_version_id uuid,
    input_fingerprint text NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    status text NOT NULL CHECK (status IN ('PENDING','RETRIEVING','GENERATING','VERIFYING',
        'SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED_RETRYABLE','FAILED_MANUAL','CANCELLED')),
    execution_count integer NOT NULL CHECK (execution_count > 0),
    revision bigint NOT NULL CHECK (revision >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL CHECK (updated_at >= created_at),
    retrieval_request_id uuid,
    evidence_ids uuid[] NOT NULL DEFAULT '{}',
    model_input_tokens bigint,
    model_output_tokens bigint,
    model_cost_micros bigint,
    model_latency_millis bigint,
    result_section_version_id uuid,
    failure_code text,
    retry_checkpoint text,
    FOREIGN KEY (solution_id, requirement_version_id)
        REFERENCES solution_requirement_version(solution_id, id),
    FOREIGN KEY (solution_id, outline_version_id)
        REFERENCES solution_outline_version(solution_id, id),
    CHECK ((model_input_tokens IS NULL) = (model_output_tokens IS NULL)),
    CHECK ((model_input_tokens IS NULL) = (model_cost_micros IS NULL)),
    CHECK ((model_input_tokens IS NULL) = (model_latency_millis IS NULL)),
    CHECK (model_input_tokens IS NULL OR
        (model_input_tokens >= 0 AND model_output_tokens >= 0 AND model_cost_micros >= 0 AND model_latency_millis >= 0)),
    CHECK (retry_checkpoint IS NULL OR status = 'FAILED_RETRYABLE')
);
CREATE INDEX solution_generation_attempt_solution_idx
    ON solution_generation_attempt (solution_id, updated_at DESC);

DO $$
DECLARE runtime_role text := current_setting('rag.runtime_role', true);
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN
        RAISE EXCEPTION 'rag.runtime_role must be configured before migration';
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON TABLE solution_generation_attempt TO %I', runtime_role);
END $$;
