ALTER TABLE qa_evaluation_run ADD COLUMN dataset_digest text
    CHECK (dataset_digest IS NULL OR dataset_digest ~ '^[0-9a-f]{64}$');
