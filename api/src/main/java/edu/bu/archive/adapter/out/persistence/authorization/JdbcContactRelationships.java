package edu.bu.archive.adapter.out.persistence.authorization;

import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;

import edu.bu.archive.application.authorization.ContactRelationships;
import edu.bu.archive.application.authorization.KualiPersonId;

/** Award or Proposal contact rows for a KIM principal, in the qualifying roles. */
public class JdbcContactRelationships implements ContactRelationships {

    private final JdbcClient jdbc;

    public JdbcContactRelationships(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isQualifyingContact(KualiPersonId person, Set<String> roles) {
        if (roles.isEmpty()) {
            return false;
        }
        Boolean found = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM archive.award_person
                               WHERE person_id = :person AND UPPER(TRIM(contact_role_code)) IN (:roles))
                    OR EXISTS (SELECT 1 FROM archive.proposal_person
                               WHERE person_id = :person AND UPPER(TRIM(contact_role_code)) IN (:roles))
                """)
                .param("person", person.value())
                .param("roles", List.copyOf(roles))
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(found);
    }
}
