-- Synthetic identity demo - BU federation not connected.
-- DISPOSABLE DEMO DATABASE ONLY. Every person, unit, IO value and record here
-- is invented. Award numbers use the fictional 990xxx range and every title
-- starts with "SYNTHETIC". Never load this into a real archive database.

BEGIN;

-- Demo-only tables (not migrations; they never exist outside the demo DB).
CREATE SCHEMA IF NOT EXISTS authz_demo;
CREATE TABLE authz_demo.persona (
    persona_key       TEXT PRIMARY KEY,
    label             TEXT NOT NULL,
    attachment_viewer BOOLEAN NOT NULL,
    description       TEXT NOT NULL,
    sort_order        INTEGER NOT NULL
);
-- Synthetic IO values per award version. The REAL IO field is unresolved
-- (decision D-A); this table exists only so the demo can exercise IO grants.
CREATE TABLE authz_demo.award_io (
    award_id BIGINT NOT NULL,
    io_value TEXT NOT NULL,
    PRIMARY KEY (award_id, io_value)
);

-- Units: SYN-U-110 is a child of SYN-U-100.
INSERT INTO archive.unit (unit_number, unit_name, parent_unit_number, active) VALUES
  ('SYN-U-001', 'SYNTHETIC University', NULL, TRUE),
  ('SYN-U-100', 'SYNTHETIC Department of Examples', 'SYN-U-001', TRUE),
  ('SYN-U-110', 'SYNTHETIC Example Sub-unit', 'SYN-U-100', TRUE),
  ('SYN-U-200', 'SYNTHETIC Department of Others', 'SYN-U-001', TRUE),
  ('SYN-U-300', 'SYNTHETIC Department of Collaborations', 'SYN-U-001', TRUE),
  ('SYN-U-400', 'SYNTHETIC Department of Viewers', 'SYN-U-001', TRUE),
  ('SYN-U-500', 'SYNTHETIC Department of Proposals', 'SYN-U-001', TRUE);

