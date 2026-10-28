-- An edit has one successor; saves serialize on the owning solution row.
CREATE UNIQUE INDEX solution_section_edit_parent_unique
    ON solution_section_version (parent_version_id)
    WHERE kind = 'HUMAN_EDIT';
