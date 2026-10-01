package edu.bu.archive.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.bu.archive.application.authorization.AuthorizationPathNotScopedException;
import edu.bu.archive.application.authorization.RecordAuthorizationService;

class RecordAuthorizationInterceptorTest {

    private final RecordAuthorizationService authorization = mock(RecordAuthorizationService.class);
    private final RecordAuthorizationInterceptor interceptor = new RecordAuthorizationInterceptor(authorization);

    private boolean pass(String path) {
        return interceptor.preHandle(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), new Object());
    }

    @Test
    void doesNothingWhenEnforcementIsOff() {
        when(authorization.enforced()).thenReturn(false);
        assertThat(pass("/api/negotiations")).isTrue();
        verify(authorization, never()).requireProvisioned();
    }

    @Test
    void restrictedUsersReachOnlyScopedPathsAndRecordsAreCheckedFirst() {
        when(authorization.enforced()).thenReturn(true);
        when(authorization.unrestricted()).thenReturn(false);

        assertThat(pass("/api/v1/awards/search")).isTrue();
        assertThat(pass("/api/v1/awards/123/people")).isTrue();
        verify(authorization).requireAward(123L);
        assertThat(pass("/api/v1/awards/990001-00001/hierarchy")).isTrue();
        verify(authorization).requireAwardNumber("990001-00001");
        assertThat(pass("/api/v1/proposals/55/versions")).isTrue();
        verify(authorization).requireProposal(55L);
        assertThat(pass("/api/proposals/SYN-1/history")).isTrue();
        verify(authorization).requireProposalNumber("SYN-1");

        assertThatThrownBy(() -> pass("/api/v1/awards/123/report.pdf")).isInstanceOf(AuthorizationPathNotScopedException.class);
        assertThatThrownBy(() -> pass("/api/negotiations")).isInstanceOf(AuthorizationPathNotScopedException.class);
        assertThatThrownBy(() -> pass("/api/v1/documents")).isInstanceOf(AuthorizationPathNotScopedException.class);
        assertThatThrownBy(() -> pass("/api/ai/awards/990001-00001/summary")).isInstanceOf(AuthorizationPathNotScopedException.class);
        assertThatThrownBy(() -> pass("/api/proposals/families")).isInstanceOf(AuthorizationPathNotScopedException.class);
    }

    @Test
    void centralUsersPassEveryPathButStillMustBeProvisioned() {
        when(authorization.enforced()).thenReturn(true);
        when(authorization.unrestricted()).thenReturn(true);
        assertThat(pass("/api/negotiations")).isTrue();
        verify(authorization).requireProvisioned();
    }

    @Test
    void theAccessStatusEndpointIsReachableBeforeProvisioning() {
        when(authorization.enforced()).thenReturn(true);
        assertThat(pass("/api/v1/me/access")).isTrue();
        verify(authorization, never()).requireProvisioned();
    }
}
