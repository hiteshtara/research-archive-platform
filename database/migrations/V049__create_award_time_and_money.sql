-- Award Time and Money: the full subsystem, archived together as one
-- coherent bundle per docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md.
-- Reuses the already-archived archive.award_amount_info as the anchor
-- (see V048 for its two new Time-and-Money columns) rather than
-- duplicating it.
--
-- Every table below is keyed by AWARD_NUMBER (or, for
-- pending_transaction/pending_transaction_extension, by
-- SOURCE_AWARD_NUMBER/DESTINATION_AWARD_NUMBER), never AWARD_ID - none
-- of these tables carry a version-specific sequence_number tie except
-- transaction_detail and award_direct_fanda_distribution, which do.
-- No table in this migration has an Oracle-enforced FK to any other
-- (confirmed via repository-timeandmoney.xml/repository-award.xml -
-- every relationship here is Java/OJB-layer only), so archive FKs are
-- added only where the referenced row is guaranteed to already exist
-- earlier in the same load (pending_transaction_extension ->
-- pending_transaction; award_direct_fanda_distribution ->
-- award_version/award_amount_info, both already-archived anchors) and
-- left as bare reference columns everywhere else.

-- AWARD_HIERARCHY: real, Oracle-PK-enforced parent/child Award
-- relationship (reclassified from NOT APPLICABLE - see
-- KUALI_ARCHIVE_COVERAGE.md). Version-agnostic - no sequence_number
-- column exists on the Oracle table at all, by the Java class's own
-- documented contract ("should always reference the active version of
-- the Award if one is present"). Soft-delete only via ACTIVE (Y/N,
-- kept as raw text per this project's OjbCharBooleanConversion
-- convention), flipped false only when an Award's first
-- (sequence_number=1) document is cancelled - no physical DELETE found
-- anywhere. Cycles are structurally impossible: every child row is
-- created with a freshly-generated award_number that cannot already
-- exist as an ancestor.
CREATE TABLE IF NOT EXISTS archive.award_hierarchy (
    award_hierarchy_id BIGINT PRIMARY KEY,
    root_award_number VARCHAR(50) NOT NULL,
    award_number VARCHAR(50) NOT NULL,
    parent_award_number VARCHAR(50) NOT NULL,
    originating_award_number VARCHAR(50) NOT NULL,
    active VARCHAR(10),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_hierarchy_award_number
    ON archive.award_hierarchy (award_number);

CREATE INDEX IF NOT EXISTS ix_award_hierarchy_parent
    ON archive.award_hierarchy (parent_award_number);


-- TIME_AND_MONEY_DOCUMENT: a real KEW workflow document, the same
-- shape as AWARD_DOCUMENT - PK is a KEW-assigned document_number, not a
-- surrogate sequence. document_status and creation_date were both
-- added by later migrations (generic Kuali V1507_016 and BU-specific
-- V1608_096 respectively) - creation_date in particular is a genuine
-- BU customization ("Add Creation Date to T&M Document. Required for
-- sorting purposes."), backfilled from UPDATE_TIMESTAMP at the time it
-- was added.
CREATE TABLE IF NOT EXISTS archive.time_and_money_document (
    document_number VARCHAR(30) PRIMARY KEY,
    root_award_number VARCHAR(50) NOT NULL,
    document_status VARCHAR(20),
    creation_date TIMESTAMP,

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_time_and_money_document_award
    ON archive.time_and_money_document (root_award_number);


-- PENDING_TRANSACTIONS: in-flight/working state for an
-- unapproved-or-just-approved Time and Money document (see the design
-- doc's "Pending vs. history" finding - whether Oracle retains these
-- rows indefinitely after processedFlag='Y' is an open question, not
-- resolved here; archived regardless per explicit scope). Keyed by
-- SOURCE_AWARD_NUMBER/DESTINATION_AWARD_NUMBER, not a bare
-- AWARD_NUMBER - a transaction belongs to a loaded Award if it appears
-- on either side.
CREATE TABLE IF NOT EXISTS archive.pending_transaction (
    transaction_id BIGINT PRIMARY KEY,
    document_number VARCHAR(30),
    source_award_number VARCHAR(50) NOT NULL,
    destination_award_number VARCHAR(50) NOT NULL,

    obligated_amount NUMERIC(14, 2),
    obligated_direct_amount NUMERIC(14, 2),
    obligated_indirect_amount NUMERIC(14, 2),
    anticipated_amount NUMERIC(14, 2),
    anticipated_direct_amount NUMERIC(14, 2),
    anticipated_indirect_amount NUMERIC(14, 2),

    comments TEXT,
    processed_flag VARCHAR(10),
    single_node_transaction VARCHAR(10),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_pending_transaction_source
    ON archive.pending_transaction (source_award_number);

CREATE INDEX IF NOT EXISTS ix_pending_transaction_destination
    ON archive.pending_transaction (destination_award_number);

CREATE INDEX IF NOT EXISTS ix_pending_transaction_document
    ON archive.pending_transaction (document_number);


-- PENDING_TRANSACTIONS_EXTENSION: BU-specific 1:1 extension
-- (bu-db/BUKR-0020: "add_budget_period_to_tm.sql"), no OJB
-- update_timestamp/update_user/versionNumber fields at all - Oracle's
-- own table genuinely has only TRANSACTION_ID + BUDGET_PERIOD, no
-- provenance columns to capture. budget_period here is VARCHAR2(30) -
-- a different physical type than award_direct_fanda_distribution's own
-- NUMBER(3) budget_period below; both are bare, unenforced references
-- to whatever Budget subsystem eventually gets archived (Tier 2, still
-- deferred) - do not assume they can be joined or compared without
-- normalizing type first.
CREATE TABLE IF NOT EXISTS archive.pending_transaction_extension (
    transaction_id BIGINT PRIMARY KEY
        REFERENCES archive.pending_transaction(transaction_id)
        ON DELETE CASCADE,
    budget_period VARCHAR(50),

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);


-- TRANSACTION_DETAILS: the durable, permanent history ledger (as
-- opposed to pending_transaction's working state) - one or more rows
-- written per approved PendingTransaction, classified PRIMARY/
-- INTERMEDIATE/DATE via transaction_detail_type (plain text, no lookup
-- table). transaction_id is a soft reference to the originating
-- PendingTransaction.transaction_id (confirmed only via Java, no
-- Oracle constraint) - kept unenforced since a detail row could
-- reference a PendingTransaction this project's Oracle checkout may
-- not retain (see the open question above). sequence_number is the
-- CURRENT/root Award's version at approval time, not necessarily the
-- version of source_award_number/destination_award_number specifically.
CREATE TABLE IF NOT EXISTS archive.transaction_detail (
    transaction_detail_id BIGINT PRIMARY KEY,
    award_number VARCHAR(50) NOT NULL,
    sequence_number INTEGER NOT NULL,
    transaction_id BIGINT NOT NULL,
    time_and_money_document_number VARCHAR(30) NOT NULL,
    source_award_number VARCHAR(50) NOT NULL,
    destination_award_number VARCHAR(50) NOT NULL,

    obligated_amount NUMERIC(14, 2),
    obligated_direct_amount NUMERIC(14, 2),
    obligated_indirect_amount NUMERIC(14, 2),
    anticipated_amount NUMERIC(14, 2),
    anticipated_direct_amount NUMERIC(14, 2),
    anticipated_indirect_amount NUMERIC(14, 2),

    comments VARCHAR(500),
    transaction_detail_type VARCHAR(20),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_transaction_detail_award
    ON archive.transaction_detail (award_number, sequence_number);

CREATE INDEX IF NOT EXISTS ix_transaction_detail_transaction
    ON archive.transaction_detail (transaction_id);

CREATE INDEX IF NOT EXISTS ix_transaction_detail_document
    ON archive.transaction_detail (time_and_money_document_number);


-- AWARD_AMOUNT_TRANSACTION: one row per (Time and Money document,
-- affected Award) pair - confirmed directly from
-- ActivePendingTransactionsServiceImpl's own comment. Oracle's own
-- TRANSACTION_ID column here is VARCHAR2(10), NOT the numeric
-- transaction_id used everywhere else in this bundle - it actually
-- stores the Time and Money DOCUMENT NUMBER (confirmed by both the
-- OJB field name, documentNumber, and matching column width against
-- TIME_AND_MONEY_DOCUMENT.DOCUMENT_NUMBER). Renamed here to
-- document_number at the archive boundary so the numeric and character
-- "TRANSACTION_ID" concepts are never exposed under the same archive
-- field name. transaction_type_code reuses the exact same
-- AWARD_TRANSACTION_TYPE lookup table already denormalized for
-- Award.transaction_type_code in 01_award_versions.sql.
-- Oracle's own UQ_AWARD_AMOUNT_TRANSACTIONS index on
-- (AWARD_NUMBER, TRANSACTION_ID) is NOT a unique constraint despite its
-- name (a plain CREATE INDEX) - not enforced as unique here either.
CREATE TABLE IF NOT EXISTS archive.award_amount_transaction (
    award_amount_transaction_id BIGINT PRIMARY KEY,
    award_number VARCHAR(50) NOT NULL,
    document_number VARCHAR(30) NOT NULL,
    transaction_type_code VARCHAR(10),
    transaction_type_description VARCHAR(300),
    notice_date DATE,
    comments TEXT,

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_amount_transaction_award
    ON archive.award_amount_transaction (award_number, document_number);


-- AWARD_AMT_FNA_DISTRIBUTION: real child of BOTH Award and
-- AwardAmountInfo (an explicit OJB reference-descriptor FK to
-- AWARD_AMOUNT_INFO_ID confirms this, unlike most other Time and Money
-- relationships) - per-budget-period F&A cost breakdown. award_id is
-- nullable in Oracle's own base DDL (no NOT NULL constraint found).
-- budget_period here is NUMBER(3) - see the type-mismatch note on
-- pending_transaction_extension above.
CREATE TABLE IF NOT EXISTS archive.award_direct_fanda_distribution (
    award_direct_fanda_distribution_id BIGINT PRIMARY KEY,
    award_id BIGINT REFERENCES archive.award_version(award_id) ON DELETE CASCADE,
    award_number VARCHAR(50),
    sequence_number INTEGER,
    amount_sequence_number INTEGER,
    award_amount_info_id BIGINT
        REFERENCES archive.award_amount_info(award_amount_info_id)
        ON DELETE CASCADE,
    budget_period INTEGER,
    start_date DATE,
    end_date DATE,
    direct_cost NUMERIC(14, 2),
    indirect_cost NUMERIC(14, 2),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_direct_fanda_distribution_award
    ON archive.award_direct_fanda_distribution (award_id);

CREATE INDEX IF NOT EXISTS ix_award_direct_fanda_distribution_number
    ON archive.award_direct_fanda_distribution (award_number, sequence_number);
