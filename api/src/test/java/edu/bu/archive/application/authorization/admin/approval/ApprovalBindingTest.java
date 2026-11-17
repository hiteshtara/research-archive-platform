package edu.bu.archive.application.authorization.admin.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.admin.GrantSpecification;

/** The Java mirror of {@code ck_pa_binding_shape}, and binding equality. */
class ApprovalBindingTest {

    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_SYNTHETIC";

    @Test
    void eachFactoryProducesTheShapeItsActionRequires() {
        GrantSpecification central = new GrantSpecification(GrantType.CENTRAL, null, false, null,
                null, "synthetic central grant");
        GrantSpecification unit = new GrantSpecification(GrantType.UNIT, "SYN-U-1", true, null,
                null, "synthetic unit grant");

        assertThatCode(() -> ApprovalBinding.none().requireShapeFor(PrivilegeAction.ADMIN_ASSIGN))
                .doesNotThrowAnyException();
        assertThatCode(() -> ApprovalBinding.none().requireShapeFor(PrivilegeAction.ADMIN_RESTORE))
                .doesNotThrowAnyException();
        assertThatCode(() -> ApprovalBinding.forGrant(central).requireShapeFor(PrivilegeAction.CENTRAL_GRANT))
                .doesNotThrowAnyException();
        assertThatCode(() -> ApprovalBinding.forGrant(unit).requireShapeFor(PrivilegeAction.SELF_GRANT))
                .doesNotThrowAnyException();
        assertThatCode(() -> ApprovalBinding.forWidening(7L, true, null)
                .requireShapeFor(PrivilegeAction.SELF_WIDEN)).doesNotThrowAnyException();
        assertThatCode(() -> ApprovalBinding.forLink(ISSUER, "sub-1")
                .requireShapeFor(PrivilegeAction.LINK_PRIVILEGED)).doesNotThrowAnyException();
    }

    @Test
    void aBindingVaguerOrWiderThanItsActionIsRefused() {
        GrantSpecification unit = new GrantSpecification(GrantType.UNIT, "SYN-U-1", false, null,
                null, "synthetic unit grant");
        assertThatThrownBy(() -> ApprovalBinding.forGrant(unit).requireShapeFor(PrivilegeAction.CENTRAL_GRANT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalBinding.none().requireShapeFor(PrivilegeAction.LINK_PRIVILEGED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalBinding.forLink(ISSUER, "sub-1")
                .requireShapeFor(PrivilegeAction.ADMIN_ASSIGN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalBinding.none().requireShapeFor(PrivilegeAction.SELF_WIDEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theKualiPrincipalIsBoundOnLinksOnly() {
        ApprovalBinding withPrincipal = ApprovalBinding.forLink(ISSUER, "sub-1",
                new edu.bu.archive.application.authorization.KualiPersonId("SYN-KP-1"));
        assertThatCode(() -> withPrincipal.requireShapeFor(PrivilegeAction.LINK_PRIVILEGED))
                .doesNotThrowAnyException();
        assertThat(withPrincipal).isNotEqualTo(ApprovalBinding.forLink(ISSUER, "sub-1"));
        assertThat(withPrincipal.describe()).contains("kualiPersonId=SYN-KP-1");
        assertThat(ApprovalBinding.forLink(ISSUER, "sub-1").describe()).contains("kualiPersonId=none");
        assertThatThrownBy(() -> new ApprovalBinding(null, null, null, null, null, null, null,
                null, null, "SYN-KP-1").requireShapeFor(PrivilegeAction.ADMIN_ASSIGN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiryIsComparedAtDatabasePrecision() {
        Instant nanos = Instant.parse("2030-01-01T00:00:00.123456789Z");
        assertThat(ApprovalBinding.forWidening(1L, false, nanos))
                .isEqualTo(ApprovalBinding.forWidening(1L, false,
                        Instant.parse("2030-01-01T00:00:00.123456Z")));
        assertThat(ApprovalBinding.forWidening(1L, false, nanos))
                .isNotEqualTo(ApprovalBinding.forWidening(1L, true, nanos));
    }
}
