-- Local SAML integration - Cognito simulated: ARCHIVE-SPECIFIC lab tables.
-- Applied after the migrations and the synthetic seed, on the disposable lab
-- database only. All rows FICTIONAL.
BEGIN;
CREATE SCHEMA IF NOT EXISTS identity_lab;

-- KIM-SHAPED identity layer (FICTIONAL). Mirrors the minimal KRIM_PRNCPL_T columns the
-- real extract would carry (KUALI_IDENTITY_MINIMAL_EXTRACT.md, Tier B): PRNCPL_ID is the
-- value Award/Proposal contact rows store as PERSON_ID. No names, emails or login names.
CREATE TABLE identity_lab.kim_principal (
    prncpl_id  TEXT PRIMARY KEY,
    entity_id  TEXT NOT NULL,
    actv_ind   CHAR(1) NOT NULL CHECK (actv_ind IN ('Y', 'N')),
    source     TEXT NOT NULL DEFAULT 'FICTIONAL'
);
INSERT INTO identity_lab.kim_principal (prncpl_id, entity_id, actv_ind) VALUES
  ('SYNP-CEN-01', 'SYNE-01', 'Y'), ('SYNP-DEP-01', 'SYNE-02', 'Y'), ('SYNP-PI-01', 'SYNE-03', 'Y'),
  ('SYNP-OAV-01', 'SYNE-05', 'Y'), ('SYNP-MULTI-01', 'SYNE-06', 'Y'), ('SYNP-NG-01', 'SYNE-07', 'Y'),
  ('SYNP-SUS-01', 'SYNE-09', 'Y'), ('SYNP-REUSE-01', 'SYNE-10', 'Y'),
  ('SYNP-KIM-11', 'SYNE-11', 'Y'),   -- PI on Award J; holds NO archive grant rows
  ('SYNP-KIM-12', 'SYNE-12', 'Y'),   -- a KIM account that is nobody's contact
  ('SYNP-KIM-13', 'SYNE-13', 'N'),   -- inactive principal (departed)
  ('SYNP-KIM-14', 'SYNE-14', 'Y'), ('SYNP-KIM-15', 'SYNE-15', 'Y');  -- targets of an ambiguous mapping

-- Crosswalk: VERIFIED Shibboleth attribute value -> KIM principal. Import target for the
-- real crosswalk (identity-lab/archive/kim/CROSSWALK_FORMAT.md). Lives in the application
-- database, never in Shibboleth. Not unique on purpose, so the runtime ambiguity rule is
-- exercised; the import validator rejects duplicates before they get here.
CREATE TABLE identity_lab.principal_crosswalk (
    crosswalk_id     BIGSERIAL PRIMARY KEY,
    attribute_name   TEXT NOT NULL,
    attribute_value  TEXT NOT NULL,
    prncpl_id        TEXT NOT NULL,
    status           TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    evidence_ref     TEXT NOT NULL,
    verified_by      TEXT NOT NULL,
    verified_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO identity_lab.principal_crosswalk (attribute_name, attribute_value, prncpl_id, evidence_ref, verified_by)
SELECT 'labInstitutionalId', v, p, 'FICTIONAL fixture', 'lab-seed' FROM (VALUES
  ('SYN-INST-0001', 'SYNP-CEN-01'), ('SYN-INST-0002', 'SYNP-DEP-01'), ('SYN-INST-0003', 'SYNP-PI-01'),
  ('SYN-INST-0005', 'SYNP-OAV-01'), ('SYN-INST-0006', 'SYNP-MULTI-01'), ('SYN-INST-0007', 'SYNP-NG-01'),
  ('SYN-INST-0009', 'SYNP-SUS-01'), ('SYN-INST-0010', 'SYNP-REUSE-01'),
  ('SYN-INST-0011', 'SYNP-KIM-11'), ('SYN-INST-0012', 'SYNP-KIM-12'), ('SYN-INST-0013', 'SYNP-KIM-13'),
  ('SYN-INST-0014', 'SYNP-KIM-14'), ('SYN-INST-0014', 'SYNP-KIM-15'),   -- ambiguous: one value, two principals
  ('SYN-INST-0016', '990001')                                           -- a rolodex (non-employee) id
) AS t(v, p);

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

CREATE TABLE identity_lab.enrollment_event (
    event_id                 BIGSERIAL PRIMARY KEY,
    occurred_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    cognito_issuer           TEXT NOT NULL,
    cognito_subject          TEXT NOT NULL,
    institutional_identifier TEXT,
    outcome                  TEXT NOT NULL,
    detail                   JSONB NOT NULL
);

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
