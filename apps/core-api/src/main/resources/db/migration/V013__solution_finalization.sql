CREATE TABLE solution_risk_confirmation (
    id uuid PRIMARY KEY,
    solution_id uuid NOT NULL REFERENCES solution(id),
    section_version_id uuid NOT NULL,
    claim_id uuid NOT NULL,
    requirement_version_id uuid NOT NULL,
    outline_version_id uuid NOT NULL,
    claim_fingerprint char(64) NOT NULL CHECK (claim_fingerprint ~ '^[0-9a-f]{64}$'),
    confirmed_by uuid NOT NULL REFERENCES app_user(id),
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    confirmed_at timestamptz NOT NULL,
    UNIQUE (section_version_id, claim_id, claim_fingerprint),
    FOREIGN KEY (solution_id, section_version_id)
        REFERENCES solution_section_version(solution_id, id),
    FOREIGN KEY (section_version_id, claim_id)
        REFERENCES solution_section_claim(version_id, claim_id),
    FOREIGN KEY (solution_id, requirement_version_id)
        REFERENCES solution_requirement_version(solution_id, id),
    FOREIGN KEY (solution_id, outline_version_id)
        REFERENCES solution_outline_version(solution_id, id)
);
CREATE INDEX solution_risk_confirmation_solution_idx ON solution_risk_confirmation (solution_id, confirmed_at);

CREATE TABLE solution_risk_confirmation_evidence (
    confirmation_id uuid NOT NULL REFERENCES solution_risk_confirmation(id),
    evidence_id uuid NOT NULL,
    PRIMARY KEY (confirmation_id, evidence_id)
);

CREATE TABLE solution_finalized_version (
    id uuid PRIMARY KEY,
    solution_id uuid NOT NULL REFERENCES solution(id),
    solution_revision bigint NOT NULL CHECK (solution_revision > 0),
    requirement_version_id uuid NOT NULL,
    outline_version_id uuid NOT NULL,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL,
    content_hash char(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE (solution_id, id),
    UNIQUE (solution_id, solution_revision),
    FOREIGN KEY (solution_id, requirement_version_id)
        REFERENCES solution_requirement_version(solution_id, id),
    FOREIGN KEY (solution_id, outline_version_id)
        REFERENCES solution_outline_version(solution_id, id)
);
ALTER TABLE solution ADD CONSTRAINT solution_finalized_version_fk
    FOREIGN KEY (id, finalized_version_id)
    REFERENCES solution_finalized_version(solution_id, id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE solution_finalized_section (
    finalized_version_id uuid NOT NULL REFERENCES solution_finalized_version(id),
    section_order integer NOT NULL CHECK (section_order > 0),
    section_version_id uuid NOT NULL REFERENCES solution_section_version(id),
    PRIMARY KEY (finalized_version_id, section_order),
    UNIQUE (finalized_version_id, section_version_id)
);
CREATE TABLE solution_finalized_risk_confirmation (
    finalized_version_id uuid NOT NULL REFERENCES solution_finalized_version(id),
    confirmation_id uuid NOT NULL REFERENCES solution_risk_confirmation(id),
    PRIMARY KEY (finalized_version_id, confirmation_id)
);

DO $$
DECLARE runtime_role text := current_setting('rag.runtime_role', true);
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN
        RAISE EXCEPTION 'rag.runtime_role must be configured before migration';
    END IF;
    EXECUTE format('GRANT SELECT, INSERT ON TABLE solution_risk_confirmation, solution_risk_confirmation_evidence, solution_finalized_version, solution_finalized_section, solution_finalized_risk_confirmation TO %I', runtime_role);
END $$;
