ALTER TABLE qa_evaluation_run ADD COLUMN case_results jsonb NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(case_results) = 'array');
