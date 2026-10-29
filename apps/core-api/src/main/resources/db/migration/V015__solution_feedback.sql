CREATE TABLE solution_feedback (
    id uuid PRIMARY KEY,
    solution_id uuid NOT NULL REFERENCES solution(id),
    finalized_version_id uuid NOT NULL,
    section_version_id uuid,
    actor_id uuid NOT NULL REFERENCES app_user(id),
    reason varchar(32) NOT NULL CHECK (reason IN ('ACCEPTED', 'FACT_ERROR', 'MISSING_EVIDENCE',
        'IRRELEVANT', 'OUTDATED', 'STYLE', 'DUPLICATE', 'SCOPE_MISMATCH', 'OTHER')),
    client_key varchar(200) NOT NULL CHECK (length(btrim(client_key)) > 0),
    created_at timestamptz NOT NULL,
    UNIQUE (actor_id, solution_id, client_key),
    FOREIGN KEY (solution_id, finalized_version_id)
        REFERENCES solution_finalized_version(solution_id, id),
    FOREIGN KEY (finalized_version_id, section_version_id)
        REFERENCES solution_finalized_section(finalized_version_id, section_version_id)
);
CREATE INDEX solution_feedback_version_idx ON solution_feedback (finalized_version_id, created_at DESC);

DO $$
DECLARE runtime_role text := current_setting('rag.runtime_role', true);
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN
        RAISE EXCEPTION 'rag.runtime_role must be configured before migration';
    END IF;
    EXECUTE format('GRANT SELECT, INSERT ON TABLE solution_feedback TO %I', runtime_role);
END $$;
