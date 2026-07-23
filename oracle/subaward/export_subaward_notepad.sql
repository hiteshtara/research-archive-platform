-- Export Subaward notes, including the restricted-view marker and create/update
-- audit fields. SUBAWARD_NOTEPAD has no SEQUENCE_NUMBER in verified Oracle.
SELECT
    sn.subaward_notepad_id AS subaward_notepad_id,
    sn.subaward_id AS subaward_id,
    sn.subaward_code AS subaward_code,
    sn.entry_number AS entry_number,
    sn.note_topic AS note_topic,
    sn.comments AS comments,
    sn.restricted_view AS restricted_view,
    sn.create_timestamp AS create_timestamp,
    sn.create_user AS create_user,
    sn.update_timestamp AS update_timestamp,
    sn.update_user AS update_user,
    sn.ver_nbr AS ver_nbr,
    sn.obj_id AS obj_id
FROM KCOEUS.SUBAWARD_NOTEPAD sn
ORDER BY sn.subaward_code, sn.entry_number, sn.subaward_notepad_id;
