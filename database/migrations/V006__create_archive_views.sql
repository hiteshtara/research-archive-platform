CREATE OR REPLACE VIEW archive.v_dashboard_counts AS
SELECT
    record_type,
    COUNT(*) AS total_records,
    COUNT(*) FILTER (
        WHERE active_flag = TRUE
    ) AS active_records,
    COUNT(*) FILTER (
        WHERE active_flag = FALSE
    ) AS inactive_records
FROM archive.research_record
GROUP BY record_type;


CREATE OR REPLACE VIEW archive.v_irb_search AS
SELECT
    record.record_id,
    protocol.study_id,
    protocol.protocol_base,
    protocol.protocol_number,
    record.title,
    protocol.protocol_type,
    protocol.protocol_status,
    protocol.approval_date,
    protocol.pi_buid,
    protocol.pi_first_name,
    protocol.pi_last_name,
    protocol.pi_full_name,
    protocol.pi_email,
    protocol.pi_buid_missing,
    protocol.active_flag,
    protocol.loaded_at
FROM archive.irb_protocol protocol
JOIN archive.research_record record
  ON record.record_id = protocol.record_id;
