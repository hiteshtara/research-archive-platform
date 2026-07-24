CREATE INDEX IF NOT EXISTS ix_negotiation_archive_list
    ON archive.negotiation (
        source_update_timestamp DESC NULLS LAST,
        negotiation_id DESC
    );

CREATE INDEX IF NOT EXISTS ix_subaward_archive_list
    ON archive.subaward (
        source_update_timestamp DESC NULLS LAST,
        sequence_number DESC,
        subaward_id DESC
    );

CREATE INDEX IF NOT EXISTS ix_proposal_version_family_latest
    ON archive.proposal_version (
        proposal_number,
        version_number DESC,
        source_update_timestamp DESC NULLS LAST,
        proposal_id DESC
    );
