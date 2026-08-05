-- Shared custom-attribute lookup, live-verified against Oracle. Prior
-- Award/Negotiation/Subaward custom-data extraction queries all
-- preserved CUSTOM_ATTRIBUTE_ID unjoined, each with a comment noting
-- the lookup object was never independently verified (see
-- sql/extract/award/05_award_custom_data.sql's own header). This
-- migration is that verification: CUSTOM_ATTRIBUTE (105 rows) has no
-- ACTIVE flag or sort order of its own - both are properties of
-- CUSTOM_ATTRIBUTE_DOCUMENT instead, a real bridge table keyed by
-- DOCUMENT_TYPE_CODE (KEW document type: 'INPR' = Institutional
-- Proposal, 'AWRD' = Award, 'PRDV' = Proposal Development - 146 rows
-- total, live-verified). A given custom attribute can be
-- active/required/sorted differently per document type it appears on
-- (live-verified: attribute 1120 "Activity Code" is active='Y' on one
-- document type context but inactive on INPR specifically), so
-- "active"/"sort order" only have meaning once scoped to the document
-- type actually being displayed - never denormalized onto
-- archive.custom_attribute itself.
--
-- Both tables are shared, not owned by any one domain - reusable by
-- Award/Negotiation/Subaward's own custom-data features later without
-- duplicating these lookups.

CREATE TABLE IF NOT EXISTS archive.custom_attribute (
    custom_attribute_id      BIGINT PRIMARY KEY,
    name                     VARCHAR(30),
    label                    VARCHAR(200),
    data_type_code           VARCHAR(3),
    data_type_description    VARCHAR(200),
    group_name               VARCHAR(250),

    source_update_timestamp  TIMESTAMP,
    source_update_user       VARCHAR(100),

    loaded_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                  BIGINT REFERENCES archive.load_run(load_id)
);

CREATE TABLE IF NOT EXISTS archive.custom_attribute_document (
    document_type_code       VARCHAR(4) NOT NULL,
    custom_attribute_id      BIGINT NOT NULL
                                 REFERENCES archive.custom_attribute(custom_attribute_id),
    type_name                VARCHAR(100),
    is_required               VARCHAR(1),
    active_flag                VARCHAR(1),
    sort_id                    INTEGER,

    source_update_timestamp  TIMESTAMP,
    source_update_user       VARCHAR(100),

    loaded_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                  BIGINT REFERENCES archive.load_run(load_id),

    PRIMARY KEY (document_type_code, custom_attribute_id)
);

CREATE INDEX IF NOT EXISTS ix_custom_attribute_document_attribute
    ON archive.custom_attribute_document (custom_attribute_id);

-- PROPOSAL_CUSTOM_DATA - live-verified real and widely populated
-- (33,661 distinct proposal_numbers archive-wide; fixture 01157400
-- alone carries 161 rows across only 30 distinct custom_attribute_ids
-- spread over 6 different sequence_numbers/proposal_ids - proving this
-- is version-scoped, like archive.award_custom_data, never family-wide
-- - a version's own values are never combined with another version's).
--
-- custom_attribute_id is deliberately NOT a foreign key to
-- archive.custom_attribute: that reference table is loaded
-- independently (archive_etl.reference_data), and Oracle admins can
-- add new custom attributes over time, so a Proposal load must never
-- hard-fail because the shared lookup hasn't (yet, or ever) been
-- refreshed with a newly-added attribute. The API resolves the label
-- via a LEFT JOIN at query time - a missing lookup surfaces as a null
-- label, not a load failure.

CREATE TABLE IF NOT EXISTS archive.proposal_custom_data (
    proposal_custom_data_id  BIGINT PRIMARY KEY,
    proposal_id               BIGINT NOT NULL,
    proposal_number            VARCHAR(50) NOT NULL,
    sequence_number             INTEGER NOT NULL,
    custom_attribute_id         BIGINT,
    value                        VARCHAR(2000),

    source_update_timestamp     TIMESTAMP,
    source_update_user          VARCHAR(100),

    loaded_at                   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                     BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_proposal_custom_data_proposal
    ON archive.proposal_custom_data (proposal_id);

CREATE INDEX IF NOT EXISTS ix_proposal_custom_data_attribute
    ON archive.proposal_custom_data (custom_attribute_id);
