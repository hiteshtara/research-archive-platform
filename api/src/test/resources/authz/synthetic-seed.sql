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

-- Section-scoping fixtures (Award section endpoints, reports, File Finder).
-- Two extra NON-current versions nobody but Central can open (other unit, no
-- Pat): A' (9000103, shares A's sequence 2) and C' (9000302). They make the
-- A and C families only PARTLY visible to the restricted personas.
INSERT INTO archive.award_version (award_id, award_number, sequence_number, award_sequence_status,
    status_description, title, sponsor_code, sponsor_name, lead_unit_number, lead_unit_name,
    account_number, award_effective_date, workflow_document_number, is_current_version, is_primary_current) VALUES
  (9000103, '990001-00001', 2, 'ARCHIVED', 'Active', 'SYNTHETIC Award A - other-unit version', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-200', 'SYNTHETIC Department of Others', NULL, DATE '2020-01-01', 'SYN-DOC-0103', FALSE, FALSE),
  (9000302, '990003-00001', 1, 'ARCHIVED', 'Active', 'SYNTHETIC Award C - other-unit version', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-200', 'SYNTHETIC Department of Others', NULL, DATE '2021-02-01', 'SYN-DOC-0302', FALSE, FALSE);
INSERT INTO archive.award_person (award_person_id, award_id, award_number, sequence_number, person_id, rolodex_id,
    full_name, contact_role_code) VALUES
  (9100015, 9000103, '990001-00001', 2, 'SYNP-OTHER-03', NULL, 'LEE UNRELATED', 'PI'),
  (9100016, 9000302, '990003-00001', 1, 'SYNP-OTHER-03', NULL, 'LEE UNRELATED', 'PI');

-- Hierarchy: D (990004-00001) is a child of B (990002-00001).
INSERT INTO archive.award_hierarchy (award_hierarchy_id, root_award_number, award_number, parent_award_number,
    originating_award_number, active) VALUES
  (9200003, '990002-00001', '990002-00001', '990002-00001', '990002-00001', 'Y'),
  (9200004, '990002-00001', '990004-00001', '990002-00001', '990002-00001', 'Y');

-- Amounts: one row per A version.
INSERT INTO archive.award_amount_info (award_amount_info_id, award_id, award_number, sequence_number,
    obligated_total_amount, anticipated_total_amount, tnm_document_number, transaction_id) VALUES
  (9700101, 9000101, '990001-00001', 1, 100.00, 100.00, 'SYN-TNM-A1', 9710001),
  (9700102, 9000102, '990001-00001', 2, 200.00, 200.00, NULL, NULL),
  (9700103, 9000103, '990001-00001', 2, 300.00, 300.00, NULL, NULL);

-- Time and Money: a document per hierarchy family (root A, root B).
INSERT INTO archive.time_and_money_document (document_number, root_award_number, document_status, creation_date) VALUES
  ('SYN-TNM-A1', '990001-00001', 'FINAL', TIMESTAMP '2020-02-01 00:00:00'),
  ('SYN-TNM-B1', '990002-00001', 'FINAL', TIMESTAMP '2021-02-01 00:00:00');
INSERT INTO archive.pending_transaction (transaction_id, document_number, source_award_number,
    destination_award_number, obligated_amount, comments) VALUES
  (9710001, 'SYN-TNM-A1', '990001-00001', '990001-00001', 100.00, 'SYNTHETIC A to A'),
  (9710002, 'SYN-TNM-A1', '990001-00001', '990001-00002', 50.00, 'SYNTHETIC A to its child'),
  (9710003, 'SYN-TNM-B1', '990002-00001', '990002-00001', 999.00, 'SYNTHETIC B only');
INSERT INTO archive.transaction_detail (transaction_detail_id, award_number, sequence_number, transaction_id,
    time_and_money_document_number, source_award_number, destination_award_number, obligated_amount) VALUES
  (9720001, '990002-00001', 1, 9710003, 'SYN-TNM-B1', '990002-00001', '990002-00001', 999.00);
INSERT INTO archive.award_amount_transaction (award_amount_transaction_id, award_number, document_number,
    transaction_type_code, transaction_type_description, notice_date, comments) VALUES
  (9730001, '990001-00001', 'SYN-TNM-A1', '1', 'SYNTHETIC New', DATE '2020-02-01', 'SYNTHETIC A action'),
  (9730002, '990004-00001', 'SYN-TNM-B1', '1', 'SYNTHETIC New', DATE '2021-02-01', 'SYNTHETIC D action');

-- Comments and notepad.
INSERT INTO archive.comment_type (comment_type_code, description, award_comment_screen_flag) VALUES
  ('SYN1', 'SYNTHETIC General Comments', 'Y');
INSERT INTO archive.award_comment (award_comment_id, award_id, award_number, sequence_number, comment_type_code, comments) VALUES
  (9740001, 9000101, '990001-00001', 1, 'SYN1', 'SYNTHETIC comment on A seq 1'),
  (9740002, 9000103, '990001-00001', 2, 'SYN1', 'SYNTHETIC comment on the other-unit A version');
INSERT INTO archive.award_notepad (award_notepad_id, award_id, award_number, entry_number, note_topic, comments,
    restricted_view) VALUES
  (9741001, 9000102, '990001-00001', 1, 'SYNTHETIC A note', 'SYNTHETIC family note on A', 'N'),
  (9741002, 9000401, '990004-00001', 1, 'SYNTHETIC D note', 'SYNTHETIC open note on D', 'N'),
  (9741003, 9000401, '990004-00001', 2, 'SYNTHETIC D restricted', 'SYNTHETIC restricted note on D', 'Y');

-- Budgets: a Posted budget on A seq 1 and a newer Posted one on the other-unit A version.
INSERT INTO archive.award_budget (budget_id, award_id, document_number, award_budget_status_code,
    award_budget_status_description, budget_version_number, total_cost) VALUES
  (9750001, 9000101, 'SYN-BDOC-01', '9', 'Posted', 1, 1000.00),
  (9750003, 9000103, 'SYN-BDOC-03', '9', 'Posted', 2, 3000.00);

-- Funding-proposal link made on C's other-unit version, to a Proposal Pat can open.
INSERT INTO archive.award_funding_proposal (award_funding_proposal_id, award_id, proposal_id, active_flag) VALUES
  (9400002, 9000302, 8000201, 'Y');

-- SAP transmissions on A: one also covers the child 990001-00002, one does not.
INSERT INTO archive.award_transmission (transmission_id, award_id, award_number, sequence_number, success_indicator,
    transmission_date, sent_data, returned_data) VALUES
  (9760001, 9000102, '990001-00001', 2, 'Y', DATE '2022-01-01', '<syn-sent-hierarchy/>', '<syn-returned-hierarchy/>'),
  (9760002, 9000102, '990001-00001', 2, 'Y', DATE '2022-02-01', '<syn-sent-a-only/>', '<syn-returned-a-only/>');
INSERT INTO archive.award_transmission_child (transmission_child_id, transmission_id, award_id, award_number,
    sequence_number, lead_unit_number) VALUES
  (9770001, 9760001, 9000102, '990001-00001', 2, 'SYN-U-100'),
  (9770002, 9760001, 9000111, '990001-00002', 1, 'SYN-U-100'),
  (9770003, 9760002, 9000102, '990001-00001', 2, 'SYN-U-100');

-- An attachment on the other-unit A version (File Finder).
INSERT INTO archive.attachment_object (file_id, file_name, content_type, upload_status) VALUES
  (9300003, 'SYNTHETIC-award-A-other-unit.pdf', 'application/pdf', 'PENDING');
INSERT INTO archive.award_attachment (award_attachment_id, award_id, award_number, sequence_number, file_id,
    type_code, description) VALUES
  (9300003, 9000103, '990001-00001', 2, 9300003, '1', 'SYNTHETIC attachment on the other-unit A version');

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
