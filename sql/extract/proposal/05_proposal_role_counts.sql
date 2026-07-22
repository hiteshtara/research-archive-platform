SELECT
    NVL(contact_role_code, '<NULL>') AS contact_role_code,
    NVL(key_person_project_role, '<NULL>') AS key_person_project_role,
    COUNT(*) AS row_count
FROM proposal_persons
GROUP BY
    contact_role_code,
    key_person_project_role
ORDER BY
    contact_role_code,
    key_person_project_role;
