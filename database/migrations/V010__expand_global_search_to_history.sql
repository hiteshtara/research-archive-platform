CREATE OR REPLACE VIEW archive.v_global_search AS
WITH latest_version AS (
    SELECT DISTINCT ON (protocol_base)
        protocol_id,
        protocol_base,
        protocol_number,
        sequence_number,
        document_number,
        crc_protocol_num,
        title,
        protocol_type,
        protocol_status,
        pi_id,
        pi_email,
        pi_affiliation,
        summary_keywords,
        approval_date,
        loaded_at
    FROM archive.irb_protocol_version
    ORDER BY
        protocol_base,
        COALESCE(sequence_number, -1) DESC,
        protocol_id DESC
),
version_data AS (
    SELECT
        protocol_base,
        STRING_AGG(
            DISTINCT NULLIF(TRIM(protocol_number), ''),
            ' | '
        ) AS protocol_numbers,
        STRING_AGG(
            DISTINCT NULLIF(TRIM(document_number), ''),
            ' | '
        ) AS document_numbers,
        STRING_AGG(
            DISTINCT NULLIF(TRIM(crc_protocol_num), ''),
            ' | '
        ) AS crc_protocol_numbers,
        STRING_AGG(
            DISTINCT NULLIF(TRIM(summary_keywords), ''),
            ' | '
        ) AS summary_keywords,
        STRING_AGG(
            DISTINCT NULLIF(TRIM(pi_email), ''),
            ' | '
        ) AS historical_pi_emails,
        STRING_AGG(
            DISTINCT NULLIF(TRIM(pi_affiliation), ''),
            ' | '
        ) AS pi_affiliations,
        STRING_AGG(
            DISTINCT NULLIF(TRIM(protocol_type), ''),
            ' | '
        ) AS historical_protocol_types,
        STRING_AGG(
            DISTINCT NULLIF(TRIM(protocol_status), ''),
            ' | '
        ) AS historical_protocol_statuses
    FROM archive.irb_protocol_version
    GROUP BY protocol_base
),
funding_data AS (
    SELECT
        protocol_base,
        STRING_AGG(
            DISTINCT NULLIF(TRIM(funding_source), ''),
            ' | '
        ) AS funding_sources
    FROM archive.irb_funding_source
    GROUP BY protocol_base
),
submission_data AS (
    SELECT
        protocol_base,
        STRING_AGG(
            DISTINCT NULLIF(TRIM(submission_type), ''),
            ' | '
        ) AS submission_types,
        STRING_AGG(
            DISTINCT NULLIF(TRIM(submission_status), ''),
            ' | '
        ) AS submission_statuses,
        STRING_AGG(
            DISTINCT NULLIF(TRIM(event_type), ''),
            ' | '
        ) AS submission_event_types,
        STRING_AGG(
            DISTINCT NULLIF(TRIM(review_type), ''),
            ' | '
        ) AS review_types
    FROM archive.irb_submission
    GROUP BY protocol_base
),
current_records AS (
    SELECT
        record_id,
        study_id,
        protocol_base,
        protocol_number,
        title,
        protocol_status,
        protocol_type,
        pi_buid,
        pi_full_name,
        pi_email
    FROM archive.v_irb_search
)
SELECT
    current_records.record_id,
    latest_version.protocol_id,
    'IRB'::VARCHAR(50) AS module,
    current_records.study_id,
    latest_version.protocol_base,
    latest_version.protocol_number,
    COALESCE(current_records.title, latest_version.title) AS title,
    COALESCE(
        current_records.protocol_status,
        latest_version.protocol_status
    ) AS protocol_status,
    COALESCE(
        current_records.protocol_type,
        latest_version.protocol_type
    ) AS protocol_type,
    current_records.pi_full_name,
    COALESCE(
        current_records.pi_email,
        latest_version.pi_email
    ) AS pi_email,
    versions.protocol_numbers,
    versions.document_numbers,
    versions.crc_protocol_numbers,
    versions.summary_keywords,
    versions.historical_pi_emails,
    versions.pi_affiliations,
    versions.historical_protocol_types,
    versions.historical_protocol_statuses,
    funding.funding_sources,
    submissions.submission_types,
    submissions.submission_statuses,
    submissions.submission_event_types,
    submissions.review_types,
    CONCAT_WS(
        ' ',
        current_records.study_id,
        latest_version.protocol_base,
        versions.protocol_numbers,
        versions.document_numbers,
        versions.crc_protocol_numbers,
        COALESCE(current_records.title, latest_version.title),
        COALESCE(
            current_records.protocol_status,
            latest_version.protocol_status
        ),
        COALESCE(
            current_records.protocol_type,
            latest_version.protocol_type
        ),
        current_records.pi_buid,
        latest_version.pi_id,
        current_records.pi_full_name,
        COALESCE(
            current_records.pi_email,
            latest_version.pi_email
        ),
        versions.summary_keywords,
        versions.historical_pi_emails,
        versions.pi_affiliations,
        versions.historical_protocol_types,
        versions.historical_protocol_statuses,
        funding.funding_sources,
        submissions.submission_types,
        submissions.submission_statuses,
        submissions.submission_event_types,
        submissions.review_types
    ) AS search_text
FROM latest_version
LEFT JOIN current_records
    ON TRIM(current_records.protocol_base) =
       TRIM(latest_version.protocol_base)
LEFT JOIN version_data versions
    ON versions.protocol_base = latest_version.protocol_base
LEFT JOIN funding_data funding
    ON funding.protocol_base = latest_version.protocol_base
LEFT JOIN submission_data submissions
    ON submissions.protocol_base = latest_version.protocol_base;
