ALTER TABLE solution_model_proposal_attempt DROP CONSTRAINT solution_model_proposal_attempt_kind_check;
ALTER TABLE solution_model_proposal_attempt ADD CONSTRAINT solution_model_proposal_attempt_kind_check
    CHECK (kind IN ('REQUIREMENTS', 'OUTLINE', 'DOCUMENT_REQ'));

CREATE TABLE solution_proposal_snapshot (
    attempt_id uuid PRIMARY KEY REFERENCES solution_model_proposal_attempt(id),
    space_id uuid NOT NULL REFERENCES knowledge_space(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE solution_proposal_document_snapshot (
    attempt_id uuid NOT NULL REFERENCES solution_proposal_snapshot(attempt_id),
    document_order integer NOT NULL CHECK (document_order BETWEEN 1 AND 10),
    space_id uuid NOT NULL REFERENCES knowledge_space(id),
    document_id uuid NOT NULL,
    version_id uuid NOT NULL,
    PRIMARY KEY (attempt_id, document_order),
    UNIQUE (attempt_id, document_id, version_id),
    FOREIGN KEY (space_id, document_id) REFERENCES document(space_id, id),
    FOREIGN KEY (document_id, version_id) REFERENCES document_version(document_id, id)
);
CREATE TABLE solution_proposal_evidence_snapshot (
    attempt_id uuid NOT NULL REFERENCES solution_proposal_snapshot(attempt_id),
    evidence_order integer NOT NULL CHECK (evidence_order BETWEEN 1 AND 15),
    evidence_id uuid NOT NULL,
    document_id uuid NOT NULL,
    version_id uuid NOT NULL,
    node_id text NOT NULL CHECK (length(btrim(node_id)) > 0 AND length(node_id) <= 200),
    locator jsonb NOT NULL CHECK (jsonb_typeof(locator) = 'object'),
    excerpt_hash char(64) NOT NULL CHECK (excerpt_hash ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (attempt_id, evidence_order),
    UNIQUE (attempt_id, evidence_id),
    FOREIGN KEY (attempt_id, document_id, version_id)
        REFERENCES solution_proposal_document_snapshot(attempt_id, document_id, version_id)
);
DO $$
DECLARE runtime_role text := current_setting('rag.runtime_role', true);
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN RAISE EXCEPTION 'rag.runtime_role must be configured before migration'; END IF;
    EXECUTE format('GRANT SELECT, INSERT ON TABLE solution_proposal_snapshot, solution_proposal_document_snapshot, solution_proposal_evidence_snapshot TO %I', runtime_role);
END $$;
