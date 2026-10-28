CREATE TABLE qa_actor_rate_limit (
    actor_id uuid PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    window_started_at timestamptz NOT NULL,
    request_count integer NOT NULL CHECK (request_count > 0),
    updated_at timestamptz NOT NULL,
    CHECK (updated_at >= window_started_at)
);

DO $$
DECLARE
    runtime_role text := current_setting('rag.runtime_role', true);
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN
        RAISE EXCEPTION 'rag.runtime_role must be configured before migration';
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON TABLE qa_actor_rate_limit TO %I', runtime_role);
END $$;
