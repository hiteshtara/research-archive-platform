SELECT
    p.proposal_id,
    p.proposal_number,
    p.sequence_number AS version_number,
    p.title,
    p.proposal_sequence_status,

    p.proposal_type_code,
    pt.description AS proposal_type,

    p.activity_type_code,
    at.description AS activity_type,

    p.sponsor_code,
    s.sponsor_name,

    p.lead_unit_number,
    u.unit_name AS lead_unit_name,

    pi.person_id AS principal_investigator_id,
    pi.full_name AS principal_investigator_name,

    p.requested_start_date_initial AS initial_start_date,
    p.requested_end_date_initial AS initial_end_date,
    p.total_direct_cost_initial AS initial_direct_cost,
    p.total_indirect_cost_initial AS initial_indirect_cost,

    CASE
        WHEN p.total_direct_cost_initial IS NULL
         AND p.total_indirect_cost_initial IS NULL
        THEN NULL
        ELSE NVL(p.total_direct_cost_initial, 0)
           + NVL(p.total_indirect_cost_initial, 0)
    END AS initial_total_cost,

    p.requested_start_date_total AS total_start_date,
    p.requested_end_date_total AS total_end_date,
    p.total_direct_cost_total AS total_direct_cost,
    p.total_indirect_cost_total AS total_indirect_cost,

    CASE
        WHEN p.total_direct_cost_total IS NULL
         AND p.total_indirect_cost_total IS NULL
        THEN NULL
        ELSE NVL(p.total_direct_cost_total, 0)
           + NVL(p.total_indirect_cost_total, 0)
    END AS total_cost,

    p.update_timestamp AS source_update_timestamp

FROM proposal p

LEFT JOIN proposal_type pt
    ON pt.proposal_type_code = p.proposal_type_code

LEFT JOIN activity_type at
    ON at.activity_type_code = p.activity_type_code

LEFT JOIN sponsor s
    ON s.sponsor_code = p.sponsor_code

LEFT JOIN unit u
    ON u.unit_number = p.lead_unit_number

LEFT JOIN (
    SELECT
        proposal_id,
        sequence_number,
        person_id,
        full_name,
        ROW_NUMBER() OVER (
            PARTITION BY
                proposal_id,
                sequence_number
            ORDER BY
                proposal_person_id DESC
        ) AS row_rank
    FROM proposal_persons
    WHERE UPPER(TRIM(contact_role_code)) = 'PI'
) pi
    ON pi.proposal_id = p.proposal_id
   AND pi.sequence_number = p.sequence_number
   AND pi.row_rank = 1

ORDER BY
    p.proposal_number,
    p.sequence_number;
