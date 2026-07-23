/*
 * Full historical PROTO_AMEND_RENEWAL export.
 *
 * Parent resolution is performed by the archive loader using the exact
 * Protocol number and sequence. The Oracle PROTOCOL_ID is preserved only as
 * source audit metadata.
 */
SELECT
    renewal.PROTO_AMEND_RENEWAL_ID AS proto_amend_renewal_id,
    renewal.PROTOCOL_ID AS source_protocol_id,
    renewal.PROTOCOL_NUMBER AS protocol_number,
    renewal.SEQUENCE_NUMBER AS sequence_number,
    renewal.PROTO_AMEND_REN_NUMBER AS proto_amend_ren_number,
    renewal.DATE_CREATED AS date_created,
    DBMS_LOB.SUBSTR(renewal.SUMMARY, 4000, 1) AS summary,
    renewal.UPDATE_TIMESTAMP AS source_update_timestamp,
    renewal.UPDATE_USER AS source_update_user,
    renewal.VER_NBR AS source_version_number,
    renewal.OBJ_ID AS source_object_id
FROM KCOEUS.PROTO_AMEND_RENEWAL renewal
ORDER BY renewal.PROTO_AMEND_RENEWAL_ID;