SELECT
    pp.proposal_person_id,
    pp.proposal_id,
    pp.proposal_number,
    pp.sequence_number,

    pp.person_id,
    pp.rolodex_id,
    pp.full_name,

    pp.contact_role_code,
    pp.key_person_project_role,

    pp.faculty_flag,
    pp.academic_year_effort,
    pp.calendar_year_effort,
    pp.summer_effort,
    pp.total_effort,

    pp.update_timestamp AS source_update_timestamp,
    pp.update_user AS source_update_user

FROM proposal_persons pp

ORDER BY
    pp.proposal_number,
    pp.sequence_number,
    pp.proposal_person_id;
