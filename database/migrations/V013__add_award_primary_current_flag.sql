ALTER TABLE archive.award_version
    ADD COLUMN IF NOT EXISTS is_primary_current BOOLEAN
    NOT NULL DEFAULT FALSE;

UPDATE archive.award_version
SET is_primary_current = FALSE;

WITH ranked AS (
    SELECT
        award_id,
        ROW_NUMBER() OVER (
            PARTITION BY award_number
            ORDER BY
                sequence_number DESC,

                CASE
                    WHEN UPPER(TRIM(award_sequence_status)) = 'ACTIVE'
                    THEN 1
                    ELSE 0
                END DESC,

                source_update_timestamp DESC NULLS LAST,
                award_id DESC
        ) AS row_rank
    FROM archive.award_version
)
UPDATE archive.award_version target
SET is_primary_current = TRUE
FROM ranked
WHERE target.award_id = ranked.award_id
  AND ranked.row_rank = 1;

CREATE UNIQUE INDEX IF NOT EXISTS ux_award_one_primary_current
    ON archive.award_version (award_number)
    WHERE is_primary_current = TRUE;

CREATE INDEX IF NOT EXISTS ix_award_primary_current
    ON archive.award_version (
        is_primary_current,
        award_number
    );
