CREATE TABLE qa_conflict_screening_snapshot (
    request_id uuid NOT NULL REFERENCES qa_request(id) ON DELETE CASCADE,
    space_id uuid NOT NULL,
    document_id uuid NOT NULL,
    version_id uuid NOT NULL,
    node_id text NOT NULL CHECK (length(btrim(node_id)) BETWEEN 1 AND 200),
    PRIMARY KEY (request_id, space_id, document_id, version_id, node_id),
    FOREIGN KEY (space_id, document_id) REFERENCES document(space_id, id),
    FOREIGN KEY (document_id, version_id) REFERENCES document_version(document_id, id),
    FOREIGN KEY (version_id, node_id) REFERENCES canonical_node(version_id, node_id)
);

CREATE INDEX qa_conflict_screening_document_idx ON qa_conflict_screening_snapshot(document_id, version_id, node_id);

DO $$
DECLARE runtime_role text := current_setting('rag.runtime_role', true);
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN
        RAISE EXCEPTION 'rag.runtime_role must be configured';
    END IF;
    EXECUTE format('GRANT SELECT, INSERT ON TABLE qa_conflict_screening_snapshot TO %I', runtime_role);
END $$;
