-- Export contact identifiers for each physical Subaward version. Contact type
-- and Rolodex lookup tables are external to the verified Subaward inventory.
SELECT
    sc.subaward_contact_id AS subaward_contact_id,
    sc.subaward_id AS subaward_id,
    sc.subaward_code AS subaward_code,
    sc.sequence_number AS sequence_number,
    sc.contact_type_code AS contact_type_code,
    sc.rolodex_id AS rolodex_id,
    sc.requisitioner_id AS requisitioner_id,
    sc.update_timestamp AS update_timestamp,
    sc.update_user AS update_user,
    sc.ver_nbr AS ver_nbr,
    sc.obj_id AS obj_id
FROM KCOEUS.SUBAWARD_CONTACT sc
ORDER BY sc.subaward_code, sc.sequence_number, sc.subaward_contact_id;
