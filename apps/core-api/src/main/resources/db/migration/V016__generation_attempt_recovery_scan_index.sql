CREATE INDEX solution_generation_attempt_recovery_idx
    ON solution_generation_attempt (updated_at, id)
    WHERE status IN ('PENDING', 'RETRIEVING', 'GENERATING');
