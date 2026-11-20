-- 旧记录可能同时承载多个来源，保留原有访问并标记为待确认。
ALTER TABLE acl_entry ADD COLUMN grant_source text NOT NULL DEFAULT 'LEGACY_UNKNOWN'
    CHECK (grant_source IN ('LEGACY_UNKNOWN', 'DIRECT', 'SPACE_ROLE'));
DROP INDEX acl_active_space_unique;
CREATE UNIQUE INDEX acl_active_space_source_unique
    ON acl_entry (space_id, principal_type, principal_id, grant_source)
    WHERE scope = 'SPACE' AND revoked_at IS NULL;
