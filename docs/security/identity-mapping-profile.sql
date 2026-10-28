\pset pager off
\timing off
-- Identity-mapping profile. READ ONLY. Aggregate counts and
-- de-identified shapes only: no names, no emails, no identifiers.

-- I1. Which KRIM-derived principal identifiers exist, and how many?
SELECT
    (SELECT COUNT(*) FROM archive.person)                              AS archive_person_rows,
    (SELECT COUNT(DISTINCT person_id) FROM archive.person)             AS distinct_person_ids,
    (SELECT COUNT(*) FROM authz.kim_principal)                         AS authz_kim_principal_rows,
    (SELECT COUNT(*) FROM authz.principal_crosswalk)                   AS crosswalk_rows,
    (SELECT COUNT(*) FROM authz.identity_link)                         AS identity_link_rows,
    (SELECT COUNT(*) FROM authz.person_status)                         AS person_status_rows,
    (SELECT COUNT(*) FROM authz.access_grant)                          AS access_grant_rows;

-- I2. Are names and institutional identifiers stored? (presence only)
SELECT
    COUNT(*)                                                           AS person_rows,
    COUNT(*) FILTER (WHERE NULLIF(TRIM(full_name),'')      IS NOT NULL) AS with_full_name,
    COUNT(*) FILTER (WHERE NULLIF(TRIM(last_name),'')      IS NOT NULL) AS with_last_name,
    COUNT(*) FILTER (WHERE NULLIF(TRIM(email_address),'')  IS NOT NULL) AS with_email,
    COUNT(*) FILTER (WHERE NULLIF(TRIM(phone_number),'')   IS NOT NULL) AS with_phone
FROM archive.person;

-- I3. Does email uniquely identify a principal?
SELECT
    COUNT(*)                                                 AS people_with_an_email,
    COUNT(DISTINCT LOWER(TRIM(email_address)))               AS distinct_emails,
    COUNT(*) - COUNT(DISTINCT LOWER(TRIM(email_address)))    AS duplicate_email_rows
FROM archive.person
WHERE NULLIF(TRIM(email_address),'') IS NOT NULL;

SELECT COUNT(*) AS emails_shared_by_more_than_one_person
FROM (SELECT LOWER(TRIM(email_address)) e FROM archive.person
      WHERE NULLIF(TRIM(email_address),'') IS NOT NULL
      GROUP BY 1 HAVING COUNT(DISTINCT person_id) > 1) t;

-- I3b. Email domain shape only - no addresses.
SELECT SPLIT_PART(LOWER(TRIM(email_address)),'@',2) AS domain, COUNT(*) AS people
FROM archive.person WHERE NULLIF(TRIM(email_address),'') IS NOT NULL
GROUP BY 1 ORDER BY people DESC LIMIT 5;

-- I4. Do multiple principals share an entity id?
SELECT
    COUNT(*)                                     AS kim_principal_rows,
    COUNT(DISTINCT entity_id)                    AS distinct_entity_ids,
    COUNT(*) FILTER (WHERE actv_ind = 'Y')       AS active_principals,
    COUNT(*) FILTER (WHERE actv_ind = 'N')       AS inactive_principals
FROM authz.kim_principal;

SELECT COUNT(*) AS entity_ids_with_multiple_principals
FROM (SELECT entity_id FROM authz.kim_principal
      GROUP BY 1 HAVING COUNT(DISTINCT prncpl_id) > 1) t;

-- I5/I7. Can the archive identify people who are NOT Award/Proposal
--        contacts? archive.person is deliberately scoped (V056), so this
--        measures the ceiling of any automatic mapping.
SELECT
    (SELECT COUNT(DISTINCT person_id) FROM archive.person)                   AS people_in_archive,
    (SELECT COUNT(DISTINCT person_id) FROM archive.award_person
       WHERE person_id IS NOT NULL)                                          AS award_contacts,
    (SELECT COUNT(DISTINCT person_id) FROM archive.proposal_person
       WHERE person_id IS NOT NULL)                                          AS proposal_contacts,
    (SELECT COUNT(DISTINCT person_id) FROM archive.unit_administrator
       WHERE person_id IS NOT NULL)                                          AS unit_administrators;

-- I5b. How many contact person_ids have NO archive.person row at all?
SELECT
    COUNT(*)                                                     AS distinct_contact_person_ids,
    COUNT(*) FILTER (WHERE p.person_id IS NULL)                  AS contacts_without_a_person_row
FROM (
    SELECT person_id FROM archive.award_person    WHERE person_id IS NOT NULL
    UNION
    SELECT person_id FROM archive.proposal_person WHERE person_id IS NOT NULL
) c
LEFT JOIN archive.person p ON p.person_id = c.person_id;

-- I8. Existing identity links - any verified mappings at all?
SELECT COALESCE(status,'(none)') AS status, COALESCE(method,'(none)') AS method, COUNT(*) AS links
FROM authz.identity_link GROUP BY 1,2;

-- I6. Crosswalk: which attribute names are in use (eppn?), and coverage.
SELECT COALESCE(attribute_name,'(none)') AS attribute_name,
       COUNT(*) AS rows,
       COUNT(*) FILTER (WHERE status='ACTIVE') AS active
FROM authz.principal_crosswalk GROUP BY 1;
