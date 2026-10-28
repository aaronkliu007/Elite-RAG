CREATE TABLE solution (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES knowledge_space(id),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL,
    solution_revision bigint NOT NULL CHECK (solution_revision > 0),
    revision bigint NOT NULL CHECK (revision >= 0),
    current_requirement_version_id uuid NOT NULL,
    current_outline_version_id uuid,
    requirement_confirmed_by uuid REFERENCES app_user(id),
    requirement_confirmed_at timestamptz,
    outline_confirmed_by uuid REFERENCES app_user(id),
    outline_confirmed_at timestamptz,
    phase text NOT NULL CHECK (phase IN ('REQUIREMENTS_DRAFT','REQUIREMENTS_CONFIRMED','OUTLINE_DRAFT',
        'OUTLINE_CONFIRMED','GENERATING','IN_REVIEW','READY_TO_FINALIZE','FINALIZED')),
    finalized_version_id uuid,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, space_id),
    CHECK ((requirement_confirmed_by IS NULL) = (requirement_confirmed_at IS NULL)),
    CHECK ((outline_confirmed_by IS NULL) = (outline_confirmed_at IS NULL)),
    CHECK (outline_confirmed_by IS NULL OR current_outline_version_id IS NOT NULL),
    CHECK ((phase = 'FINALIZED') = (finalized_version_id IS NOT NULL))
);
CREATE INDEX solution_space_updated_idx ON solution (space_id, updated_at DESC, id DESC);

CREATE TABLE solution_requirement_version (
    id uuid PRIMARY KEY,
    solution_id uuid NOT NULL REFERENCES solution(id),
    number bigint NOT NULL CHECK (number > 0),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL,
    change_reason text NOT NULL CHECK (length(btrim(change_reason)) > 0),
    UNIQUE (solution_id, id),
    UNIQUE (solution_id, number)
);
ALTER TABLE solution ADD CONSTRAINT solution_current_requirement_fk
    FOREIGN KEY (id, current_requirement_version_id)
    REFERENCES solution_requirement_version(solution_id, id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE solution_requirement_item (
    version_id uuid NOT NULL REFERENCES solution_requirement_version(id),
    item_id uuid NOT NULL,
    item_order integer NOT NULL CHECK (item_order > 0),
    category text NOT NULL,
    body text NOT NULL CHECK (length(btrim(body)) > 0),
    PRIMARY KEY (version_id, item_id),
    UNIQUE (version_id, item_order)
);
CREATE TABLE solution_requirement_source (
    version_id uuid NOT NULL,
    item_id uuid NOT NULL,
    source_order integer NOT NULL CHECK (source_order > 0),
    space_id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    locator jsonb NOT NULL CHECK (jsonb_typeof(locator) = 'object'),
    PRIMARY KEY (version_id, item_id, source_order),
    FOREIGN KEY (version_id, item_id) REFERENCES solution_requirement_item(version_id, item_id),
    FOREIGN KEY (space_id, document_id) REFERENCES document(space_id, id),
    FOREIGN KEY (document_id, document_version_id) REFERENCES document_version(document_id, id)
);

CREATE TABLE solution_outline_version (
    id uuid PRIMARY KEY,
    solution_id uuid NOT NULL REFERENCES solution(id),
    number bigint NOT NULL CHECK (number > 0),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL,
    UNIQUE (solution_id, id),
    UNIQUE (solution_id, number)
);
ALTER TABLE solution ADD CONSTRAINT solution_current_outline_fk
    FOREIGN KEY (id, current_outline_version_id)
    REFERENCES solution_outline_version(solution_id, id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE solution_outline_node (
    version_id uuid NOT NULL REFERENCES solution_outline_version(id),
    section_id uuid NOT NULL,
    node_order integer NOT NULL CHECK (node_order > 0),
    title text NOT NULL CHECK (length(btrim(title)) > 0),
    level integer NOT NULL CHECK (level BETWEEN 1 AND 4),
    required boolean NOT NULL,
    objective text NOT NULL CHECK (length(btrim(objective)) > 0),
    retrieval_hint text NOT NULL CHECK (length(btrim(retrieval_hint)) > 0),
    PRIMARY KEY (version_id, section_id),
    UNIQUE (version_id, node_order)
);
CREATE TABLE solution_outline_node_risk (
    version_id uuid NOT NULL,
    section_id uuid NOT NULL,
    risk_type text NOT NULL,
    PRIMARY KEY (version_id, section_id, risk_type),
    FOREIGN KEY (version_id, section_id) REFERENCES solution_outline_node(version_id, section_id)
);
CREATE TABLE solution_adopted_section (
    solution_id uuid NOT NULL REFERENCES solution(id),
    section_id uuid NOT NULL,
    section_version_id uuid NOT NULL,
    PRIMARY KEY (solution_id, section_id)
);

CREATE TABLE solution_command_idempotency (
    actor_id uuid NOT NULL REFERENCES app_user(id),
    resource_id uuid NOT NULL,
    action text NOT NULL,
    client_key text NOT NULL,
    request_digest text NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    result_solution_id uuid,
    result_revision bigint,
    result_requirement_version_id uuid,
    result_outline_version_id uuid,
    result_phase text,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, resource_id, action, client_key),
    CHECK ((result_solution_id IS NULL) = (result_revision IS NULL)),
    CHECK ((result_solution_id IS NULL) = (result_requirement_version_id IS NULL)),
    CHECK ((result_solution_id IS NULL) = (result_phase IS NULL))
);

DO $$
DECLARE runtime_role text := current_setting('rag.runtime_role', true);
BEGIN
    IF runtime_role IS NULL OR runtime_role = '' THEN
        RAISE EXCEPTION 'rag.runtime_role must be configured before migration';
    END IF;
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE solution, solution_adopted_section, solution_command_idempotency TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT ON TABLE solution_requirement_version, solution_requirement_item, solution_requirement_source, solution_outline_version, solution_outline_node, solution_outline_node_risk TO %I', runtime_role);
END $$;
