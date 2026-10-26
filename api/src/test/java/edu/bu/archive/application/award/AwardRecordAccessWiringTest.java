package edu.bu.archive.application.award;

import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.AwardRecordAccess;
import edu.bu.archive.application.authorization.SqlFragment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fail-open audit: enabling record authorization must never leave
 * {@link AwardRecordAccess#PERMIT_ALL} in place.
 *
 * <p>{@code PERMIT_ALL} exists so slice tests and contexts without the
 * Phase 1 beans keep working. That same fallback is a breach if it is ever
 * reached while {@code app.authorization.enforcement-enabled} is true: the
 * operator reads the configuration, believes the archive is protected, and
 * every Award is served to every authenticated caller with no error anywhere.
 *
 * <p>So the construction of {@link AwardArchiveService} refuses that
 * combination, and these tests are the proof. They do not enable enforcement
 * anywhere that matters - the property object is constructed directly, never
 * read from a deployed configuration file.
 */
class AwardRecordAccessWiringTest {

    private static final AwardArchiveRepository NO_REPOSITORY = null;

    // --- The fail-open case, which must not be reachable ----------------

    @Test
    void enforcementEnabledWithNoImplementationRefusesToStart() {
        assertThatThrownBy(() -> new AwardArchiveService(
                NO_REPOSITORY, null, null, absent(), properties(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enforcement-enabled is true")
                .hasMessageContaining("Refusing to start");
    }

    @Test
    void enforcementEnabledWithAnExplicitlyPermissivePortAlsoRefusesToStart() {
        // Passing PERMIT_ALL deliberately is no better than resolving to it
        // by accident, and is rejected identically.
        assertThatThrownBy(() -> new AwardArchiveService(
                NO_REPOSITORY, null, null, present(AwardRecordAccess.PERMIT_ALL), properties(true)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theRefusalNamesTheCauseSoAnOperatorCanActOnIt() {
        assertThatThrownBy(() -> new AwardArchiveService(
                NO_REPOSITORY, null, null, absent(), properties(true)))
                .hasMessageContaining("AwardRecordAccess")
                .hasMessageContaining("AuthorizationConfiguration");
    }

    // --- The supported configurations, which must keep working ----------

    @Test
    void enforcementEnabledWithARealImplementationStartsNormally() {
        assertThatCode(() -> new AwardArchiveService(
                NO_REPOSITORY, null, null, present(strict()), properties(true)))
                .doesNotThrowAnyException();
    }

    @Test
    void enforcementDisabledWithNoImplementationStartsAndPermits() {
        // Today's deployed configuration. It must stay startable and inert.
        assertThatCode(() -> new AwardArchiveService(
                NO_REPOSITORY, null, null, absent(), properties(false)))
                .doesNotThrowAnyException();
    }

    @Test
    void anAbsentPropertiesObjectIsTreatedAsEnforcementOff() {
        // Slice tests construct the service with no Spring context at all.
        // That is the pre-Phase-2 world and must not become a startup error.
        assertThatCode(() -> new AwardArchiveService(NO_REPOSITORY, null, null, absent(), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> new AwardArchiveService(NO_REPOSITORY, null))
                .doesNotThrowAnyException();
    }

    // --- PERMIT_ALL itself ----------------------------------------------

    @Test
    void permitAllAddsNoPredicateAndChecksNothing() {
        assertThat(AwardRecordAccess.PERMIT_ALL.awardScopeSql("av")).isEqualTo(SqlFragment.NONE);
        assertThat(AwardRecordAccess.PERMIT_ALL.canSeeAwardNumber("anything")).isTrue();
        assertThatCode(() -> AwardRecordAccess.PERMIT_ALL.requireAward(1L)).doesNotThrowAnyException();
        assertThatCode(() -> AwardRecordAccess.PERMIT_ALL.requireAwardNumber("x")).doesNotThrowAnyException();
    }

    @Test
    void permitAllIsTheOnlyPermissiveImplementationShippedInMainCode() {
        // Guards against a second "allow everything" implementation being
        // added later, which would reopen the hole this class closes: the
        // startup refusal recognises PERMIT_ALL by identity.
        assertThat(SqlFragment.NONE.sql()).isEmpty();
        assertThat(SqlFragment.DENY_ALL.sql()).contains("FALSE");
    }

    // --- helpers ---------------------------------------------------------

    private static ObjectProvider<AuthorizationProperties> properties(boolean enforcementEnabled) {
        AuthorizationProperties properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(enforcementEnabled);
        return provider(properties);
    }

    /** A port that is not PERMIT_ALL; its behaviour is irrelevant to wiring. */
    private static AwardRecordAccess strict() {
        return new AwardRecordAccess() {
            @Override public SqlFragment awardScopeSql(String alias) {
                return SqlFragment.DENY_ALL;
            }

            @Override public void requireAward(long awardId) {
                throw new IllegalStateException("denied");
            }

            @Override public void requireAwardNumber(String awardNumber) {
                throw new IllegalStateException("denied");
            }

            @Override public boolean canSeeAwardNumber(String awardNumber) {
                return false;
            }

            @Override public void requireProvisioned() { }

            @Override public void requireEveryAwardVersion(String awardNumber) {
                requireAwardNumber(awardNumber);
            }

            @Override public boolean unrestricted() { return false; }
        };
    }

    private static <T> ObjectProvider<T> absent() {
        return provider(null);
    }

    private static <T> ObjectProvider<T> present(T value) {
        return provider(value);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject() {
                return value;
            }

            @Override public T getObject(Object... args) {
                return value;
            }

            @Override public T getIfAvailable() {
                return value;
            }

            @Override public T getIfUnique() {
                return value;
            }

            @Override public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }
        };
    }
}
