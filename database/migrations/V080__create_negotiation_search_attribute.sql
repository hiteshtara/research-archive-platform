-- Resolved, denormalized Negotiation attributes for search and display.
--
-- WHY THIS TABLE EXISTS
--
-- Kuali's "Negotiation Attributes" (Title, PI, Lead Unit, Sponsor, ...)
-- do not live in one place. archive.negotiation_unassociated_detail is
-- NOT a per-negotiation attributes table: it is populated only for
-- negotiations that are not associated with another module. Measured
-- live 2026-09-22, identically in Oracle and in the archive:
--
--   Association Type        Negotiations   With a detail row
--   None                           8,533               8,533  (100%)
--   Award                          2,223                  21  (0.9%)
--   Subaward                          16                   0
--   Institutional Proposal             3                   0
--   TOTAL                         10,775               8,554  (79.4%)
--
-- So reading or filtering PI/Sponsor/Lead Unit/Title from the detail
-- table alone silently blanks and silently excludes 2,221 of 10,775
-- negotiations (20.6%) while still reporting a correct-looking total
-- count. Award-associated negotiations resolve their attributes from
-- the associated Award instead: ASSOCIATED_DOCUMENT_ID matches
-- AWARD.AWARD_NUMBER for 2,223 of 2,223 of them (100%).
--
-- WHY IT IS A SEPARATE TABLE, NOT COLUMNS ON archive.negotiation
--
-- Every column on archive.negotiation is a faithful copy of a
-- KCOEUS.NEGOTIATION column. The values here are DERIVED - chosen
-- between two sources and resolved through reference tables - so they
-- are kept apart rather than mixed in beside source-faithful columns.
-- The join is 1:1 on the primary key, which plans as a hash/merge
-- join, not a correlated subquery.
--
-- WHY IT IS DENORMALIZED AT ALL
--
-- Resolving these per row at query time requires correlated LATERALs
-- against award_version and award_person. Measured on dev RDS with
-- EXPLAIN (ANALYZE, BUFFERS) before this migration: the paged 25-row
-- query ran in 0.6 ms but the COUNT(*) that drives pagination totals
-- took 2,489.8 ms, because both LATERALs are evaluated for all 10,775
-- rows. The ILIKE predicates were not the bottleneck and a trigram
-- index would not have fixed it. Denormalizing removes the LATERALs
-- from both the list and the count path.
--
-- INDEXES
--
-- Deliberately only the primary key for now. The filter predicates are
-- substring (ILIKE '%term%') matches over 10,775 rows, which a btree
-- index cannot serve, and this project does not add speculative
-- indexes. Index choices are to be driven by the EXPLAIN (ANALYZE,
-- BUFFERS) comparison run against this table once it is populated.

CREATE TABLE IF NOT EXISTS archive.negotiation_search_attribute (
    negotiation_id                  BIGINT PRIMARY KEY
                                        REFERENCES archive.negotiation(negotiation_id)
                                        ON DELETE CASCADE,

    -- UNASSOCIATED_DETAIL | AWARD | NONE - which source supplied the
    -- values below. Surfaced through the API so the UI can be honest
    -- about provenance instead of implying every row resolved the same
    -- way.
    attribute_source                VARCHAR(24) NOT NULL,

    -- Widths are the WIDEST of the two possible sources for each value,
    -- because every one is a COALESCE across the unassociated-detail
    -- row and the associated Award. Narrowing any of these to one
    -- source's width would fail the insert on the other:
    --   sponsor_name        detail->archive.sponsor VARCHAR(200)
    --                       vs award_version        VARCHAR(500)
    --   lead_unit_name      archive.unit  VARCHAR(60)
    --                       vs award_version        VARCHAR(500)
    --   sponsor_code        detail        VARCHAR(100)
    --                       vs award_version        VARCHAR(30)
    --   lead_unit_number    detail.lead_unit VARCHAR(100)
    --                       vs award_version        VARCHAR(30)
    title                           TEXT,

    principal_investigator_name     VARCHAR(500),
    principal_investigator_person_id VARCHAR(100),

    sponsor_code                    VARCHAR(100),
    sponsor_name                    VARCHAR(500),
    prime_sponsor_code              VARCHAR(100),
    prime_sponsor_name              VARCHAR(500),

    lead_unit_number                VARCHAR(100),
    lead_unit_name                  VARCHAR(500),

    sponsor_award_number            VARCHAR(200),

    loaded_at                       TIMESTAMPTZ NOT NULL
                                        DEFAULT CURRENT_TIMESTAMP,
    load_id                         BIGINT
                                        REFERENCES archive.load_run(load_id)
);
