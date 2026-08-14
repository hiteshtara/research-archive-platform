package edu.bu.archive.application.security;

import edu.bu.archive.exception.AttachmentAccessDeniedException;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentAuthorizationServiceTest {

    private final AttachmentAuthorizationService service =
            new AttachmentAuthorizationService();

    private Authentication authenticationWith(String... authorities) {
        List<SimpleGrantedAuthority> granted = List.of(authorities).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new TestingAuthenticationToken("user", "credentials", granted);
    }

    @Test
    void authorityHolderIsGrantedAccess() {
        Authentication authentication = authenticationWith(
                AttachmentAuthorizationService.ATTACHMENT_VIEWER_AUTHORITY
        );

        assertThatCode(() -> service.requireAttachmentAccess(authentication))
                .doesNotThrowAnyException();
    }

    @Test
    void authenticatedWithoutTheAuthorityIsDenied() {
        Authentication authentication = authenticationWith("ROLE_SomeOtherGroup");

        assertThatThrownBy(() -> service.requireAttachmentAccess(authentication))
                .isInstanceOf(AttachmentAccessDeniedException.class);
    }

    @Test
    void authenticatedWithNoAuthoritiesAtAllIsDenied() {
        Authentication authentication = authenticationWith();

        assertThatThrownBy(() -> service.requireAttachmentAccess(authentication))
                .isInstanceOf(AttachmentAccessDeniedException.class);
    }

    @Test
    void nullAuthenticationIsDenied() {
        assertThatThrownBy(() -> service.requireAttachmentAccess(null))
                .isInstanceOf(AttachmentAccessDeniedException.class);
    }

    @Test
    void anotherRoleSharingAPrefixIsNotConfusedWithTheRealAuthority() {
        // "ROLE_ArchiveAttachmentViewerReadOnly" must not satisfy a check
        // for exactly "ROLE_ArchiveAttachmentViewer" - the comparison is
        // an exact authority-string match, never a prefix/contains check.
        Authentication authentication = authenticationWith(
                AttachmentAuthorizationService.ATTACHMENT_VIEWER_AUTHORITY
                        + "ReadOnly"
        );

        assertThatThrownBy(() -> service.requireAttachmentAccess(authentication))
                .isInstanceOf(AttachmentAccessDeniedException.class);
    }
}
