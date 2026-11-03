\pset pager off
\timing off

-- =====================================================================
-- Negotiation authorization data profile. READ ONLY - every statement a
-- SELECT. Column names verified against database/migrations first.
-- =====================================================================

-- N1. Grain. archive.negotiation has negotiation_id as PK and NO
--     number+sequence pair, so unlike Award/Proposal/Subaward there may
--     be no version family at all. Proving it rather than assuming.
SELECT
    COUNT(*)                                   AS negotiation_rows,
    COUNT(DISTINCT negotiation_id)             AS distinct_ids,
    COUNT(DISTINCT document_number)            AS distinct_document_numbers,
    COUNT(*) FILTER (WHERE document_number IS NULL) AS null_document_number
FROM archive.negotiation;

-- N1b. Does any document_number repeat? (would imply a family grain)
SELECT COUNT(*) AS document_numbers_used_more_than_once
FROM (SELECT document_number FROM archive.negotiation
      WHERE document_number IS NOT NULL
      GROUP BY document_number HAVING COUNT(*) > 1) t;

-- N2/N3. Association types, and how many carry an association id.
SELECT
    COALESCE(negotiation_association_type_description,'(null)') AS association_type,
    COUNT(*)                                                     AS negotiations,
    COUNT(*) FILTER (WHERE associated_document_id IS NOT NULL
                       AND TRIM(associated_document_id) <> '')   AS with_association_id,
    COUNT(*) FILTER (WHERE associated_document_id IS NULL
                        OR TRIM(associated_document_id) = '')    AS without_association_id
FROM archive.negotiation
GROUP BY 1 ORDER BY negotiations DESC;

-- N4. Do association references RESOLVE? associated_document_id is TEXT.
--     Tested against Award (award_number and workflow document number)
--     and Proposal (proposal_number).
SELECT
    COUNT(*)                                                      AS with_association_id,
    COUNT(*) FILTER (WHERE av.award_number IS NOT NULL)           AS resolves_to_award_number,
    COUNT(*) FILTER (WHERE awd.workflow_document_number IS NOT NULL) AS resolves_to_award_doc_number,
    COUNT(*) FILTER (WHERE pv.proposal_number IS NOT NULL)        AS resolves_to_proposal_number,
    COUNT(*) FILTER (WHERE av.award_number IS NULL
                       AND awd.workflow_document_number IS NULL
                       AND pv.proposal_number IS NULL)            AS resolves_to_nothing
FROM archive.negotiation n
LEFT JOIN LATERAL (SELECT 1 AS award_number FROM archive.award_version x
                   WHERE x.award_number = TRIM(n.associated_document_id) LIMIT 1) av ON TRUE
LEFT JOIN LATERAL (SELECT 1 AS workflow_document_number FROM archive.award_version x
                   WHERE x.workflow_document_number = TRIM(n.associated_document_id) LIMIT 1) awd ON TRUE
LEFT JOIN LATERAL (SELECT 1 AS proposal_number FROM archive.proposal_version x
                   WHERE x.proposal_number = TRIM(n.associated_document_id) LIMIT 1) pv ON TRUE
WHERE n.associated_document_id IS NOT NULL AND TRIM(n.associated_document_id) <> '';

-- N5. Is one associated_document_id shared by several Negotiations, and
--     does one Negotiation ever name more than one record? (the column
--     is scalar TEXT, so the second is only possible via delimiters)
SELECT
    COUNT(DISTINCT TRIM(associated_document_id))                       AS distinct_association_ids,
    COUNT(*) FILTER (WHERE associated_document_id LIKE '%,%'
                        OR associated_document_id LIKE '%;%'
                        OR associated_document_id LIKE '% %')          AS ids_containing_a_delimiter
FROM archive.negotiation
WHERE associated_document_id IS NOT NULL AND TRIM(associated_document_id) <> '';

SELECT MAX(negotiations_sharing_one_id) AS max_negotiations_per_association_id
FROM (SELECT TRIM(associated_document_id) AS k, COUNT(*) AS negotiations_sharing_one_id
      FROM archive.negotiation
      WHERE associated_document_id IS NOT NULL AND TRIM(associated_document_id) <> ''
      GROUP BY 1) t;

