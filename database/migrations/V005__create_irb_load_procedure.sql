CREATE OR REPLACE PROCEDURE archive.load_irb(
    p_load_id BIGINT
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_rows_staged       INTEGER;
    v_duplicate_count   INTEGER;
    v_invalid_count     INTEGER;
    v_rows_loaded       INTEGER;
BEGIN
    SELECT COUNT(*)
      INTO v_rows_staged
      FROM archive.irb_protocol_stage
     WHERE load_id = p_load_id;

    IF v_rows_staged = 0 THEN
        RAISE EXCEPTION
            'No staged IRB rows found for load_id %',
            p_load_id;
    END IF;

    SELECT COUNT(*)
      INTO v_duplicate_count
      FROM (
          SELECT protocol_base
            FROM archive.irb_protocol_stage
           WHERE load_id = p_load_id
           GROUP BY protocol_base
          HAVING COUNT(*) > 1
      ) duplicates;

    IF v_duplicate_count > 0 THEN
        UPDATE archive.load_run
           SET status        = 'REJECTED',
               rows_staged   = v_rows_staged,
               rows_rejected = v_duplicate_count,
               completed_at  = CURRENT_TIMESTAMP,
               error_message = 'Duplicate protocol_base values found'
         WHERE load_id = p_load_id;

        RAISE EXCEPTION
            'IRB load % rejected: % duplicate protocol bases',
            p_load_id,
            v_duplicate_count;
    END IF;

    SELECT COUNT(*)
      INTO v_invalid_count
      FROM archive.irb_protocol_stage
     WHERE load_id = p_load_id
       AND (
           protocol_id IS NULL
           OR protocol_base IS NULL
           OR protocol_number IS NULL
           OR title IS NULL
       );

    IF v_invalid_count > 0 THEN
        INSERT INTO archive.load_rejection (
            load_id,
            domain,
            source_row_number,
            business_key,
            rejection_reason,
            rejected_record
        )
        SELECT
            load_id,
            'IRB',
            source_row_number,
            protocol_base,
            'Required IRB value is missing',
            to_jsonb(stage_row)
        FROM archive.irb_protocol_stage stage_row
        WHERE load_id = p_load_id
          AND (
              protocol_id IS NULL
              OR protocol_base IS NULL
              OR protocol_number IS NULL
              OR title IS NULL
          );

        UPDATE archive.load_run
           SET status        = 'REJECTED',
               rows_staged   = v_rows_staged,
               rows_rejected = v_invalid_count,
               completed_at  = CURRENT_TIMESTAMP,
               error_message = 'Required IRB values are missing'
         WHERE load_id = p_load_id;

        RAISE EXCEPTION
            'IRB load % rejected: % invalid rows',
            p_load_id,
            v_invalid_count;
    END IF;

    INSERT INTO archive.research_record (
        record_type,
        source_system,
        source_identifier,
        business_identifier,
        title,
        status_code,
        status_description,
        lead_person_buid,
        lead_person_name,
        active_flag,
        archived_flag,
        loaded_at,
        load_id
    )
    SELECT
        'IRB',
        'KUALI',
        stage.protocol_id::VARCHAR,
        stage.study_id,
        stage.title,
        stage.protocol_status_code,
        stage.protocol_status,
        stage.pi_buid,
        stage.pi_full_name,
        TRUE,
        TRUE,
        CURRENT_TIMESTAMP,
        p_load_id
    FROM archive.irb_protocol_stage stage
    WHERE stage.load_id = p_load_id
    ON CONFLICT (
        record_type,
        source_system,
        source_identifier
    )
    DO UPDATE SET
        business_identifier = EXCLUDED.business_identifier,
        title               = EXCLUDED.title,
        status_code         = EXCLUDED.status_code,
        status_description  = EXCLUDED.status_description,
        lead_person_buid    = EXCLUDED.lead_person_buid,
        lead_person_name    = EXCLUDED.lead_person_name,
        active_flag         = EXCLUDED.active_flag,
        loaded_at           = CURRENT_TIMESTAMP,
        load_id             = p_load_id;

    INSERT INTO archive.irb_protocol (
        record_id,
        protocol_id,
        study_id,
        protocol_base,
        protocol_number,
        protocol_type_code,
        protocol_type,
        protocol_status_code,
        protocol_status,
        approval_date,
        pi_buid,
        pi_first_name,
        pi_last_name,
        pi_full_name,
        pi_email,
        pi_buid_missing,
        active_flag,
        source_file_name,
        source_row_number,
        source_extract_at,
        loaded_at,
        load_id
    )
    SELECT
        record.record_id,
        stage.protocol_id,
        stage.study_id,
        stage.protocol_base,
        stage.protocol_number,
        stage.protocol_type_code,
        stage.protocol_type,
        stage.protocol_status_code,
        stage.protocol_status,
        stage.approval_date,
        stage.pi_buid,
        stage.pi_first_name,
        stage.pi_last_name,
        stage.pi_full_name,
        stage.pi_email,
        COALESCE(stage.pi_buid_missing, FALSE),
        TRUE,
        stage.source_file_name,
        stage.source_row_number,
        stage.source_extract_at,
        CURRENT_TIMESTAMP,
        p_load_id
    FROM archive.irb_protocol_stage stage
    JOIN archive.research_record record
      ON record.record_type = 'IRB'
     AND record.source_system = 'KUALI'
     AND record.source_identifier =
            stage.protocol_id::VARCHAR
    WHERE stage.load_id = p_load_id
    ON CONFLICT (protocol_base)
    DO UPDATE SET
        record_id            = EXCLUDED.record_id,
        protocol_id          = EXCLUDED.protocol_id,
        study_id             = EXCLUDED.study_id,
        protocol_number      = EXCLUDED.protocol_number,
        protocol_type_code   = EXCLUDED.protocol_type_code,
        protocol_type        = EXCLUDED.protocol_type,
        protocol_status_code = EXCLUDED.protocol_status_code,
        protocol_status      = EXCLUDED.protocol_status,
        approval_date        = EXCLUDED.approval_date,
        pi_buid              = EXCLUDED.pi_buid,
        pi_first_name        = EXCLUDED.pi_first_name,
        pi_last_name         = EXCLUDED.pi_last_name,
        pi_full_name         = EXCLUDED.pi_full_name,
        pi_email             = EXCLUDED.pi_email,
        pi_buid_missing      = EXCLUDED.pi_buid_missing,
        active_flag          = EXCLUDED.active_flag,
        source_file_name     = EXCLUDED.source_file_name,
        source_row_number    = EXCLUDED.source_row_number,
        source_extract_at    = EXCLUDED.source_extract_at,
        loaded_at            = CURRENT_TIMESTAMP,
        load_id              = p_load_id;

    GET DIAGNOSTICS v_rows_loaded = ROW_COUNT;

    UPDATE archive.load_run
       SET status       = 'LOADED',
           rows_staged  = v_rows_staged,
           rows_loaded  = v_rows_loaded,
           completed_at = CURRENT_TIMESTAMP
     WHERE load_id = p_load_id;

    DELETE FROM archive.irb_protocol_stage
     WHERE load_id = p_load_id;
END;
$$;