-- Awards. A has two versions; A's child, B, C..H have one each.
INSERT INTO archive.award_version (award_id, award_number, sequence_number, award_sequence_status,
    status_description, title, sponsor_code, sponsor_name, lead_unit_number, lead_unit_name,
    account_number, award_effective_date, workflow_document_number, is_current_version, is_primary_current) VALUES
  (9000101, '990001-00001', 1, 'ARCHIVED', 'Active', 'SYNTHETIC Award A - original', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-100', 'SYNTHETIC Department of Examples', NULL, DATE '2020-01-01', 'SYN-DOC-0101', FALSE, FALSE),
  (9000102, '990001-00001', 2, 'ACTIVE', 'Active', 'SYNTHETIC Award A - PI is Pat Example', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-100', 'SYNTHETIC Department of Examples', NULL, DATE '2020-01-01', 'SYN-DOC-0102', TRUE, TRUE),
  (9000111, '990001-00002', 1, 'ACTIVE', 'Active', 'SYNTHETIC Award A child - different PI', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-100', 'SYNTHETIC Department of Examples', NULL, DATE '2020-06-01', 'SYN-DOC-0111', TRUE, TRUE),
  (9000201, '990002-00001', 1, 'ACTIVE', 'Active', 'SYNTHETIC Award B - unrelated', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-200', 'SYNTHETIC Department of Others', NULL, DATE '2021-01-01', 'SYN-DOC-0201', TRUE, TRUE),
  (9000301, '990003-00001', 1, 'ACTIVE', 'Active', 'SYNTHETIC Award C - Pat is Co-PI (MPI)', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-300', 'SYNTHETIC Department of Collaborations', NULL, DATE '2021-02-01', 'SYN-DOC-0301', TRUE, TRUE),
  (9000401, '990004-00001', 1, 'ACTIVE', 'Active', 'SYNTHETIC Award D - Pat is Co-Investigator', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-300', 'SYNTHETIC Department of Collaborations', NULL, DATE '2021-03-01', 'SYN-DOC-0401', TRUE, TRUE),
  (9000501, '990005-00001', 1, 'ACTIVE', 'Active', 'SYNTHETIC Award E - Pat is Key Person only', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-300', 'SYNTHETIC Department of Collaborations', NULL, DATE '2021-04-01', 'SYN-DOC-0501', TRUE, TRUE),
  (9000601, '990006-00001', 1, 'ACTIVE', 'Active', 'SYNTHETIC Award F - carries IO SYN-IO-7001', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-400', 'SYNTHETIC Department of Viewers', NULL, DATE '2021-05-01', 'SYN-DOC-0601', TRUE, TRUE),
  (9000701, '990007-00001', 1, 'ACTIVE', 'Active', 'SYNTHETIC Award G - in a sub-unit of the department', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-110', 'SYNTHETIC Example Sub-unit', NULL, DATE '2021-06-01', 'SYN-DOC-0701', TRUE, TRUE),
  (9000801, '990008-00001', 1, 'ACTIVE', 'Active', 'SYNTHETIC Award H - carries IO SYN-IO-7002', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-200', 'SYNTHETIC Department of Others', NULL, DATE '2021-07-01', 'SYN-DOC-0801', TRUE, TRUE);

-- People. Pat Example = employee SYNP-PI-01. A rolodex (non-employee) COI on A.
INSERT INTO archive.award_person (award_person_id, award_id, award_number, sequence_number, person_id, rolodex_id,
    full_name, contact_role_code) VALUES
  (9100001, 9000101, '990001-00001', 1, 'SYNP-PI-01', NULL, 'PAT EXAMPLE', 'PI'),
  (9100002, 9000102, '990001-00001', 2, 'SYNP-PI-01', NULL, 'PAT EXAMPLE', 'PI'),
  (9100003, 9000102, '990001-00001', 2, NULL, 990001, 'ROBIN OUTSIDECOLLAB', 'COI'),
  (9100004, 9000111, '990001-00002', 1, 'SYNP-OTHER-02', NULL, 'SAM OTHERPI', 'PI'),
  (9100005, 9000201, '990002-00001', 1, 'SYNP-OTHER-03', NULL, 'LEE UNRELATED', 'PI'),
  (9100006, 9000301, '990003-00001', 1, 'SYNP-OTHER-04', NULL, 'KIM LEADPI', 'PI'),
  (9100007, 9000301, '990003-00001', 1, 'SYNP-PI-01', NULL, 'PAT EXAMPLE', 'MPI'),
  (9100008, 9000401, '990004-00001', 1, 'SYNP-OTHER-04', NULL, 'KIM LEADPI', 'PI'),
  (9100009, 9000401, '990004-00001', 1, 'SYNP-PI-01', NULL, 'PAT EXAMPLE', 'COI'),
  (9100010, 9000501, '990005-00001', 1, 'SYNP-OTHER-04', NULL, 'KIM LEADPI', 'PI'),
  (9100011, 9000501, '990005-00001', 1, 'SYNP-PI-01', NULL, 'PAT EXAMPLE', 'KP'),
  (9100012, 9000601, '990006-00001', 1, 'SYNP-OTHER-05', NULL, 'JO VIEWEDPI', 'PI'),
  (9100013, 9000701, '990007-00001', 1, 'SYNP-OTHER-06', NULL, 'AL SUBUNITPI', 'PI'),
  (9100014, 9000801, '990008-00001', 1, 'SYNP-OTHER-07', NULL, 'MO IOPI', 'PI');

-- Hierarchy: A (990001-00001) is the parent of 990001-00002.
INSERT INTO archive.award_hierarchy (award_hierarchy_id, root_award_number, award_number, parent_award_number,
    originating_award_number, active) VALUES
  (9200001, '990001-00001', '990001-00001', '990001-00001', '990001-00001', 'Y'),
  (9200002, '990001-00001', '990001-00002', '990001-00001', '990001-00001', 'Y');

-- Attachments (metadata only; the demo has no stored files).
INSERT INTO archive.attachment_object (file_id, file_name, content_type, upload_status) VALUES
  (9300001, 'SYNTHETIC-award-A.pdf', 'application/pdf', 'PENDING'),
  (9300002, 'SYNTHETIC-award-B.pdf', 'application/pdf', 'PENDING');
INSERT INTO archive.award_attachment (award_attachment_id, award_id, award_number, sequence_number, file_id,
    type_code, description) VALUES
  (9300001, 9000102, '990001-00001', 2, 9300001, '1', 'SYNTHETIC attachment on Award A'),
  (9300002, 9000201, '990002-00001', 1, 9300002, '1', 'SYNTHETIC attachment on Award B');

-- Proposals: P1 funds Award A (Pat not listed); P2 Pat is PI; P3 in the department unit.
INSERT INTO archive.proposal_version (proposal_id, proposal_number, version_number, title, proposal_sequence_status,
    sponsor_code, sponsor_name, lead_unit_number, lead_unit_name, principal_investigator_name, document_number) VALUES
  (8000101, 'SYN-PRP-0001', 1, 'SYNTHETIC Proposal 1 - related to Award A', 'ACTIVE', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-300', 'SYNTHETIC Department of Collaborations', 'KIM LEADPI', 'SYN-PDOC-01'),
  (8000201, 'SYN-PRP-0002', 1, 'SYNTHETIC Proposal 2 - Pat is PI', 'ACTIVE', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-500', 'SYNTHETIC Department of Proposals', 'PAT EXAMPLE', 'SYN-PDOC-02'),
  (8000301, 'SYN-PRP-0003', 1, 'SYNTHETIC Proposal 3 - department unit', 'ACTIVE', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-100', 'SYNTHETIC Department of Examples', 'SAM OTHERPI', 'SYN-PDOC-03');
INSERT INTO archive.proposal_person (proposal_person_id, proposal_id, proposal_number, sequence_number, person_id,
    full_name, contact_role_code) VALUES
  (8100001, 8000101, 'SYN-PRP-0001', 1, 'SYNP-OTHER-04', 'KIM LEADPI', 'PI'),
  (8100002, 8000201, 'SYN-PRP-0002', 1, 'SYNP-PI-01', 'PAT EXAMPLE', 'PI'),
  (8100003, 8000301, 'SYN-PRP-0003', 1, 'SYNP-OTHER-02', 'SAM OTHERPI', 'PI');
INSERT INTO archive.award_funding_proposal (award_funding_proposal_id, award_id, proposal_id, active_flag) VALUES
  (9400001, 9000102, 8000101, 'Y');

-- A Negotiation and a Subaward related to Award A (central-only for now).
INSERT INTO archive.negotiation (negotiation_id, document_number, negotiation_status_description,
    negotiation_association_type_code, negotiation_association_type_description, associated_document_id) VALUES
  (9500001, 'SYN-NDOC-01', 'SYNTHETIC In progress', 'AWD', 'Award', '990001-00001');
INSERT INTO archive.subaward (subaward_id, document_number, sequence_number, subaward_code, title,
    status_description, subaward_sequence_status) VALUES
  (9600001, 'SYN-SDOC-01', 1, 'SYN-SUB-01', 'SYNTHETIC Subaward funded by Award A', 'SYNTHETIC Active', 'ACTIVE');
INSERT INTO archive.subaward_funding (subaward_funding_source_id, subaward_id, subaward_code, sequence_number,
    award_id, award_number) VALUES
  (9610001, 9600001, 'SYN-SUB-01', 1, 9000102, '990001-00001');

-- Synthetic IO values (real IO field unresolved).
INSERT INTO authz_demo.award_io (award_id, io_value) VALUES
  (9000601, 'SYN-IO-7001'),
  (9000801, 'SYN-IO-7002');

-- Personas: synthetic Cognito identities (issuer + sub) and archive grants.
INSERT INTO authz_demo.persona VALUES
  ('central',     'Central user',                       TRUE,  'CENTRAL grant: every record', 1),
  ('department',  'Department user (SYN-U-100)',        TRUE,  'UNIT grant SYN-U-100', 2),
  ('pi',          'Research Staff: Pat Example (PI)',   FALSE, 'Contact-derived: PI on A, Co-PI on C, COI on D, KP on E, PI on Proposal 2. No attachment group', 3),
  ('pi-attach',   'Pat Example + attachment group',     TRUE,  'Same person as "pi", plus ArchiveAttachmentViewer', 4),
  ('oav',         'Other Authorized Viewer (SYN-IO-7001)', TRUE, 'IO grant SYN-IO-7001 (synthetic IO field)', 5),
  ('multi',       'Multiple grants',                    TRUE,  'UNIT SYN-U-300 + IO SYN-IO-7002', 6),
  ('nogrants',    'Signed in, no grants',               TRUE,  'Mapped identity with no active grant', 7),
  ('unknown',     'Unknown identity',                   TRUE,  'Valid sign-in with no identity link', 8),
  ('revoked',     'Revoked mapping',                    TRUE,  'Identity link revoked (had CENTRAL)', 9),
  ('suspended',   'Suspended person',                   TRUE,  'CENTRAL grant but suspended', 10);

INSERT INTO authz.identity_link (cognito_issuer, cognito_subject, institutional_identifier, kuali_person_id,
    login_name, method, status, verified_by, verified_at, revoked_by, revoked_at) VALUES
  ('https://demo.invalid/synthetic-cognito', 'demo-central',    'SYN-INST-0001', 'SYNP-CEN-01',   'syn-central', 'ADMIN_VERIFIED', 'ACTIVE', 'demo-seed', now(), NULL, NULL),
  ('https://demo.invalid/synthetic-cognito', 'demo-department', 'SYN-INST-0002', 'SYNP-DEP-01',   'syn-dept',    'ADMIN_VERIFIED', 'ACTIVE', 'demo-seed', now(), NULL, NULL),
  ('https://demo.invalid/synthetic-cognito', 'demo-pi',         'SYN-INST-0003', 'SYNP-PI-01',    'syn-pat',     'ADMIN_VERIFIED', 'ACTIVE', 'demo-seed', now(), NULL, NULL),
  ('https://demo.invalid/synthetic-cognito', 'demo-pi-attach',  'SYN-INST-0003', 'SYNP-PI-01',    'syn-pat',     'ADMIN_VERIFIED', 'ACTIVE', 'demo-seed', now(), NULL, NULL),
  ('https://demo.invalid/synthetic-cognito', 'demo-oav',        'SYN-INST-0005', 'SYNP-OAV-01',   'syn-oav',     'ADMIN_VERIFIED', 'ACTIVE', 'demo-seed', now(), NULL, NULL),
  ('https://demo.invalid/synthetic-cognito', 'demo-multi',      'SYN-INST-0006', 'SYNP-MULTI-01', 'syn-multi',   'ADMIN_VERIFIED', 'ACTIVE', 'demo-seed', now(), NULL, NULL),
  ('https://demo.invalid/synthetic-cognito', 'demo-nogrants',   'SYN-INST-0007', 'SYNP-NG-01',    'syn-nogrants','ADMIN_VERIFIED', 'ACTIVE', 'demo-seed', now(), NULL, NULL),
  ('https://demo.invalid/synthetic-cognito', 'demo-revoked',    'SYN-INST-0008', 'SYNP-REV-01',   'syn-revoked', 'ADMIN_VERIFIED', 'REVOKED','demo-seed', now(), 'demo-seed', now()),
  ('https://demo.invalid/synthetic-cognito', 'demo-suspended',  'SYN-INST-0009', 'SYNP-SUS-01',   'syn-suspended','ADMIN_VERIFIED','ACTIVE', 'demo-seed', now(), NULL, NULL);

INSERT INTO authz.person_status (institutional_identifier, suspended, changed_by, changed_at, reason) VALUES
  ('SYN-INST-0009', TRUE, 'demo-seed', now(), 'SYNTHETIC suspension');

INSERT INTO authz.access_grant (institutional_identifier, grant_type, unit_number, include_descendants, io_value, granted_by, reason) VALUES
  ('SYN-INST-0001', 'CENTRAL', NULL, FALSE, NULL, 'demo-seed', 'SYNTHETIC'),
  ('SYN-INST-0002', 'UNIT', 'SYN-U-100', TRUE, NULL, 'demo-seed', 'SYNTHETIC'),
  ('SYN-INST-0003', 'CONTACT_DERIVATION', NULL, FALSE, NULL, 'demo-seed', 'SYNTHETIC'),
  ('SYN-INST-0005', 'IO', NULL, FALSE, 'SYN-IO-7001', 'demo-seed', 'SYNTHETIC'),
  ('SYN-INST-0006', 'UNIT', 'SYN-U-300', FALSE, NULL, 'demo-seed', 'SYNTHETIC'),
  ('SYN-INST-0006', 'IO', NULL, FALSE, 'SYN-IO-7002', 'demo-seed', 'SYNTHETIC'),
  ('SYN-INST-0008', 'CENTRAL', NULL, FALSE, NULL, 'demo-seed', 'SYNTHETIC'),
  ('SYN-INST-0009', 'CENTRAL', NULL, FALSE, NULL, 'demo-seed', 'SYNTHETIC');

COMMIT;
