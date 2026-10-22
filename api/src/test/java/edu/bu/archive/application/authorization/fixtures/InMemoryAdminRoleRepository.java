package edu.bu.archive.application.authorization.fixtures;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.AdminRoleMember;
import edu.bu.archive.application.authorization.admin.AdminRoleMemberStatus;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.LastArchiveAdministratorException;

/**
 * In-memory ARCHIVE_ADMIN store for unit tests.
 *
 * <p>Implements the same rules as the JDBC repository, including the
 * last-administrator guard, but NOT its locking: the guard's correctness under
 * concurrency depends on {@code SELECT ... FOR UPDATE} and is therefore proven
 * by ArchiveAdminConcurrencyIntegrationTest against a real database, not here.
 */
public class InMemoryAdminRoleRepository implements AdminRoleRepository {

    private final List<AdminRoleMember> rows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);

    @Override
    public Optional<AdminRoleMember> findActive(InstitutionalIdentifier member) {
        return rows.stream()
                .filter(r -> r.member().value().equals(member.value()) && r.isActive())
                .findFirst();
    }

    @Override
    public List<AdminRoleMember> findHistory(InstitutionalIdentifier member) {
        return rows.stream()
                .filter(r -> r.member().value().equals(member.value()))
                .sorted(Comparator.comparingLong(AdminRoleMember::adminMemberId).reversed())
                .toList();
    }

    @Override
    public List<AdminRoleMember> findAllActive() {
        return rows.stream().filter(AdminRoleMember::isActive).toList();
    }

    @Override
    public int countActive() {
        return findAllActive().size();
    }

    @Override
    public AdminRoleMember assign(
            InstitutionalIdentifier member, String grantedBy, String reason) {
        if (findActive(member).isPresent()) {
            throw new IllegalStateException(
                    "identity already holds an active ARCHIVE_ADMIN membership");
        }
        return add(member, grantedBy, reason);
    }

    @Override
    public void revokeWithLastAdministratorGuard(
            InstitutionalIdentifier member, String revokedBy, String reason) {

        AdminRoleMember target = findActive(member).orElseThrow(() -> new IllegalStateException(
                "identity holds no active ARCHIVE_ADMIN membership to revoke"));

        if (countActive() <= 1) {
            throw new LastArchiveAdministratorException(
                    "refusing to revoke the last active ARCHIVE_ADMIN; assign another "
                            + "administrator first");
        }

        rows.remove(target);
        rows.add(new AdminRoleMember(
                target.adminMemberId(), target.member(), AdminRoleMemberStatus.REVOKED,
                target.grantedBy(), target.grantedAt(), revokedBy, Instant.now(),
                reason == null ? target.reason() : reason));
    }

    @Override
    public AdminRoleMember bootstrap(
            InstitutionalIdentifier member, String operator, String reason) {
        if (!findAllActive().isEmpty()) {
            throw new IllegalStateException(
                    "refusing to bootstrap: an active ARCHIVE_ADMIN already exists. "
                            + "Assign further administrators through an existing one.");
        }
        return add(member, AdminRoleMember.BOOTSTRAP_ACTOR,
                "operator=" + operator + (reason == null ? "" : "; " + reason));
    }

    private AdminRoleMember add(
            InstitutionalIdentifier member, String grantedBy, String reason) {
        AdminRoleMember row = new AdminRoleMember(
                ids.getAndIncrement(), member, AdminRoleMemberStatus.ACTIVE,
                grantedBy, Instant.now(), null, null, reason);
        rows.add(row);
        return row;
    }
}
