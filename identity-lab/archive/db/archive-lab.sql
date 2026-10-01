-- Local SAML integration - Cognito simulated: ARCHIVE-SPECIFIC lab tables.
-- Applied after the migrations and the synthetic seed, on the disposable lab
-- database only. All rows FICTIONAL.
BEGIN;
CREATE SCHEMA IF NOT EXISTS identity_lab;

-- The KIM principals and the crosswalk live in the REAL authz tables (V083) and are loaded
-- at start with the production admin CLI from fixtures/kim_principals.tsv and
-- fixtures/principal_crosswalk.tsv (validated, all-or-nothing, audited).

-- Award J: Kim PI (SYNP-KIM-11), unit SYN-U-300. Access must come from the contact row
-- alone - no CONTACT_DERIVATION or any other grant exists for SYN-INST-0011.
INSERT INTO archive.award_version (award_id, award_number, sequence_number, award_sequence_status,
    status_description, title, sponsor_code, sponsor_name, lead_unit_number, lead_unit_name,
    account_number, award_effective_date, workflow_document_number, is_current_version, is_primary_current) VALUES
  (9001001, '990010-00001', 1, 'ACTIVE', 'Active', 'SYNTHETIC Award J - PI matched through KIM principal', 'SYN-SP',
   'SYNTHETIC Sponsor', 'SYN-U-300', 'SYNTHETIC Department of Collaborations', NULL, DATE '2023-01-01', 'SYN-DOC-1001',
   TRUE, TRUE);
INSERT INTO archive.award_person (award_person_id, award_id, award_number, sequence_number, person_id, rolodex_id,
    full_name, contact_role_code) VALUES
  (9101001, 9001001, '990010-00001', 1, 'SYNP-KIM-11', NULL, 'KIM PRINCIPALPI', 'PI');

-- Under contact-derivation VERIFIED_PRINCIPAL the seed's explicit CONTACT_DERIVATION grant
-- is unnecessary; remove it so Pat's access also comes from the verified mapping alone.
DELETE FROM authz.access_grant WHERE grant_type = 'CONTACT_DERIVATION';

-- Enrollment decisions are audited by the API in authz.access_audit (action ENROLLMENT_*).

-- Historical Kuali roles: EVIDENCE ONLY. Nothing reads this table to grant
-- access; archive access comes only from authz.access_grant. The lab tests
-- that these rows give no access.
CREATE SCHEMA IF NOT EXISTS lab_evidence;
CREATE TABLE lab_evidence.kuali_role_evidence (
    institutional_identifier TEXT NOT NULL,
    kuali_role_name          TEXT NOT NULL,
    unit_number              TEXT,
    subunits                 BOOLEAN,
    note                     TEXT NOT NULL
);
INSERT INTO lab_evidence.kuali_role_evidence VALUES
  ('SYN-INST-0003', 'Award Viewer (FICTIONAL)',   'SYN-U-200', TRUE, 'would cover Award B in Kuali; must NOT grant archive access'),
  ('SYN-INST-0007', 'Award Modifier (FICTIONAL)', 'SYN-U-001', TRUE, 'would cover every unit in Kuali; must NOT grant archive access');
COMMIT;
