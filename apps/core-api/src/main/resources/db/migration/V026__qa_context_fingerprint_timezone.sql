-- Stabilize JSON rendering of timestamptz across database sessions.
ALTER FUNCTION qa_document_context_fingerprint(uuid, uuid) SET TimeZone TO 'UTC';
