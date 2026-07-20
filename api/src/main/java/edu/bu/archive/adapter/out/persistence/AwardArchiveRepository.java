package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
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

    public List<AwardRowResponse> findHistoryRows(
            String awardNumber
    ) {
        return jdbc.sql("""
                SELECT
                    award_id,
                    award_number,
                    sequence_number,
                    title,

                    status_description
                        AS status,

                    award_sequence_status,

                    sponsor_name
                        AS sponsor,

                    prime_sponsor_name
                        AS prime_sponsor,

                    lead_unit_name
                        AS lead_unit,

                    account_number,
                    sponsor_award_number,
                    begin_date,
                    closeout_date,

                    is_current_version
                        AS current,

                    is_primary_current
                        AS primary_current

                FROM archive.award_version
                WHERE award_number = :awardNumber
                ORDER BY
                    sequence_number DESC,

                    CASE
                        WHEN UPPER(TRIM(award_sequence_status)) = 'ACTIVE'
                        THEN 1
                        ELSE 0
                    END DESC,

                    source_update_timestamp DESC NULLS LAST,
                    award_id DESC
                """)
                .param("awardNumber", awardNumber)
                .query(AwardRowResponse.class)
                .list();
    }
}
