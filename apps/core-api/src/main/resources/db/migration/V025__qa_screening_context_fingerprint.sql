ALTER TABLE qa_conflict_screening_snapshot ADD COLUMN context_fingerprint text
    CHECK (context_fingerprint IS NULL OR context_fingerprint ~ '^[0-9a-f]{64}$');

CREATE FUNCTION qa_document_context_fingerprint(p_document uuid, p_version uuid) RETURNS text
LANGUAGE sql STABLE AS $$
    SELECT encode(sha256(convert_to(jsonb_build_array(d.title,to_jsonb(c),
        (SELECT COALESCE(jsonb_agg(to_jsonb(n) ORDER BY n.stable_order), '[]'::jsonb)
         FROM canonical_node n WHERE n.version_id=p_version))::text, 'UTF8')), 'hex')
    FROM document d JOIN canonical_document c ON c.version_id=p_version
    WHERE d.id=p_document AND d.current_published_version_id=p_version AND NOT d.archived
$$;

REVOKE ALL ON FUNCTION qa_document_context_fingerprint(uuid, uuid) FROM PUBLIC;
DO $$
DECLARE runtime_role text := current_setting('rag.runtime_role', true);
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN RAISE EXCEPTION 'rag.runtime_role must be configured'; END IF;
    EXECUTE format('GRANT EXECUTE ON FUNCTION qa_document_context_fingerprint(uuid, uuid) TO %I', runtime_role);
END $$;
