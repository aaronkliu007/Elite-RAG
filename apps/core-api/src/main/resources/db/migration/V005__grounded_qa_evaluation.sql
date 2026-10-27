CREATE TABLE qa_evaluation_run (
    id uuid PRIMARY KEY,
    dataset_id uuid NOT NULL,
    dataset_version text NOT NULL CHECK (dataset_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    code_version text NOT NULL CHECK (code_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    policy_version text NOT NULL CHECK (policy_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    prompt_version text NOT NULL CHECK (prompt_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    sanitization_version text NOT NULL CHECK (sanitization_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    model_config_version text NOT NULL CHECK (model_config_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    started_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL,
    passed boolean NOT NULL,
    metrics jsonb NOT NULL CHECK (jsonb_typeof(metrics) = 'object'),
    assessment jsonb NOT NULL CHECK (jsonb_typeof(assessment) = 'object'),
    CHECK (completed_at >= started_at)
);
CREATE INDEX qa_evaluation_run_started_idx ON qa_evaluation_run (started_at DESC,id);

DO $$
DECLARE
    runtime_role text := current_setting('rag.runtime_role', true);
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN
        RAISE EXCEPTION 'rag.runtime_role must be configured before migration';
    END IF;
    EXECUTE format('GRANT SELECT, INSERT ON TABLE qa_evaluation_run TO %I', runtime_role);
END $$;
