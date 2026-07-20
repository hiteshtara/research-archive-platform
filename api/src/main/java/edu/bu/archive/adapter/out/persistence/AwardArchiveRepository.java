package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.AwardHistoryResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AwardArchiveRepository {

    private final JdbcClient jdbc;

    public AwardArchiveRepository(
            JdbcClient jdbc
    ) {
        this.jdbc = jdbc;
    }

    public List<AwardHistoryResponse> history(
            String awardNumber
    ) {

        return jdbc.sql("""
                SELECT
                    award_id,
                    award_number,
                    sequence_number,
                    title,
                    status_description,
                    award_sequence_status,
                    sponsor_name,
                    prime_sponsor_name,
                    lead_unit_name,
                    account_number,
                    sponsor_award_number,
                    begin_date,
                    closeout_date,
                    is_current_version,
                    is_primary_current
                FROM archive.award_version
                WHERE award_number = :awardNumber
                ORDER BY
                    sequence_number DESC,
                    award_id DESC
                """)
                .param("awardNumber", awardNumber)
                .query(AwardHistoryResponse.class)
                .list();

    }

}
