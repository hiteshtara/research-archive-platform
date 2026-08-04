-- PROPOSAL_PERSON_UNITS has no PROPOSAL_ID column of its own - only
-- PROPOSAL_PERSON_ID. proposal_id/proposal_number/sequence_number are
-- denormalized through this join back to PROPOSAL_PERSONS so the
-- loader's own read_filtered(column="proposal_id", ...) can scope
-- these rows the same way it already scopes proposal_persons/
-- proposal_attachments. Inner JOIN, not LEFT JOIN: PROPOSAL_PERSON_ID
-- is NOT NULL on this table.

SELECT
    ppu.proposal_person_unit_id,
    ppu.proposal_person_id,
    pp.proposal_id,
    pp.proposal_number,
    pp.sequence_number,

    ppu.unit_number,
    ppu.lead_unit_flag,

    ppu.update_timestamp AS source_update_timestamp,
    ppu.update_user AS source_update_user

FROM proposal_person_units ppu
JOIN proposal_persons pp
    ON ppu.proposal_person_id = pp.proposal_person_id

ORDER BY
    pp.proposal_id,
    ppu.proposal_person_unit_id;
