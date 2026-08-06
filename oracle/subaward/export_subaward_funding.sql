-- Export source Award associations. AWARD_ID is the authoritative,
-- proven link (SubAwardFundingSource.awardId -> Award, from
-- repository-subAward.xml) but is only a raw internal Award version id,
-- not a business identifier the archive can display or resolve to a
-- current Award version on its own - award_number is denormalized here
-- via a LEFT JOIN (never assumed present) so the API can still show
-- "Associated Award <number>" even when that exact Award version, or
-- the whole Award family, has not been archived.
SELECT
    sfs.subaward_funding_source_id AS subaward_funding_source_id,
    sfs.subaward_id AS subaward_id,
    sfs.subaward_code AS subaward_code,
    sfs.sequence_number AS sequence_number,
    sfs.award_id AS award_id,
    a.award_number AS award_number,
    sfs.update_timestamp AS update_timestamp,
    sfs.update_user AS update_user,
    sfs.ver_nbr AS ver_nbr,
    sfs.obj_id AS obj_id
FROM KCOEUS.SUBAWARD_FUNDING_SOURCE sfs
LEFT JOIN AWARD a
    ON a.award_id = sfs.award_id
ORDER BY sfs.subaward_code, sfs.sequence_number,
    sfs.subaward_funding_source_id;