-- N6. Lead-unit coverage. TWO candidate sources: the denormalised
--     negotiation_search_attribute (one row per negotiation, any source)
--     and negotiation_unassociated_detail.lead_unit.
SELECT
    (SELECT COUNT(*) FROM archive.negotiation)                                AS negotiations,
    COUNT(sa.negotiation_id)                                                  AS with_search_attribute_row,
    COUNT(*) FILTER (WHERE NULLIF(TRIM(sa.lead_unit_number),'') IS NOT NULL)  AS with_lead_unit_number,
    COUNT(*) FILTER (WHERE NULLIF(TRIM(sa.lead_unit_number),'') IS NOT NULL
                       AND u.unit_number IS NOT NULL)                         AS lead_unit_matches_archive_unit,
    COUNT(*) FILTER (WHERE NULLIF(TRIM(sa.lead_unit_number),'') IS NOT NULL
                       AND u.unit_number IS NULL)                             AS lead_unit_unmatched
FROM archive.negotiation n
LEFT JOIN archive.negotiation_search_attribute sa ON sa.negotiation_id = n.negotiation_id
LEFT JOIN archive.unit u ON UPPER(TRIM(u.unit_number)) = UPPER(TRIM(sa.lead_unit_number));

-- N6b. Where do those attributes come from?
SELECT COALESCE(attribute_source,'(null)') AS attribute_source,
       COUNT(*)                            AS rows,
       COUNT(*) FILTER (WHERE NULLIF(TRIM(lead_unit_number),'') IS NOT NULL) AS with_lead_unit
FROM archive.negotiation_search_attribute GROUP BY 1 ORDER BY rows DESC;

-- N6c. negotiation_unassociated_detail specifically.
SELECT
    COUNT(*)                                                           AS unassociated_detail_rows,
    COUNT(DISTINCT negotiation_id)                                     AS negotiations_covered,
    COUNT(*) FILTER (WHERE NULLIF(TRIM(lead_unit),'') IS NOT NULL)     AS with_lead_unit,
    COUNT(*) FILTER (WHERE NULLIF(TRIM(pi_person_id),'') IS NOT NULL)  AS with_pi_person_id,
    COUNT(*) FILTER (WHERE NULLIF(TRIM(contact_admin_person_id),'') IS NOT NULL) AS with_contact_admin
FROM archive.negotiation_unassociated_detail;

-- N7. Negotiator and administrative contacts - a possible person-based rule.
SELECT
    COUNT(*)                                                                AS negotiations,
    COUNT(*) FILTER (WHERE NULLIF(TRIM(negotiator_person_id),'') IS NOT NULL) AS with_negotiator_person_id,
    COUNT(DISTINCT NULLIF(TRIM(negotiator_person_id),''))                   AS distinct_negotiators
FROM archive.negotiation;

-- N8. THE HEADLINE: how many Negotiations have NO reliable authorization
--     relationship at all - no resolvable association AND no usable lead unit.
WITH basis AS (
  SELECT n.negotiation_id,
         (av.hit IS NOT NULL OR awd.hit IS NOT NULL OR pv.hit IS NOT NULL) AS resolvable_association,
         (NULLIF(TRIM(sa.lead_unit_number),'') IS NOT NULL
            AND u.unit_number IS NOT NULL)                                  AS usable_lead_unit
  FROM archive.negotiation n
  LEFT JOIN archive.negotiation_search_attribute sa ON sa.negotiation_id = n.negotiation_id
  LEFT JOIN archive.unit u ON UPPER(TRIM(u.unit_number)) = UPPER(TRIM(sa.lead_unit_number))
  LEFT JOIN LATERAL (SELECT 1 AS hit FROM archive.award_version x
                     WHERE x.award_number = TRIM(n.associated_document_id) LIMIT 1) av ON TRUE
  LEFT JOIN LATERAL (SELECT 1 AS hit FROM archive.award_version x
                     WHERE x.workflow_document_number = TRIM(n.associated_document_id) LIMIT 1) awd ON TRUE
  LEFT JOIN LATERAL (SELECT 1 AS hit FROM archive.proposal_version x
                     WHERE x.proposal_number = TRIM(n.associated_document_id) LIMIT 1) pv ON TRUE)
SELECT COUNT(*)                                                             AS negotiations,
       COUNT(*) FILTER (WHERE resolvable_association)                       AS has_resolvable_association,
       COUNT(*) FILTER (WHERE usable_lead_unit)                             AS has_usable_lead_unit,
       COUNT(*) FILTER (WHERE resolvable_association OR usable_lead_unit)   AS has_either,
       COUNT(*) FILTER (WHERE NOT resolvable_association AND NOT usable_lead_unit) AS has_NEITHER,
       ROUND(100.0*COUNT(*) FILTER (WHERE NOT resolvable_association AND NOT usable_lead_unit)
             /NULLIF(COUNT(*),0),2)                                         AS pct_neither
FROM basis;
