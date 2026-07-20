package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.AwardFamilySummaryResponse;
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

    public List<AwardFamilySummaryResponse> findFamilies(
            String query,
            int limit
    ) {
        String normalizedQuery =
                query == null
                        ? ""
                        : query.trim();

        return jdbc.sql("""
                SELECT
                    award_number,

                    title,

                    status_description
                        AS status,

                    award_sequence_status,

                    sponsor_name
                        AS sponsor,

                    lead_unit_name
                        AS lead_unit,

                    account_number,

                    sequence_number
                        AS latest_sequence_number,

                    award_id
                        AS primary_award_id

                FROM archive.award_version

                WHERE is_primary_current = TRUE

                  AND (
                        :query = ''

                        OR award_number ILIKE
                           '%' || :query || '%'

                        OR title ILIKE
                           '%' || :query || '%'

                        OR sponsor_name ILIKE
                           '%' || :query || '%'

                        OR lead_unit_name ILIKE
                           '%' || :query || '%'

                        OR account_number ILIKE
                           '%' || :query || '%'
                  )

                ORDER BY award_number

                LIMIT :limit
                """)
                .param("query", normalizedQuery)
                .param("limit", limit)
                .query(AwardFamilySummaryResponse.class)
                .list();
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
                        WHEN UPPER(
                            TRIM(award_sequence_status)
                        ) = 'ACTIVE'
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
