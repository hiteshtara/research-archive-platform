-- PROPOSAL_CUSTOM_DATA is real and widely populated (live-verified:
-- 33,661 distinct proposal_numbers archive-wide). Carries its own real
-- sequence_number per row - version-scoped, like AWARD_CUSTOM_DATA,
-- never family-wide (live-verified: fixture 01157400 has 161 rows
-- across only 30 distinct custom_attribute_ids spread over 6 different
-- sequence_numbers/proposal_ids - a version's own values are never
-- combined with another version's). custom_attribute_id is kept
-- unjoined here - the shared archive.custom_attribute/
-- custom_attribute_document reference tables resolve the label at
-- query time.
--
-- proposal_number/sequence_number are resolved via a join back to
-- PROPOSAL (the same authoritative source 01_proposal_versions.sql
-- reads from) rather than trusted from PROPOSAL_CUSTOM_DATA's own
-- denormalized copies - live-verified: proposal_custom_data_id 383407
-- (proposal_id 1157402) has both of its own copies NULL even though
-- proposal_id is populated, which would otherwise violate this
-- archive's NOT NULL columns on load.

SELECT
    pcd.proposal_custom_data_id,
    pcd.proposal_id,
    p.proposal_number,
    p.sequence_number,
    pcd.custom_attribute_id,
    pcd.value,

    pcd.update_timestamp AS source_update_timestamp,
    pcd.update_user AS source_update_user

FROM proposal_custom_data pcd

JOIN proposal p
    ON p.proposal_id = pcd.proposal_id

ORDER BY
    p.proposal_number,
    p.sequence_number,
    pcd.proposal_custom_data_id;
