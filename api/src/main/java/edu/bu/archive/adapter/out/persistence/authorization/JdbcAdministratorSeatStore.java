package edu.bu.archive.adapter.out.persistence.authorization;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.AdminAuditAction;
import edu.bu.archive.application.authorization.admin.bootstrap.AdministratorSeatStore;

/** Locking and history for the one-time second-administrator bootstrap. */
@Repository
public class JdbcAdministratorSeatStore implements AdministratorSeatStore {

    private final JdbcClient jdbc;

    public JdbcAdministratorSeatStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<InstitutionalIdentifier> lockActiveAdministrators() {
        /*
         * The advisory lock first, for the reason JdbcAdminRoleRepository.bootstrap
         * documents: FOR UPDATE alone locks only rows that exist, and the
         * rows that matter here are the ones a concurrent run is about to
         * INSERT. Same key as the first-administrator bootstrap, so the two
         * paths also serialise against each other.
         */
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
                .param("key", JdbcAdminRoleRepository.BOOTSTRAP_LOCK_KEY)
                .query(Object.class)
                .single();

        return jdbc.sql("""
                        SELECT institutional_identifier FROM authz.admin_role_member
                         WHERE status = 'ACTIVE'
                         ORDER BY admin_member_id
                         FOR UPDATE
                        """)
                .query(String.class)
                .list()
                .stream()
                .map(InstitutionalIdentifier::new)
                .toList();
    }

    @Override
    public boolean secondAdministratorEverSeated() {
        Boolean seated = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM authz.access_audit WHERE action = :action)
                        """)
                .param("action", AdminAuditAction.ADMIN_ROLE_SECOND_BOOTSTRAPPED.name())
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(seated);
    }
}
