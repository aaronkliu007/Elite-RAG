ALTER TABLE qa_model_call_audit
    ADD COLUMN provider text NOT NULL DEFAULT 'legacy-unknown'
        CHECK (provider ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    ADD COLUMN endpoint_identifier text NOT NULL DEFAULT 'legacy-unknown'
        CHECK (endpoint_identifier ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    ADD COLUMN model_name text NOT NULL DEFAULT 'legacy-unknown'
        CHECK (length(btrim(model_name)) BETWEEN 1 AND 200),
    ADD COLUMN model_config_version text NOT NULL DEFAULT 'legacy-unknown'
        CHECK (model_config_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$');

ALTER TABLE qa_model_call_audit
    ALTER COLUMN provider DROP DEFAULT,
    ALTER COLUMN endpoint_identifier DROP DEFAULT,
    ALTER COLUMN model_name DROP DEFAULT,
    ALTER COLUMN model_config_version DROP DEFAULT;
