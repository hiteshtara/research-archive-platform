-- Corrective migration: adds the two scalar AWARD-level fields
-- (Award.basisOfPaymentCode/Award.methodOfPaymentCode, confirmed at
-- coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml
-- lines 82/84) that AWARD_TERMS_DESIGN.md deliberately deferred as a
-- TRUNCATE-path change out of scope for that bundle. V011 already
-- shipped and is not being rewritten - see the same precedent set by
-- V013 (is_primary_current, added via its own later ALTER).
--
-- BASIS_OF_PAYMENT_CODE/METHOD_OF_PAYMENT_CODE are both real,
-- OJB-mapped VARCHAR2(3) columns directly on AWARD - not INTEGER like
-- STATUS_CODE/TRANSACTION_TYPE_CODE, so they are never numeric-
-- converted in the ETL despite looking like digit codes (a leading
-- zero, e.g. "01", would be lost). AWARD_BASIS_OF_PAYMENT/
-- AWARD_METHOD_OF_PAYMENT are pure code+description lookup tables (no
-- AWARD_ID, no other relationship to Award) - the *_description
-- columns here are denormalized snapshots taken at extraction time via
-- LEFT JOIN, the same convention already used for
-- status_description/transaction_type in 01_award_versions.sql, not
-- values resolved dynamically at read time. No BU-specific override of
-- either lookup table or of AWARD's own columns was found in bu-db/.
--
-- See docs/architecture/AWARD_TERMS_DESIGN.md's Decisions section for
-- why this was deferred, and
-- docs/architecture/AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md for the
-- full field-level mapping this migration implements.

ALTER TABLE archive.award_version
    ADD COLUMN IF NOT EXISTS basis_of_payment_code VARCHAR(10),
    ADD COLUMN IF NOT EXISTS basis_of_payment_description VARCHAR(300),
    ADD COLUMN IF NOT EXISTS method_of_payment_code VARCHAR(10),
    ADD COLUMN IF NOT EXISTS method_of_payment_description VARCHAR(300);
