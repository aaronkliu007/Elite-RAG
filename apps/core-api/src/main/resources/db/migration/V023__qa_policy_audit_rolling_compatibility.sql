-- 旧版本应用尚未提供策略版本时，保留明确的未知标记以支持滚动升级。
ALTER TABLE qa_model_call_audit ALTER COLUMN qa_policy_version SET DEFAULT 'legacy-unknown';
