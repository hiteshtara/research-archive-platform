package edu.bu.archive.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.bu.archive.application.authorization.AuthorizationPathNotScopedException;
import edu.bu.archive.application.authorization.PartialFamilyAccessException;
import edu.bu.archive.application.authorization.RecordAuthorizationService;
import edu.bu.archive.application.authorization.RecordNotAccessibleException;

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
        // Documents: scoped per module inside the query (Award/Proposal; other modules excluded).
        assertThat(pass("/api/v1/documents")).isTrue();
        assertThat(pass("/api/documents/search")).isTrue();
        assertThatThrownBy(() -> pass("/api/v1/documents/extra")).isInstanceOf(AuthorizationPathNotScopedException.class);

        assertThatThrownBy(() -> pass("/api/negotiations")).isInstanceOf(AuthorizationPathNotScopedException.class);
        assertThatThrownBy(() -> pass("/api/ai/awards/990001-00001/summary/extra")).isInstanceOf(AuthorizationPathNotScopedException.class);
        assertThatThrownBy(() -> pass("/api/awards/990001-00001/history")).isInstanceOf(AuthorizationPathNotScopedException.class);
        assertThatThrownBy(() -> pass("/api/v1/explorer/units")).isInstanceOf(AuthorizationPathNotScopedException.class);
        assertThatThrownBy(() -> pass("/api/v1/negotiations/1")).isInstanceOf(AuthorizationPathNotScopedException.class);
        assertThatThrownBy(() -> pass("/api/proposals/families")).isInstanceOf(AuthorizationPathNotScopedException.class);
    }

    @Test
    void awardSubPathsAreAnExplicitAllowListAndReportsAreAllowedForAnInScopeAward() {
        when(authorization.enforced()).thenReturn(true);
        when(authorization.unrestricted()).thenReturn(false);

        for (String sub : List.of("", "/summary", "/versions", "/people", "/unit-details", "/unit-contacts",
                "/sponsor-contacts", "/central-administration-contacts", "/amounts", "/time-and-money/summary",
                "/time-and-money/actions", "/time-and-money/history", "/time-and-money/transactions/77",
                "/time-and-money/documents/TNM-1", "/terms", "/custom-data", "/comments", "/sap-transmissions",
                "/attachments", "/attachments/5/download", "/report.pdf", "/report-with-attachments.pdf",
                "/budget/summary", "/budget/versions", "/budget/periods", "/budget/line-items",
                "/budget/personnel", "/funding-proposals", "/funding-subawards", "/negotiations")) {
            assertThat(pass("/api/v1/awards/123" + sub)).as(sub).isTrue();
        }
        verify(authorization, times(30)).requireAward(123L);

        for (String sub : List.of("/", "/new-section", "/report.pdf;x=1", "/time-and-money/transactions/abc",
                "/attachments/5/download/x", "/budget", "/summary/extra", "/report.docx")) {
            assertThatThrownBy(() -> pass("/api/v1/awards/124" + sub)).as(sub)
                    .isInstanceOf(AuthorizationPathNotScopedException.class);
        }
        verify(authorization, never()).requireAward(124L);
    }

    @Test
    void proposalSubPathsAreAnExplicitAllowList() {
        when(authorization.enforced()).thenReturn(true);
        when(authorization.unrestricted()).thenReturn(false);

        for (String sub : List.of("", "/versions", "/people", "/units", "/attachments", "/attachments/5/download",
                "/comments", "/funded-awards", "/custom-data")) {
            assertThat(pass("/api/v1/proposals/55" + sub)).as(sub).isTrue();
        }
        verify(authorization, times(9)).requireProposal(55L);

        for (String sub : List.of("/", "/new-thing", "/versions/extra", "/attachments/abc/download",
                "/attachments/5/download/x", "/comments;x=1", "/report.pdf", "/funded-awards/1")) {
            assertThatThrownBy(() -> pass("/api/v1/proposals/56" + sub)).as(sub)
                    .isInstanceOf(AuthorizationPathNotScopedException.class);
        }
        verify(authorization, never()).requireProposal(56L);

        // Central users are not limited to the allow-list.
        when(authorization.unrestricted()).thenReturn(true);
        assertThat(pass("/api/v1/proposals/56/new-thing")).isTrue();
    }

    @Test
    void anOutOfScopeAwardIsRefusedBeforeAReportIsBuilt() {
        when(authorization.enforced()).thenReturn(true);
        when(authorization.unrestricted()).thenReturn(false);
        doThrow(new RecordNotAccessibleException()).when(authorization).requireAward(9L);
        assertThatThrownBy(() -> pass("/api/v1/awards/9/report.pdf")).isInstanceOf(RecordNotAccessibleException.class);
    }

    @Test
    void fileFinderExplorerAndAiAreCheckedForRestrictedUsers() {
        when(authorization.enforced()).thenReturn(true);
        when(authorization.unrestricted()).thenReturn(false);

        assertThat(pass("/api/v1/attachments/search")).isTrue();

        MockHttpServletRequest byNumber = new MockHttpServletRequest("GET", "/api/v1/explorer/awards");
        byNumber.addParameter("awardNumber", "990001-00001");
        assertThat(interceptor.preHandle(byNumber, new MockHttpServletResponse(), new Object())).isTrue();
        verify(authorization).requireAwardNumber("990001-00001");

        MockHttpServletRequest byId = new MockHttpServletRequest("GET", "/api/v1/explorer/award-versions");
        byId.addParameter("awardId", "9000101");
        assertThat(interceptor.preHandle(byId, new MockHttpServletResponse(), new Object())).isTrue();
        verify(authorization).requireAward(9000101L);

        MockHttpServletRequest twoValues = new MockHttpServletRequest("GET", "/api/v1/explorer/award-versions");
        twoValues.addParameter("awardId", "1", "2");
        assertThatThrownBy(() -> interceptor.preHandle(twoValues, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(RecordNotAccessibleException.class);
        assertThatThrownBy(() -> pass("/api/v1/explorer/award-versions")).isInstanceOf(RecordNotAccessibleException.class);
        MockHttpServletRequest notANumber = new MockHttpServletRequest("GET", "/api/v1/explorer/award-versions");
        notANumber.addParameter("awardId", "abc");
        assertThatThrownBy(() -> interceptor.preHandle(notANumber, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(RecordNotAccessibleException.class);

        for (String ai : List.of("summary", "questions", "evidence-search")) {
            MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/ai/awards/990004-00001/" + ai);
            assertThat(interceptor.preHandle(post, new MockHttpServletResponse(), new Object())).isTrue();
        }
        verify(authorization, times(3)).requireEveryAwardVersion("990004-00001");
        doThrow(new PartialFamilyAccessException()).when(authorization).requireEveryAwardVersion("990001-00001");
        assertThatThrownBy(() -> pass("/api/ai/awards/990001-00001/summary")).isInstanceOf(PartialFamilyAccessException.class);
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
