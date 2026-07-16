CREATE OR REPLACE VIEW archive.v_global_search AS
WITH version_data AS (
    SELECT
        protocol_base,

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
)
SELECT
    current_irb.record_id,
    'IRB'::VARCHAR(50) AS module,

    current_irb.study_id,
    current_irb.protocol_base,
    current_irb.protocol_number,
    current_irb.title,
    current_irb.protocol_status,
    current_irb.protocol_type,
    current_irb.pi_full_name,
    current_irb.pi_email,

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
        current_irb.study_id,
        current_irb.protocol_base,
        current_irb.protocol_number,
        current_irb.title,
        current_irb.protocol_status,
        current_irb.protocol_type,
        current_irb.pi_buid,
        current_irb.pi_full_name,
        current_irb.pi_email,
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
        submissions.review_types
    ) AS search_text
FROM archive.v_irb_search current_irb
LEFT JOIN version_data versions
    ON TRIM(versions.protocol_base) = TRIM(current_irb.protocol_base)
LEFT JOIN funding_data funding
    ON TRIM(funding.protocol_base) = TRIM(current_irb.protocol_base)
LEFT JOIN submission_data submissions
    ON TRIM(submissions.protocol_base) = TRIM(current_irb.protocol_base);
