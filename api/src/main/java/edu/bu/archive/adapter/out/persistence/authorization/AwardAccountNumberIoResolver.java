package edu.bu.archive.adapter.out.persistence.authorization;

import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;

import edu.bu.archive.application.authorization.IoResolver;
import edu.bu.archive.application.authorization.RecordModule;

/**
 * APPROVED (Hitesh, 2026-10-01; design rev 3.9, decision D-A): an IO grant authorizes the
 * Award versions whose account number equals the granted IO value. Matching is exact after
 * trimming. It applies to Awards only; an IO grant never reaches Proposals, Negotiations,
 * Subawards or other Awards through relationships (design 4.6).
 */
public final class AwardAccountNumberIoResolver implements IoResolver {

    private final JdbcClient jdbc;

    public AwardAccountNumberIoResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> ioValuesFor(RecordModule module, String versionKey) {
        if (module != RecordModule.AWARD) {
            return Set.of();
        }
        return jdbc.sql("""
                        SELECT TRIM(account_number) FROM archive.award_version
                        WHERE award_id = CAST(:id AS BIGINT) AND NULLIF(TRIM(account_number), '') IS NOT NULL
                        """)
                .param("id", versionKey)
                .query(String.class)
                .optional()
                .map(Set::of)
                .orElse(Set.of());
    }
}
