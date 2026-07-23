-- Export Negotiation activity history to negotiation_activities.csv.
-- NEGOTIATION_ID is the parent foreign key to KCOEUS.NEGOTIATION.
-- Activity type and location retain raw IDs and add lookup codes/descriptions.
SELECT
    a.negotiation_activity_id AS negotiation_activity_id,
    a.negotiation_id AS negotiation_id,
    a.activity_type_id AS activity_type_id,
    at.negotiation_activity_type_code AS activity_type_code,
    at.description AS activity_type_description,
    a.location_id AS location_id,
    nl.negotiation_location_code AS location_code,
    nl.description AS location_description,
    a.start_date AS start_date,
    a.end_date AS end_date,
    a.create_date AS create_date,
    a.followup_date AS followup_date,
    a.last_modified_user AS last_modified_user,
    a.last_modified_date AS last_modified_date,
    a.description AS description,
    a.restricted AS restricted,
    a.update_timestamp AS update_timestamp,
    a.update_user AS update_user,
    a.ver_nbr AS ver_nbr,
    a.obj_id AS obj_id
FROM KCOEUS.NEGOTIATION_ACTIVITY a
-- The OJB relationship maps ACTIVITY_TYPE_ID to the activity-type lookup ID.
LEFT JOIN KCOEUS.NEGOTIATION_ACTIVITY_TYPE at
    ON at.negotiation_activity_type_id = a.activity_type_id
-- The OJB relationship maps LOCATION_ID to the location lookup ID.
LEFT JOIN KCOEUS.NEGOTIATION_LOCATION nl
    ON nl.negotiation_location_id = a.location_id
ORDER BY a.negotiation_id, a.start_date, a.negotiation_activity_id;
