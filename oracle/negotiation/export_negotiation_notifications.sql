-- Export Negotiation notifications to negotiation_notifications.csv.
-- OWNING_DOCUMENT_ID_FK is the descriptor-defined parent link to NEGOTIATION.
-- NOTIFICATION_TYPE_ID is retained without a lookup join because the common
-- notification-type source object has not yet been verified in BU Oracle.
SELECT
    nn.notification_id AS notification_id,
    nn.notification_type_id AS notification_type_id,
    nn.document_number AS document_number,
    nn.owning_document_id_fk AS owning_document_id_fk,
    nn.recipients AS recipients,
    nn.subject AS subject,
    nn.message AS message,
    nn.update_timestamp AS update_timestamp,
    nn.update_user AS update_user,
    nn.ver_nbr AS ver_nbr,
    nn.obj_id AS obj_id
FROM KCOEUS.NEGOTIATION_NOTIFICATION nn
ORDER BY nn.owning_document_id_fk, nn.notification_id;
