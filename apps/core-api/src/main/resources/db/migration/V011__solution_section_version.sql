ALTER TABLE solution_generation_attempt
    ADD CONSTRAINT solution_generation_attempt_solution_id_unique UNIQUE (solution_id, id);

CREATE TABLE solution_section_version (
    id uuid PRIMARY KEY,
    solution_id uuid NOT NULL REFERENCES solution(id),
    section_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('MODEL_DRAFT','HUMAN_EDIT','ADOPTED')),
    content text NOT NULL CHECK (length(btrim(content)) > 0),
    requirement_version_id uuid NOT NULL,
    outline_version_id uuid NOT NULL,
    baseline_section_version_id uuid,
    parent_version_id uuid,
    created_by uuid REFERENCES app_user(id),
    model_id text,
    created_at timestamptz NOT NULL,
    attempt_id uuid UNIQUE,
    UNIQUE (solution_id, id),
    UNIQUE (solution_id, section_id, id),
    FOREIGN KEY (solution_id, requirement_version_id)
        REFERENCES solution_requirement_version(solution_id, id),
    FOREIGN KEY (solution_id, outline_version_id)
        REFERENCES solution_outline_version(solution_id, id),
    FOREIGN KEY (outline_version_id, section_id)
        REFERENCES solution_outline_node(version_id, section_id),
    FOREIGN KEY (solution_id, section_id, baseline_section_version_id)
        REFERENCES solution_section_version(solution_id, section_id, id),
    FOREIGN KEY (solution_id, section_id, parent_version_id)
        REFERENCES solution_section_version(solution_id, section_id, id),
    FOREIGN KEY (solution_id, attempt_id)
        REFERENCES solution_generation_attempt(solution_id, id),
    CHECK ((kind = 'MODEL_DRAFT' AND created_by IS NULL AND model_id IS NOT NULL AND attempt_id IS NOT NULL)
        OR (kind <> 'MODEL_DRAFT' AND created_by IS NOT NULL AND model_id IS NULL AND attempt_id IS NULL))
);
CREATE INDEX solution_section_version_section_idx
    ON solution_section_version (solution_id, section_id, created_at DESC);

CREATE TABLE solution_section_claim (
    version_id uuid NOT NULL REFERENCES solution_section_version(id),
    claim_id uuid NOT NULL,
    claim_order integer NOT NULL CHECK (claim_order > 0),
    verification_order integer NOT NULL CHECK (verification_order > 0),
    body text NOT NULL CHECK (length(btrim(body)) > 0),
    factual boolean NOT NULL,
    verdict text NOT NULL CHECK (verdict IN ('SUPPORTED','PARTIAL','UNSUPPORTED','HUMAN_CONFIRMATION_REQUIRED')),
    reason_code text NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    PRIMARY KEY (version_id, claim_id),
    UNIQUE (version_id, claim_order),
    UNIQUE (version_id, verification_order)
);
CREATE TABLE solution_section_claim_evidence (
    version_id uuid NOT NULL,
    claim_id uuid NOT NULL,
    evidence_id uuid NOT NULL,
    PRIMARY KEY (version_id, claim_id, evidence_id),
    FOREIGN KEY (version_id, claim_id) REFERENCES solution_section_claim(version_id, claim_id)
);
CREATE TABLE solution_section_claim_risk (
    version_id uuid NOT NULL,
    claim_id uuid NOT NULL,
    risk_type text NOT NULL,
    PRIMARY KEY (version_id, claim_id, risk_type),
    FOREIGN KEY (version_id, claim_id) REFERENCES solution_section_claim(version_id, claim_id)
);

DO $$
DECLARE runtime_role text := current_setting('rag.runtime_role', true);
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN
        RAISE EXCEPTION 'rag.runtime_role must be configured before migration';
    END IF;
    EXECUTE format('GRANT SELECT, INSERT ON TABLE solution_section_version, solution_section_claim, solution_section_claim_evidence, solution_section_claim_risk TO %I', runtime_role);
END $$;
