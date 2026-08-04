SELECT
    puc.proposal_unit_contact_id,
    puc.proposal_id,
    puc.proposal_number,
    puc.sequence_number,

    puc.person_id,
    puc.full_name,
    puc.unit_administrator_type_code,
    puc.unit_contact_type,

    puc.update_timestamp AS source_update_timestamp,
    puc.update_user AS source_update_user

FROM proposal_unit_contacts puc

ORDER BY
    puc.proposal_number,
    puc.sequence_number,
    puc.proposal_unit_contact_id;
