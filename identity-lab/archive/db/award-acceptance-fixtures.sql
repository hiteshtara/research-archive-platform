-- Local SAML integration - Cognito simulated: ARCHIVE-SPECIFIC acceptance fixtures.
-- All rows FICTIONAL. Applied after the shared synthetic seed, on the disposable lab
-- database only, so the Testcontainers suites keep their own expectations.
-- Purpose: every PERMITTED Award action must succeed with complete data, so a 404
-- can never be mistaken for an authorization success.
BEGIN;

-- Award I: one family exercising the version-sensitive cases of design section 9.
--   seq 1: unit SYN-U-200, PI someone else, no IO         (T6, T8, T14 "other version")
--   seq 2: unit SYN-U-100, Pat is PI, IO SYN-IO-7001       (T8 PI on seq 2 only, T14 IO on current only)
INSERT INTO archive.award_version (award_id, award_number, sequence_number, award_sequence_status,
    status_description, title, sponsor_code, sponsor_name, lead_unit_number, lead_unit_name,
    account_number, award_effective_date, workflow_document_number, is_current_version, is_primary_current) VALUES
  (9000901, '990009-00001', 1, 'ARCHIVED', 'Active', 'SYNTHETIC Award I - seq 1 in another unit', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-200', 'SYNTHETIC Department of Others', NULL, DATE '2022-01-01', 'SYN-DOC-0901', FALSE, FALSE),
  (9000902, '990009-00001', 2, 'ACTIVE', 'Active', 'SYNTHETIC Award I - seq 2, Pat is PI', 'SYN-SP', 'SYNTHETIC Sponsor',
   'SYN-U-100', 'SYNTHETIC Department of Examples', NULL, DATE '2022-01-01', 'SYN-DOC-0902', TRUE, TRUE);
INSERT INTO archive.award_person (award_person_id, award_id, award_number, sequence_number, person_id, rolodex_id,
    full_name, contact_role_code) VALUES
  (9100901, 9000901, '990009-00001', 1, 'SYNP-OTHER-08', NULL, 'VAL EARLIERPI', 'PI'),
  (9100902, 9000902, '990009-00001', 2, 'SYNP-PI-01', NULL, 'PAT EXAMPLE', 'PI');
-- IO grants match the Award account number (approved D-A): seq 2 only carries SYN-IO-7001.
UPDATE archive.award_version SET account_number = 'SYN-IO-7001' WHERE award_id = 9000902;
INSERT INTO archive.award_hierarchy (award_hierarchy_id, root_award_number, award_number, parent_award_number,
    originating_award_number, active) VALUES
  (9200009, '990009-00001', '990009-00001', '990009-00001', '990009-00001', 'Y');

-- Every other synthetic Award is the root of its own one-node hierarchy.
INSERT INTO archive.award_hierarchy (award_hierarchy_id, root_award_number, award_number, parent_award_number,
    originating_award_number, active)
SELECT 9200100 + row_number() OVER (ORDER BY n), n, n, n, n, 'Y'
FROM (SELECT DISTINCT award_number AS n FROM archive.award_version WHERE award_number LIKE '99000_-0000_') a
WHERE NOT EXISTS (SELECT 1 FROM archive.award_hierarchy h WHERE h.award_number = a.n);

-- Amounts: one amount-info row per version, with a Time and Money document, so the
-- Time and Money summary and both report PDFs exist for every synthetic Award.
INSERT INTO archive.time_and_money_document (document_number, root_award_number, document_status, creation_date)
SELECT 'SYN-TNM-' || right(av.award_number, 11), h.root_award_number, 'FINAL', DATE '2022-02-01'
FROM archive.award_version av
JOIN archive.award_hierarchy h ON h.award_number = av.award_number
WHERE av.award_number LIKE '99000_-0000_'
GROUP BY av.award_number, h.root_award_number;

INSERT INTO archive.award_amount_info (award_amount_info_id, award_id, award_number, sequence_number,
    obligated_total_direct, obligated_total_indirect, obligated_total_amount,
    anticipated_total_direct, anticipated_total_indirect, anticipated_total_amount, tnm_document_number)
SELECT 9400000 + (av.award_id - 9000000), av.award_id, av.award_number, av.sequence_number,
       1000 * av.sequence_number, 500 * av.sequence_number, 1500 * av.sequence_number,
       2000 * av.sequence_number, 1000 * av.sequence_number, 3000 * av.sequence_number,
       'SYN-TNM-' || right(av.award_number, 11)
FROM archive.award_version av
WHERE av.award_number LIKE '99000_-0000_'
  AND NOT EXISTS (SELECT 1 FROM archive.award_amount_info x WHERE x.award_id = av.award_id);

INSERT INTO archive.award_amount_transaction (award_amount_transaction_id, award_number, document_number,
    transaction_type_code, transaction_type_description, notice_date, comments)
SELECT 9500000 + row_number() OVER (ORDER BY award_number), award_number, 'SYN-TNM-' || right(award_number, 11),
       '1', 'SYNTHETIC New award', DATE '2022-02-01', 'SYNTHETIC transaction for ' || award_number
FROM (SELECT DISTINCT award_number FROM archive.award_version WHERE award_number LIKE '99000_-0000_') a;

-- A Time and Money transaction moving money into Award B only (cross-award probe).
INSERT INTO archive.pending_transaction (transaction_id, document_number, source_award_number, destination_award_number,
    obligated_amount, anticipated_amount, comments, processed_flag)
VALUES (9700002, 'SYN-TNM-90002-00001', '990002-00001', '990002-00001', 777, 777,
        'SYNTHETIC Award B transaction - must not be readable through another Award', 'Y');

-- Comments: one per Award version, naming the version, so a leak of another
-- version's comment is visible in the response text.
INSERT INTO archive.comment_type (comment_type_code, description, award_comment_screen_flag)
VALUES ('SYN', 'SYNTHETIC General comment', 'Y') ON CONFLICT DO NOTHING;
INSERT INTO archive.award_comment (award_comment_id, award_id, award_number, sequence_number, comment_type_code, comments)
SELECT 9600000 + (av.award_id - 9000000), av.award_id, av.award_number, av.sequence_number, 'SYN',
       'SYNTHETIC comment for award_id ' || av.award_id || ' (' || av.award_number || ' seq ' || av.sequence_number || ')'
FROM archive.award_version av WHERE av.award_number LIKE '99000_-0000_';

-- Stored attachment files: the existing A (seq 2) and B attachments become real
-- files, plus one on A seq 1 only (another version) and one on A's child.
UPDATE archive.attachment_object SET upload_status = 'UPLOADED', s3_bucket = 'local-fixtures', s3_key = 'synthetic/' || file_name,
       file_size_bytes = 400 WHERE file_id IN (9300001, 9300002, 9300003);
INSERT INTO archive.attachment_object (file_id, file_name, content_type, upload_status, s3_key, file_size_bytes, s3_bucket) VALUES
  (9301003, 'SYNTHETIC-award-A-seq1.pdf', 'application/pdf', 'UPLOADED', 'synthetic/SYNTHETIC-award-A-seq1.pdf', 400, 'local-fixtures'),
  (9301004, 'SYNTHETIC-award-A-child.pdf', 'application/pdf', 'UPLOADED', 'synthetic/SYNTHETIC-award-A-child.pdf', 400, 'local-fixtures'),
  (9301009, 'SYNTHETIC-award-I-seq2.pdf', 'application/pdf', 'UPLOADED', 'synthetic/SYNTHETIC-award-I-seq2.pdf', 400, 'local-fixtures');
INSERT INTO archive.award_attachment (award_attachment_id, award_id, award_number, sequence_number, file_id,
    type_code, description) VALUES
  (9301003, 9000101, '990001-00001', 1, 9301003, '1', 'SYNTHETIC attachment on Award A seq 1 only'),
  (9301004, 9000111, '990001-00002', 1, 9301004, '1', 'SYNTHETIC attachment on Award A child'),
  (9301009, 9000902, '990009-00001', 2, 9301009, '1', 'SYNTHETIC attachment on Award I seq 2');
-- Evidence Search rows (FICTIONAL; fixed lab embedding): Award A's own version plus one excerpt of
-- every related type (Proposal SYN-PRP-0001, which Pat cannot open; Negotiation; Subaward), and
-- Award B's version for the forbidden case.
INSERT INTO archive.search_embedding (module, record_id, canonical_family_id, business_number, source_text,
    source_hash, embedding, embedding_model, document_type, parent_module, parent_business_identifier,
    exact_record_id, source_table, source_primary_key)
SELECT 'AWARD', v.pk, v.family, v.num, v.text, 'lab-' || v.pk, array_fill(0.1::real, ARRAY[1024])::vector,
       'identity-lab-fixed', v.type, 'AWARD', v.num, v.pk, v.source_table, v.pk
FROM (VALUES
  (9000102, 9000102, '990001-00001', 'AWARD_VERSION', 'archive.award_version',
   'Award 990001-00001 version 2: SYNTHETIC Award A - PI is Pat Example.'),
  (9400001, 9000102, '990001-00001', 'RELATED_PROPOSAL', 'archive.award_funding_proposal',
   'Award 990001-00001 version 2 is funded by Proposal SYN-PRP-0001: SYNTHETIC Proposal 1 - related to Award A.'),
  (9500001, 9000102, '990001-00001', 'RELATED_NEGOTIATION', 'archive.negotiation',
   'Negotiation SYN-NDOC-01 associated with Award 990001-00001, negotiator SYNTHETIC NEGOTIATOR.'),
  (9610001, 9000102, '990001-00001', 'RELATED_SUBAWARD', 'archive.subaward_funding',
   'Subaward SYN-SUB-01 (document SYN-SDOC-01) is linked to Award 990001-00001.'),
  (9000201, 9000201, '990002-00001', 'AWARD_VERSION', 'archive.award_version',
   'Award 990002-00001 version 1: SYNTHETIC Award B - unrelated.')
) AS v(pk, family, num, type, source_table, text);
COMMIT;
