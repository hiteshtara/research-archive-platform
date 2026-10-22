package edu.bu.archive.adapter.out.persistence.authorization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import edu.bu.archive.application.authorization.IoResolver;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.RecordContact;
import edu.bu.archive.application.authorization.RecordFacts;
import edu.bu.archive.application.authorization.RecordFactsRepository;
import edu.bu.archive.application.authorization.RecordModule;

/**
 * Authorization facts for Awards and Proposals: lead unit and employee
 * contacts per version. Contacts with no person_id (rolodex, non-employee)
 * are kept as non-employee contacts and can never match a signed-in person.
 */
@Repository
public class JdbcRecordFactsRepository implements RecordFactsRepository {

    private final JdbcClient jdbc;
    private final IoResolver ioResolver;

    public JdbcRecordFactsRepository(JdbcClient jdbc, IoResolver ioResolver) {
        this.jdbc = jdbc;
        this.ioResolver = ioResolver;
    }

    @Override
    public Optional<String> awardNumberForId(long awardId) {
        return jdbc.sql("SELECT award_number FROM archive.award_version WHERE award_id = :id LIMIT 1")
                .param("id", awardId).query(String.class).optional();
    }

    @Override
    public Optional<String> currentAwardVersionKey(String awardNumber) {
        return jdbc.sql("""
                        SELECT award_id::text FROM archive.award_version
                        WHERE award_number = :n AND is_primary_current = TRUE LIMIT 1
                        """)
                .param("n", awardNumber).query(String.class).optional();
    }

    @Override
    public List<RecordFacts> awardFamily(String awardNumber) {
        Map<String, List<RecordContact>> contacts = new HashMap<>();
        jdbc.sql("""
                        SELECT ap.award_id::text AS version_key, ap.person_id, ap.contact_role_code
                        FROM archive.award_person ap
                        JOIN archive.award_version av ON av.award_id = ap.award_id
                        WHERE av.award_number = :n
                        """)
                .param("n", awardNumber)
                .query((rs, i) -> {
                    contacts.computeIfAbsent(rs.getString("version_key"), k -> new ArrayList<>())
                            .add(contact(rs.getString("person_id"), rs.getString("contact_role_code")));
                    return null;
                })
                .list();
        return jdbc.sql("""
                        SELECT award_id::text AS version_key, award_number, lead_unit_number
                        FROM archive.award_version WHERE award_number = :n
                        """)
                .param("n", awardNumber)
                .query((rs, i) -> {
                    String key = rs.getString("version_key");
                    return new RecordFacts(RecordModule.AWARD, key, rs.getString("award_number"),
                            rs.getString("lead_unit_number"), contacts.getOrDefault(key, List.of()),
                            ioResolver.ioValuesFor(RecordModule.AWARD, key));
                })
                .list();
    }

    @Override
    public Optional<String> proposalNumberForId(long proposalId) {
        return jdbc.sql("SELECT proposal_number FROM archive.proposal_version WHERE proposal_id = :id LIMIT 1")
                .param("id", proposalId).query(String.class).optional();
    }

    @Override
    public Optional<String> latestProposalVersionKey(String proposalNumber) {
        return jdbc.sql("""
                        SELECT proposal_id::text FROM archive.proposal_version WHERE proposal_number = :n
                        ORDER BY version_number DESC, source_update_timestamp DESC NULLS LAST, proposal_id DESC
                        LIMIT 1
                        """)
                .param("n", proposalNumber).query(String.class).optional();
    }

    @Override
    public List<RecordFacts> proposalFamily(String proposalNumber) {
        Map<String, List<RecordContact>> contacts = new HashMap<>();
        jdbc.sql("""
                        SELECT pp.proposal_id::text AS version_key, pp.person_id, pp.contact_role_code
                        FROM archive.proposal_person pp
                        WHERE pp.proposal_id IN (SELECT proposal_id FROM archive.proposal_version WHERE proposal_number = :n)
                        """)
                .param("n", proposalNumber)
                .query((rs, i) -> {
                    contacts.computeIfAbsent(rs.getString("version_key"), k -> new ArrayList<>())
                            .add(contact(rs.getString("person_id"), rs.getString("contact_role_code")));
                    return null;
                })
                .list();
        return jdbc.sql("""
                        SELECT DISTINCT ON (proposal_id) proposal_id::text AS version_key, proposal_number, lead_unit_number
                        FROM archive.proposal_version WHERE proposal_number = :n
                        ORDER BY proposal_id, version_number DESC
                        """)
                .param("n", proposalNumber)
                .query((rs, i) -> {
                    String key = rs.getString("version_key");
                    return new RecordFacts(RecordModule.PROPOSAL, key, rs.getString("proposal_number"),
                            rs.getString("lead_unit_number"), contacts.getOrDefault(key, List.of()), java.util.Set.of());
                })
                .list();
    }

    private static RecordContact contact(String personId, String roleCode) {
        return personId == null || personId.isBlank()
                ? new RecordContact(Optional.empty(), roleCode, false)
                : new RecordContact(Optional.of(new KualiPersonId(personId)), roleCode, true);
    }
}
