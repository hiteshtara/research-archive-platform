-- Local SAML integration - Cognito simulated: ARCHIVE-SPECIFIC lab tables.
-- Applied after the migrations and the synthetic seed, on the disposable lab
-- database only. All rows FICTIONAL.
BEGIN;
CREATE SCHEMA IF NOT EXISTS identity_lab;

-- Crosswalk: institutional identifier -> Kuali person id. Lives in the lab's
-- application database, never in Shibboleth.
CREATE TABLE identity_lab.person_registry (
    institutional_identifier TEXT PRIMARY KEY,
    kuali_person_id          TEXT NOT NULL,
    source                   TEXT NOT NULL DEFAULT 'FICTIONAL'
);
INSERT INTO identity_lab.person_registry (institutional_identifier, kuali_person_id) VALUES
  ('SYN-INST-0001', 'SYNP-CEN-01'),
  ('SYN-INST-0002', 'SYNP-DEP-01'),
  ('SYN-INST-0003', 'SYNP-PI-01'),
  ('SYN-INST-0005', 'SYNP-OAV-01'),
  ('SYN-INST-0006', 'SYNP-MULTI-01'),
  ('SYN-INST-0007', 'SYNP-NG-01'),
  ('SYN-INST-0009', 'SYNP-SUS-01'),
  ('SYN-INST-0010', 'SYNP-REUSE-01');   -- a different person who later reuses a login name

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
