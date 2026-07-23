-- Export one row per Negotiation business object to negotiations.csv.
--
-- NEGOTIATION_DOCUMENT is the document parent identified by DOCUMENT_NUMBER.
-- Lookup joins are LEFT JOINs so an unknown or inactive lookup value does not
-- remove the source negotiation from the archive export.
SELECT
    n.negotiation_id AS negotiation_id,
    n.document_number AS document_number,
    n.negotation_status_id AS negotiation_status_id,
    ns.negotiation_status_code AS negotiation_status_code,
    ns.description AS negotiation_status_description,
    n.negotiation_agreement_type_id AS negotiation_agreement_type_id,
    nat.negotiation_agrmnt_type_code AS negotiation_agreement_type_code,
    nat.description AS negotiation_agreement_type_description,
    n.negotiation_assc_type_id AS negotiation_association_type_id,
    nast.negotiation_assc_type_code AS negotiation_association_type_code,
    nast.description AS negotiation_association_type_description,
    n.negotiator_person_id AS negotiator_person_id,
    n.negotiator_full_name AS negotiator_full_name,
    n.negotiation_start_date AS negotiation_start_date,
    n.negotiation_end_date AS negotiation_end_date,
    n.anticipated_award_date AS anticipated_award_date,
    n.document_folder AS document_folder,
    n.associated_document_id AS associated_document_id,
    n.update_timestamp AS update_timestamp,
    n.update_user AS update_user,
    n.ver_nbr AS ver_nbr,
    n.obj_id AS obj_id,
    nd.update_timestamp AS document_update_timestamp,
    nd.update_user AS document_update_user,
    nd.ver_nbr AS document_ver_nbr,
    nd.obj_id AS document_obj_id
FROM KCOEUS.NEGOTIATION n
LEFT JOIN KCOEUS.NEGOTIATION_DOCUMENT nd
    ON nd.document_number = n.document_number
-- Status lookup: raw ID plus its stable code and display description.
LEFT JOIN KCOEUS.NEGOTIATION_STATUS ns
    ON ns.negotiation_status_id = n.negotation_status_id
-- Agreement-type lookup uses the lookup table's descriptor-defined ID name.
LEFT JOIN KCOEUS.NEGOTIATION_AGREEMENT_TYPE nat
    ON nat.negotiation_agrmnt_type_id = n.negotiation_agreement_type_id
-- Association type describes how ASSOCIATED_DOCUMENT_ID should be interpreted.
LEFT JOIN KCOEUS.NEGOTIATION_ASSOCIATION_TYPE nast
    ON nast.negotiation_assc_type_id = n.negotiation_assc_type_id
ORDER BY n.negotiation_id;
