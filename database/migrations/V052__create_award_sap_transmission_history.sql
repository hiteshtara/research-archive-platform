-- SAP Award Transmission History: the separate integration-history
-- subsystem identified by docs/architecture/SAP_AWARD_TRANSMISSION_ASSESSMENT.md.
-- This does NOT rebuild, call, or depend on the operational SAP
-- integration (edu.bu.kuali.kra.award.sapintegration.*) in any way -
-- it is archive-only ETL over the two real, persisted Kuali business
-- objects that already record transmission history:
-- edu.bu.kuali.kra.bo.AwardTransmission (AWARD_TRANSMISSION) and
-- edu.bu.kuali.kra.bo.AwardTransmissionChild (AWARD_TRANSMISSION_CHILD).
--
-- No Oracle bootstrap DDL for either table was found anywhere in the
-- available BU Kuali checkout (searched exhaustively - see the
-- assessment's Source material used) - the OJB class-descriptor in
-- repository-award.xml is the only confirmed source for column names
-- and Java-level types. Real Oracle-level PK/FK constraints, exact
-- column widths, and NOT NULL constraints are UNCONFIRMED, the same
-- situation already documented for AWARD_EXTENSION. Whoever next has
-- BU Oracle/VPN access should confirm this against real DDL before
-- trusting it the way AwardExtension's own unconfirmed FK situation
-- is already flagged.
--
-- archive.award_transmission: one row per transmission ATTEMPT (both
-- success and failure are preserved; retransmission always creates a
-- new row, never overwrites a prior one - see the assessment's
-- Findings). award_id is the ROOT/primary Award of the transmitted
-- hierarchy at the moment of the attempt - AWARD_ID on real
-- AWARD_TRANSMISSION rows can be REASSIGNED in place to a later Award
-- version by AwardServiceImpl.updateTransmissionHistory (an UPDATE,
-- not a new row) - archived as observed at extraction time, the same
-- "capture what Oracle shows today" discipline used everywhere else
-- in this project. sent_data/returned_data are stored as Postgres
-- TEXT, verbatim, byte-for-byte as extracted - never parsed,
-- normalized, redacted, or regenerated, per explicit instruction; no
-- length limit is imposed since the real Oracle column's true type/
-- width is unconfirmed (OJB declares VARCHAR, which is very unlikely
-- to be OJB's declared type for a full SOAP XML payload - a
-- discrepancy flagged as an open question in the design doc, not
-- silently resolved either way here).
CREATE TABLE IF NOT EXISTS archive.award_transmission (
    transmission_id             BIGINT PRIMARY KEY,
    award_id                    BIGINT NOT NULL
                                     REFERENCES archive.award_version(award_id)
                                     ON DELETE CASCADE,
    award_number                VARCHAR(50),
    sequence_number             INTEGER,

    initiator_id                VARCHAR(50),
    transmitter_id              VARCHAR(50),
    success_indicator           VARCHAR(10),
    transmission_date           DATE,

    sent_data                   TEXT,
    returned_data                TEXT,

    basis_of_payment_code       VARCHAR(10),
    account_type_code           INTEGER,
    sponsor_code                VARCHAR(30),
    method_of_payment_code      VARCHAR(10),
    document_number             VARCHAR(30),

    source_update_timestamp     TIMESTAMP,
    source_update_user          VARCHAR(100),
    source_version_number       BIGINT,

    loaded_at                   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                     BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_transmission_award
    ON archive.award_transmission (award_id, transmission_id);

-- archive.award_transmission_child: one row per hierarchy-child Award
-- included in a specific transmission attempt. award_id here is the
-- CHILD Award, which routinely belongs to a DIFFERENT award_number
-- family than the parent transmission's own award_id - the same
-- cross-award_number-family relationship already handled elsewhere in
-- this project via bare, unenforced reference columns (e.g.
-- archive.award_hierarchy.parent_award_number). transmission_id is
-- therefore also kept as a BARE, unenforced column (no Postgres FK to
-- archive.award_transmission) rather than a hard FK: this project's
-- own per-award incremental loading (--load-award-id/--load-batch)
-- cannot guarantee the parent transmission's own root Award family has
-- already been loaded before a given child Award's family is loaded -
-- unlike every other two-level child relationship in this domain
-- (e.g. pending_transaction_extension -> pending_transaction), which
-- stays within one award_number family and is always read together in
-- the same bounded call. overhead_key/base_code/off_campus are the
-- ACTUAL F&A rate basis values used for this specific transmission -
-- per the assessment's central finding, these are frequently copied
-- forward from a PRIOR transmission's own child row rather than
-- recomputed from current Budget data, and are therefore
-- unrecoverable from any other archived table once the source budget
-- has moved past "to be posted".
CREATE TABLE IF NOT EXISTS archive.award_transmission_child (
    transmission_child_id       BIGINT PRIMARY KEY,
    transmission_id             BIGINT,
    award_id                    BIGINT NOT NULL
                                     REFERENCES archive.award_version(award_id)
                                     ON DELETE CASCADE,
    award_number                VARCHAR(50),
    sequence_number             INTEGER,

    parent_document_number      VARCHAR(30),
    child_document_number       VARCHAR(30),
    lead_unit_number            VARCHAR(30),
    child_type                  VARCHAR(50),

    overhead_key                VARCHAR(20),
    base_code                   VARCHAR(20),
    off_campus                  VARCHAR(10),

    source_update_timestamp     TIMESTAMP,
    source_update_user          VARCHAR(100),
    source_version_number       BIGINT,

    loaded_at                   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                     BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_transmission_child_award
    ON archive.award_transmission_child (award_id, transmission_child_id);

CREATE INDEX IF NOT EXISTS ix_award_transmission_child_transmission
    ON archive.award_transmission_child (transmission_id);
