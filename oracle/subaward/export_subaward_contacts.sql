-- Export contact identifiers for each physical Subaward version.
-- contact_type_description is denormalized via KCOEUS.CONTACT_TYPE (the
-- shared Award/Subaward contact-type lookup, proven from
-- SubAwardContact.java's contactType reference-descriptor) so the API
-- can show a real contact role name without a new reference table.
-- Rolodex/Person resolution happens at read time in the archive's own
-- repository SQL (against already-archived archive.rolodex/
-- archive.person), not here - both are already-comprehensive-enough
-- archive tables, not narrow to Subaward.
SELECT
    sc.subaward_contact_id AS subaward_contact_id,
    sc.subaward_id AS subaward_id,
    sc.subaward_code AS subaward_code,
    sc.sequence_number AS sequence_number,
    sc.contact_type_code AS contact_type_code,
    ct.description AS contact_type_description,
    sc.rolodex_id AS rolodex_id,
    sc.requisitioner_id AS requisitioner_id,
    sc.update_timestamp AS update_timestamp,
    sc.update_user AS update_user,
    sc.ver_nbr AS ver_nbr,
    sc.obj_id AS obj_id
FROM KCOEUS.SUBAWARD_CONTACT sc
LEFT JOIN KCOEUS.CONTACT_TYPE ct
    ON ct.contact_type_code = sc.contact_type_code
ORDER BY sc.subaward_code, sc.sequence_number, sc.subaward_contact_id;
