CREATE TABLE solution_model_proposal_attempt (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES app_user(id),
    solution_id uuid NOT NULL REFERENCES solution(id),
    kind varchar(16) NOT NULL CHECK (kind IN ('REQUIREMENTS', 'OUTLINE')),
    client_key varchar(200) NOT NULL CHECK (length(btrim(client_key)) > 0),
    requirement_version_id uuid NOT NULL,
    expected_revision bigint NOT NULL CHECK (expected_revision >= 0),
    input_fingerprint char(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    status varchar(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'INDETERMINATE')),
    result_json jsonb CHECK (result_json IS NULL OR jsonb_typeof(result_json) = 'object'),
    failure_code varchar(64) CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z0-9_]+$'),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL CHECK (updated_at >= created_at),
    lease_until timestamptz NOT NULL,
    UNIQUE (actor_id, solution_id, kind, client_key),
    FOREIGN KEY (solution_id, requirement_version_id)
        REFERENCES solution_requirement_version(solution_id, id),
    CHECK ((status = 'SUCCEEDED') = (result_json IS NOT NULL)),
    CHECK ((status IN ('FAILED', 'INDETERMINATE')) = (failure_code IS NOT NULL))
);
CREATE INDEX solution_model_proposal_recovery_idx
    ON solution_model_proposal_attempt (lease_until, id) WHERE status = 'RUNNING';

DO $$
DECLARE runtime_role text := current_setting('rag.runtime_role', true);
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN
        RAISE EXCEPTION 'rag.runtime_role must be configured before migration';
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON TABLE solution_model_proposal_attempt TO %I', runtime_role);
END $$;
