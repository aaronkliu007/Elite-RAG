ALTER TABLE qa_evidence_snapshot DROP CONSTRAINT qa_evidence_snapshot_locator_type_check;
ALTER TABLE qa_evidence_snapshot ADD CONSTRAINT qa_evidence_snapshot_locator_type_check CHECK (locator_type IN ('PAGE','SLIDE','TABLE','MARKDOWN','SECTION'));
