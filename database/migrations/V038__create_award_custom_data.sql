-- Award Custom Data: KCOEUS.AWARD_CUSTOM_DATA, a generic key/value
-- (EAV-style) table of custom attribute values attached to an Award,
-- confirmed against the upstream Kuali Coeus source
-- (org.kuali.kra.award.customdata.AwardCustomData -> AWARD_CUSTOM_DATA).
-- Mirrors archive.subaward_custom_data's shape (which, like Award, has
-- its own award_number/sequence_number versioning) rather than
-- archive.negotiation_custom_data's (Negotiation has no sequence
-- concept at all). See docs/architecture/AWARD_IMPLEMENTATION_ROADMAP.md.
--
-- custom_attribute_id is deliberately kept as a bare Oracle ID, with no
-- lookup join - the same convention already established for
-- archive.negotiation_custom_data and archive.subaward_custom_data,
-- whose own migration comments note the lookup object is not yet
-- verified. Not re-litigated here.
--
-- This table is a Tier 1 subsystem per
-- docs/architecture/AWARD_DOMAIN_DECOMPOSITION.md: it depends only on
-- archive.award_version(award_id) already existing, with no dependency
-- on award_amount_info/award_person/award_funding_proposal.

CREATE TABLE IF NOT EXISTS archive.award_custom_data (
    award_custom_data_id     BIGINT PRIMARY KEY,
    award_id                 BIGINT NOT NULL
                                 REFERENCES archive.award_version(award_id)
                                 ON DELETE CASCADE,

    award_number             VARCHAR(50),
    sequence_number          INTEGER,

    custom_attribute_id      BIGINT,
    value                    TEXT,

    source_update_timestamp  TIMESTAMP,
    source_update_user       VARCHAR(100),
    source_version_number    BIGINT,
    source_object_id         VARCHAR(100),

    loaded_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                  BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_custom_data_award
    ON archive.award_custom_data (
        award_id,
        award_custom_data_id
    );

CREATE INDEX IF NOT EXISTS ix_award_custom_data_attribute
    ON archive.award_custom_data (custom_attribute_id);
