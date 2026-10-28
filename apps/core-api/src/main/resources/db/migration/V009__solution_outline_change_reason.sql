ALTER TABLE solution_outline_version ADD COLUMN change_reason text;
UPDATE solution_outline_version SET change_reason = '历史版本，原因未记录';
ALTER TABLE solution_outline_version ALTER COLUMN change_reason SET NOT NULL;
ALTER TABLE solution_outline_version ADD CONSTRAINT solution_outline_change_reason_nonblank
    CHECK (length(btrim(change_reason)) > 0);
