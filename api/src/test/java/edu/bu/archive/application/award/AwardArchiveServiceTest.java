package edu.bu.archive.application.award;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyEdgeRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyNodeResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryCardRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.adapter.out.persistence.AwardAttachmentStorage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AwardArchiveServiceTest {

    private AwardArchiveRepository repository;
    private AwardArchiveService service;

    @BeforeEach
    void setUp() {
        repository = mock(AwardArchiveRepository.class);
        service = new AwardArchiveService(
                repository,
                mock(AwardAttachmentStorage.class)
        );
    }

    @Test
    void searchClampsPaginationAndBuildsAPageResponse() {
        AwardSearchResultResponse result = new AwardSearchResultResponse(
                3L, "100004-00003", 1, "Title", "Approved Award",
                "MICHAEL MCCLEAN", "Brown University",
                "SPH ENVIRONMENTAL HEALTH", BigDecimal.TEN, null, null
        );
        when(repository.countSearchAwards("%cancer%", "cancer"))
                .thenReturn(205L);
        when(repository.searchAwards("%cancer%", "cancer", 100, 0))
                .thenReturn(List.of(result));
        when(repository.findExactWorkflowDocumentMatch("cancer"))
                .thenReturn(Optional.empty());

        AwardSearchResponse response = service.search("cancer", -1, 500);
        PageResponse<AwardSearchResultResponse> page = response.results();

        assertThat(page.content()).containsExactly(result);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(100);
        assertThat(page.totalElements()).isEqualTo(205L);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isFalse();
        assertThat(response.exactDocumentMatch()).isNull();
        verify(repository).searchAwards("%cancer%", "cancer", 100, 0);
    }

    @Test
    void searchNormalizesANullQueryToAnEmptyWrappedPattern() {
        when(repository.countSearchAwards("%%", "")).thenReturn(0L);
        when(repository.searchAwards("%%", "", 25, 0))
                .thenReturn(List.of());
        when(repository.findExactWorkflowDocumentMatch(""))
                .thenReturn(Optional.empty());

        AwardSearchResponse response = service.search(null, 0, 25);

        assertThat(response.results().content()).isEmpty();
        verify(repository).searchAwards("%%", "", 25, 0);
    }

    @Test
    void searchAppliesTheApplicationWildcardSyntax() {
        when(repository.countSearchAwards("%105698%", "*105698*"))
                .thenReturn(0L);
        when(repository.searchAwards("%105698%", "*105698*", 25, 0))
                .thenReturn(List.of());
        when(repository.findExactWorkflowDocumentMatch("*105698*"))
                .thenReturn(Optional.empty());

        service.search("*105698*", 0, 25);

        verify(repository).searchAwards("%105698%", "*105698*", 25, 0);
    }

    @Test
    void searchSurfacesAnExactWorkflowDocumentNumberMatch() {
        AwardDocumentNumberMatchResponse match =
                new AwardDocumentNumberMatchResponse(
                        1135067L, "100567-00001", 6, "328797", "Award",
                        "Title", "Approved Award"
                );
        when(repository.countSearchAwards("%328797%", "328797"))
                .thenReturn(0L);
        when(repository.searchAwards("%328797%", "328797", 25, 0))
                .thenReturn(List.of());
        when(repository.findExactWorkflowDocumentMatch("328797"))
                .thenReturn(Optional.of(match));

        AwardSearchResponse response = service.search("328797", 0, 25);

        assertThat(response.exactDocumentMatch()).isEqualTo(match);
        assertThat(response.exactDocumentMatch().awardId())
                .isEqualTo(1135067L);
        assertThat(response.exactDocumentMatch().sequenceNumber())
                .isEqualTo(6);
        assertThat(response.results().content()).isEmpty();
    }

    @Test
    void findHierarchyReturnsASingleNodeTreeWhenNoHierarchyRowExists() {
        when(repository.findHierarchyRoot("100004-00003"))
                .thenReturn(Optional.empty());

        AwardSummaryCardRow card = new AwardSummaryCardRow(
                "100004-00003", 3L, 1, "Title", "Approved Award",
                "MICHAEL MCCLEAN", "Brown University",
                "SPH ENVIRONMENTAL HEALTH", BigDecimal.TEN
        );
        when(repository.findSummaryCards(List.of("100004-00003")))
                .thenReturn(List.of(card));

        AwardHierarchyResponse hierarchy =
                service.findHierarchy("100004-00003");

        assertThat(hierarchy.rootAwardNumber()).isEqualTo("100004-00003");
        assertThat(hierarchy.requestedAwardNumber())
                .isEqualTo("100004-00003");
        assertThat(hierarchy.selectedAwardPath())
                .containsExactly("100004-00003");
        assertThat(hierarchy.root().awardNumber())
                .isEqualTo("100004-00003");
        assertThat(hierarchy.root().parentAwardNumber()).isNull();
        assertThat(hierarchy.root().active()).isTrue();
        assertThat(hierarchy.root().children()).isEmpty();
    }

    @Test
    void findHierarchyThrowsNotFoundWhenTheAwardDoesNotExistAtAll() {
        when(repository.findHierarchyRoot("NO-SUCH-AWARD"))
                .thenReturn(Optional.empty());
        when(repository.findSummaryCards(List.of("NO-SUCH-AWARD")))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.findHierarchy("NO-SUCH-AWARD"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Award not found: NO-SUCH-AWARD");
    }

    @Test
    void findHierarchyBuildsARecursiveTreeAndResolvesTheSelectedPath() {
        // A self-referencing root row (parent_award_number is NOT NULL
        // on archive.award_hierarchy - a real root row still carries a
        // value, typically pointing back at itself), one child, and one
        // inactive grandchild.
        AwardHierarchyEdgeRow rootEdge = new AwardHierarchyEdgeRow(
                "100004-00001", "100004-00001", "100004-00001", "Y"
        );
        AwardHierarchyEdgeRow childEdge = new AwardHierarchyEdgeRow(
                "100004-00001", "100004-00002", "100004-00001", "Y"
        );
        AwardHierarchyEdgeRow grandchildEdge = new AwardHierarchyEdgeRow(
                "100004-00001", "100004-00003", "100004-00002", "N"
        );

        when(repository.findHierarchyRoot("100004-00003"))
                .thenReturn(Optional.of("100004-00001"));
        when(repository.findHierarchyEdges("100004-00001"))
                .thenReturn(List.of(rootEdge, childEdge, grandchildEdge));

        when(repository.findSummaryCards(anyCollection()))
                .thenReturn(List.of(
                        summaryCard("100004-00001"),
                        summaryCard("100004-00002"),
                        summaryCard("100004-00003")
                ));

        AwardHierarchyResponse hierarchy =
                service.findHierarchy("100004-00003");

        assertThat(hierarchy.rootAwardNumber()).isEqualTo("100004-00001");
        assertThat(hierarchy.requestedAwardNumber())
                .isEqualTo("100004-00003");
        assertThat(hierarchy.selectedAwardPath()).containsExactly(
                "100004-00001", "100004-00002", "100004-00003"
        );

        AwardHierarchyNodeResponse root = hierarchy.root();
        assertThat(root.awardNumber()).isEqualTo("100004-00001");
        // The resolved root's own (self-referencing) parent isn't
        // exposed as a meaningful parent at the top of the tree.
        assertThat(root.parentAwardNumber()).isNull();
        assertThat(root.children()).hasSize(1);

        AwardHierarchyNodeResponse child = root.children().get(0);
        assertThat(child.awardNumber()).isEqualTo("100004-00002");
        assertThat(child.parentAwardNumber()).isEqualTo("100004-00001");
        assertThat(child.children()).hasSize(1);

        AwardHierarchyNodeResponse grandchild = child.children().get(0);
        assertThat(grandchild.awardNumber()).isEqualTo("100004-00003");
        assertThat(grandchild.parentAwardNumber())
                .isEqualTo("100004-00002");
        assertThat(grandchild.active()).isFalse();
        assertThat(grandchild.children()).isEmpty();
    }

    @Test
    void findHierarchyPromotesTheRequestedAwardWhenTheRootRowIsMissing() {
        // Malformed/missing historical data: root_award_number does not
        // resolve to any edge in this family's own edge set.
        AwardHierarchyEdgeRow onlyEdge = new AwardHierarchyEdgeRow(
                "100004-99999", "100004-00003", "100004-00002", "Y"
        );

        when(repository.findHierarchyRoot("100004-00003"))
                .thenReturn(Optional.of("100004-99999"));
        when(repository.findHierarchyEdges("100004-99999"))
                .thenReturn(List.of(onlyEdge));
        when(repository.findSummaryCards(anyCollection()))
                .thenReturn(List.of(summaryCard("100004-00003")));

        AwardHierarchyResponse hierarchy =
                service.findHierarchy("100004-00003");

        assertThat(hierarchy.root().awardNumber())
                .isEqualTo("100004-00003");
        assertThat(hierarchy.root().parentAwardNumber()).isNull();
    }

    @Test
    void findHierarchyGuardsAgainstCyclesInMalformedHierarchyData() {
        // A -> parent B, B -> parent A: neither is a valid self-loop,
        // but together they form a 2-cycle a naive recursion would
        // never terminate on.
        AwardHierarchyEdgeRow edgeA = new AwardHierarchyEdgeRow(
                "A", "A", "B", "Y"
        );
        AwardHierarchyEdgeRow edgeB = new AwardHierarchyEdgeRow(
                "A", "B", "A", "Y"
        );

        when(repository.findHierarchyRoot("A"))
                .thenReturn(Optional.of("A"));
        when(repository.findHierarchyEdges("A"))
                .thenReturn(List.of(edgeA, edgeB));
        when(repository.findSummaryCards(anyCollection()))
                .thenReturn(List.of(summaryCard("A"), summaryCard("B")));

        AwardHierarchyResponse hierarchy = service.findHierarchy("A");

        // Termination itself (no StackOverflowError / infinite loop) is
        // the primary assertion for malformed cyclic data - the walk
        // stops the moment it revisits an award_number it has already
        // placed on the path.
        assertThat(hierarchy.root().awardNumber()).isEqualTo("A");
        assertThat(hierarchy.selectedAwardPath()).containsExactly("B", "A");
    }

    @Test
    void findSummaryThrowsNotFoundForAMissingAward() {
        when(repository.findSummaryByAwardId(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findSummary(999L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Award not found: 999");
    }

    @Test
    void findSummaryReturnsTheRepositoryMapping() {
        AwardSummaryResponse expected = new AwardSummaryResponse(
                3L, "100004-00003", 1, "Title", "Approved Award",
                "Brown University", null, "MICHAEL MCCLEAN",
                "SPH ENVIRONMENTAL HEALTH", null, null, null, null,
                BigDecimal.TEN, BigDecimal.TEN, "1", "Cost reimbursement",
                "28", "Invoice", null, null
        );
        when(repository.findSummaryByAwardId(3L))
                .thenReturn(Optional.of(expected));

        assertThat(service.findSummary(3L)).isEqualTo(expected);
    }

    @Test
    void findVersionsThrowsNotFoundForAMissingAwardId() {
        when(repository.findAwardNumberForId(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findVersions(999L, 0, 50))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Award not found: 999");
    }

    @Test
    void findVersionsResolvesTheAwardNumberThenDelegates() {
        AwardVersionSummaryResponse version =
                new AwardVersionSummaryResponse(
                        3L, "100004-00003", 1, "Approved Award",
                        "12", "Converted Record", null, null, null, null, true
                );
        when(repository.findAwardNumberForId(3L))
                .thenReturn(Optional.of("100004-00003"));
        when(repository.countVersions("100004-00003")).thenReturn(1L);
        when(repository.findVersionSummaries("100004-00003", 50, 0))
                .thenReturn(List.of(version));

        PageResponse<AwardVersionSummaryResponse> page =
                service.findVersions(3L, 0, 50);

        assertThat(page.content()).containsExactly(version);
        assertThat(page.totalElements()).isEqualTo(1L);
    }

    @Test
    void findVersionsAppliesTheSamePaginationClampingAsSearch() {
        when(repository.findAwardNumberForId(3L))
                .thenReturn(Optional.of("100004-00003"));
        when(repository.countVersions("100004-00003")).thenReturn(0L);
        when(repository.findVersionSummaries("100004-00003", 100, 0))
                .thenReturn(List.of());

        PageResponse<AwardVersionSummaryResponse> page =
                service.findVersions(3L, -1, 500);

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(100);
        verify(repository).findVersionSummaries("100004-00003", 100, 0);
    }

    private AwardSummaryCardRow summaryCard(String awardNumber) {
        return new AwardSummaryCardRow(
                awardNumber, 1L, 1, "Title", "Status", "PI",
                "Sponsor", "Lead Unit", BigDecimal.ONE
        );
    }
}
