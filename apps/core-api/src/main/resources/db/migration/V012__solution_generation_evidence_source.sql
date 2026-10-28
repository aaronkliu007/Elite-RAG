CREATE TABLE solution_generation_evidence_source (
    attempt_id uuid NOT NULL REFERENCES solution_generation_attempt(id),
    evidence_id uuid NOT NULL,
    evidence_order integer NOT NULL CHECK (evidence_order > 0),
    space_id uuid NOT NULL,
    document_id uuid NOT NULL,
    version_id uuid NOT NULL,
    node_id text NOT NULL CHECK (length(btrim(node_id)) > 0 AND length(node_id) <= 200),
    excerpt_hash char(64) NOT NULL CHECK (excerpt_hash ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (attempt_id, evidence_id),
    UNIQUE (attempt_id, evidence_order)
);

DO $$
DECLARE runtime_role text := current_setting('rag.runtime_role', true);
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN
        RAISE EXCEPTION 'rag.runtime_role must be configured before migration';
    END IF;
    EXECUTE format('GRANT SELECT, INSERT ON TABLE solution_generation_evidence_source TO %I', runtime_role);
END $$;
