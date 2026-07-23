-- Export details captured when a Negotiation is not linked to another module
-- to negotiation_unassociated.csv. NEGOTIATION_ID is the parent foreign key.
-- Reference codes remain text so values such as unit and sponsor codes keep
-- any leading zeroes. Their lookup objects are outside the verified set.
SELECT
    ud.negotiation_unassoc_detail_id AS negotiation_unassoc_detail_id,
    ud.negotiation_id AS negotiation_id,
    ud.title AS title,
    ud.pi_person_id AS pi_person_id,
    ud.pi_rolodex_id AS pi_rolodex_id,
    ud.lead_unit AS lead_unit,
    ud.sponsor_code AS sponsor_code,
    ud.pi_name AS pi_name,
    ud.prime_sponsor_code AS prime_sponsor_code,
    ud.sponsor_award_number AS sponsor_award_number,
    ud.contact_admin_person_id AS contact_admin_person_id,
    ud.subaward_org AS subaward_org,
    ud.update_timestamp AS update_timestamp,
    ud.update_user AS update_user,
    ud.ver_nbr AS ver_nbr,
    ud.obj_id AS obj_id
FROM KCOEUS.NEGOTIATION_UNASSOC_DETAIL ud
ORDER BY ud.negotiation_id, ud.negotiation_unassoc_detail_id;
