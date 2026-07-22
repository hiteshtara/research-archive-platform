package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalRowResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ProposalArchiveRepository {

    private final JdbcClient jdbc;

    public ProposalArchiveRepository(
            JdbcClient jdbc
    ) {
        this.jdbc = jdbc;
    }

    public Optional<ProposalRowResponse> findCurrent(
            String proposalNumber
    ) {
        return jdbc.sql("""
                SELECT
                    proposal_id,
                    proposal_number,
                    version_number,
                    title,
                    proposal_sequence_status AS status,
                    proposal_type,
                    activity_type,
                    sponsor_code,
                    sponsor_name,
                    lead_unit_number,
                    lead_unit_name,
                    principal_investigator_id,
                    principal_investigator_name
                        AS principal_investigator,
                    initial_start_date,
                    initial_end_date,
                    initial_direct_cost,
                    initial_indirect_cost,
                    initial_total_cost,
                    total_start_date,
                    total_end_date,
                    total_direct_cost,
                    total_indirect_cost,
                    total_cost
                FROM archive.proposal_version
                WHERE proposal_number = :proposalNumber
                ORDER BY
                    version_number DESC,
                    source_update_timestamp DESC NULLS LAST,
                    proposal_id DESC
                LIMIT 1
                """)
                .param("proposalNumber", proposalNumber)
                .query(ProposalRowResponse.class)
                .optional();
    }
}
