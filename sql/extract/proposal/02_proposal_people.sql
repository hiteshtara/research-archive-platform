SELECT
    pp.proposal_id,
    pp.sequence_number AS version_number,
    pp.person_id,
    pp.full_name,
    pp.contact_role_code AS role,

    CASE
        WHEN UPPER(TRIM(pp.contact_role_code)) = 'PI'
        THEN 'Y'
        ELSE 'N'
    END AS principal_investigator,

    pp.update_timestamp AS source_update_timestamp

FROM proposal_persons pp

WHERE pp.contact_role_code IN (
    'PI',
    'MPI',
    'COI',
    'KP'
)

ORDER BY
    pp.proposal_id,
    pp.sequence_number,
    CASE UPPER(TRIM(pp.contact_role_code))
        WHEN 'PI' THEN 1
        WHEN 'MPI' THEN 2
        WHEN 'COI' THEN 3
        WHEN 'KP' THEN 4
        ELSE 5
    END,
    pp.full_name;
