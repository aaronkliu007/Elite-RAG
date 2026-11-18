ALTER TABLE qa_model_call_audit
    ADD COLUMN qa_policy_version text NOT NULL DEFAULT 'legacy-unknown'
        CHECK (qa_policy_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$');

ALTER TABLE qa_model_call_audit ALTER COLUMN qa_policy_version DROP DEFAULT;
