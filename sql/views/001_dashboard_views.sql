CREATE OR REPLACE VIEW archive.v_dashboard_counts AS
SELECT
    record_type,
    COUNT(*) AS total_records,
    COUNT(*) FILTER (WHERE active_flag = TRUE) AS active_records,
    COUNT(*) FILTER (WHERE active_flag = FALSE) AS inactive_records
FROM archive.research_record
GROUP BY record_type;

CREATE OR REPLACE VIEW archive.v_global_search AS
SELECT
    record_id,
    record_type,
    business_identifier,
    source_identifier,
    title,
    status_description,
    lead_person_buid,
    lead_person_name,
    responsible_unit,
    start_date,
    end_date,
    loaded_at
FROM archive.research_record;

CREATE OR REPLACE VIEW archive.v_irb_search AS
SELECT
    rr.record_id,
    irb.study_id,
    irb.protocol_base,
    irb.protocol_number,
    rr.title,
    irb.protocol_type,
    irb.protocol_status,
    irb.approval_date,
    irb.pi_buid,
    irb.pi_first_name,
    irb.pi_last_name,
    irb.pi_full_name,
    irb.pi_email,
    irb.pi_buid_missing,
    irb.responsible_unit,
    irb.active_flag,
    irb.loaded_at
FROM archive.irb_protocol irb
JOIN archive.research_record rr
    ON rr.record_id = irb.record_id;
