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
    pp.update_timestamp
FROM proposal_persons pp
WHERE pp.proposal_id = 3892
ORDER BY
    pp.sequence_number,
    pp.contact_role_code,
    pp.full_name;
