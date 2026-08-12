package edu.bu.archive.application.document;

import edu.bu.archive.adapter.in.web.dto.document.DocumentExplorerResponse;
import edu.bu.archive.adapter.in.web.dto.document.DocumentExplorerResultResponse;
import edu.bu.archive.adapter.out.persistence.DocumentExplorerFilter;
import edu.bu.archive.adapter.out.persistence.DocumentExplorerRepository;
import edu.bu.archive.adapter.out.persistence.DocumentExplorerRow;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentExplorerServiceTest {

    private final DocumentExplorerRepository repository = mock(DocumentExplorerRepository.class);
    private final DocumentExplorerService service = new DocumentExplorerService(repository);

    private void stub(List<DocumentExplorerRow> rows, long total) {
        // Stubs both the filtered path (count/search/moduleFacets) and
        // the default-unfiltered fast path (countDefault/
        // searchDefaultPage/moduleFacetsDefault) identically, so tests
        // that only care about row-mapping/routing/pagination logic
        // (not which path a specific request takes) work regardless of
        // whether service.search()'s isUnfiltered() check routes to one
        // or the other - which path is actually taken is verified
        // separately by the isUnfiltered()-routing tests below.
        when(repository.count(any())).thenReturn(total);
        when(repository.search(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(rows);
        when(repository.moduleFacets(any())).thenReturn(List.of());
        when(repository.countDefault()).thenReturn(total);
        when(repository.searchDefaultPage(
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(rows);
        when(repository.moduleFacetsDefault()).thenReturn(List.of());
    }

    private DocumentExplorerResponse search(String module) {
        return service.search(
                null, null, null, module, null, null, null, false,
                null, null, null, false, null, null, null, null, null, 0, 25
        );
    }

    // --- Scenario 1: one result per module + document_number (pass-through, no duplication introduced) ---

    @Test
    void oneResultRowPerRepositoryRowNoDuplicationIntroducedByTheService() {
        stub(List.of(
                new DocumentExplorerRow("AWARD", "1037915", "204713-00001", "CARB-X",
                        "ACTIVE", "10", "Approved Award", "544", "1202020000", "Unit Name",
                        "9001", "Jane Smith", "PI", "NIH", "National Institutes of Health",
                        null, null, LocalDate.of(2020, 1, 1), "3561610", 2, 1, 0)
        ), 1L);

        DocumentExplorerResponse response = search(null);
        assertThat(response.results().content()).hasSize(1);
    }

    // --- Scenario 26: correct routes for all four modules ---

    @Test
    void awardRowRoutesByAwardId() {
        stub(List.of(new DocumentExplorerRow(
                "AWARD", "1037915", "204713-00001", "CARB-X", "ACTIVE", "10",
                "Approved Award", "544", "1202020000", "Unit", "9001", "Jane Smith",
                "PI", "NIH", "National Institutes of Health", null, null,
                LocalDate.of(2020, 1, 1), "3561610", 2, 1, 0
        )), 1L);
        DocumentExplorerResultResponse row = search(null).results().content().get(0);
        assertThat(row.targetRoute()).isEqualTo("/awards/3561610");
    }

    @Test
    void proposalRowRoutesByProposalNumber() {
        stub(List.of(new DocumentExplorerRow(
                "PROPOSAL", "430102", "01128961", "CARB-X", "ACTIVE", "3",
                "Funded", "3", null, null, "pi-1", "Jane Smith",
                "Principal Investigator", "NIH", "National Institutes of Health", null, null,
                LocalDate.of(2019, 6, 1), "01128961", 0, 1, 0
        )), 1L);
        DocumentExplorerResultResponse row = search(null).results().content().get(0);
        assertThat(row.targetRoute()).isEqualTo("/proposals/01128961");
    }

    @Test
    void negotiationRowRoutesByNegotiationId() {
        stub(List.of(new DocumentExplorerRow(
                "NEGOTIATION", "367756", "355", null, "ACTIVE", "1",
                "Fully Executed", null, null, null, "neg-1", "Pat Negotiator",
                "Negotiator", null, null, null, null,
                LocalDate.of(2021, 3, 1), "355", 0, 1, 0
        )), 1L);
        DocumentExplorerResultResponse row = search(null).results().content().get(0);
        assertThat(row.targetRoute()).isEqualTo("/negotiations/355");
        assertThat(row.primaryPersonRole()).isEqualTo("Negotiator");
    }

    @Test
    void subawardRowRoutesBySubawardId() {
        stub(List.of(new DocumentExplorerRow(
                "SUBAWARD", "343156", "1363", "Subaward Title", "ACTIVE", "07",
                "07. Executed", "1", null, null, "5001", "Site PI",
                "Site Investigator", null, null, "ORG-1", null,
                LocalDate.of(2018, 9, 1), "1363", 0, 1, 0
        )), 1L);
        DocumentExplorerResultResponse row = search(null).results().content().get(0);
        assertThat(row.targetRoute()).isEqualTo("/subawards/1363");
    }

    // --- Scenario 18/27: module filtering, invalid module (including IRB) rejected ---

    @Test
    void validModuleIsUppercasedAndBoundExactly() {
        stub(List.of(), 0L);
        search("award");
        verify(repository).count(argThat(filter -> filter.module().equals("AWARD")));
    }

    @Test
    void irbIsNotAnApprovedModuleAndIsRejected() {
        assertThatThrownBy(() -> search("IRB"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IRB");
    }

    @Test
    void unrecognizedModuleIsRejectedNotSilentlyWidened() {
        assertThatThrownBy(() -> search("NOT_A_REAL_MODULE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Scenario 23: invalid normalized status and sort rejected ---

    @Test
    void invalidNormalizedStatusIsRejected() {
        stub(List.of(), 0L);
        assertThatThrownBy(() -> service.search(
                null, null, null, null, "NOT_A_STATUS", null, null, false,
                null, null, null, false, null, null, null, null, null, 0, 25
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidSortIsRejected() {
        stub(List.of(), 0L);
        assertThatThrownBy(() -> service.search(
                null, null, null, null, null, null, null, false,
                null, null, null, false, null, null, null, null, "sourceText", 0, 25
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankModuleAndStatusMeanNoFilter() {
        // personName is set so this request is NOT fully unfiltered and
        // stays on the count(filter) path - the point of this test is
        // blank-module normalization, verified via the filter object
        // reaching the repository, not which repository method runs.
        stub(List.of(), 0L);
        service.search(
                null, null, null, null, null, null, null, false,
                null, "Smith", null, false, null, null, null, null, null, 0, 25
        );
        verify(repository).count(argThat(filter -> filter.module().equals("")));
    }

    // --- Scenario 22: pagination ---

    @Test
    void paginationClampsAndComputesOffset() {
        stub(List.of(), 130L);
        DocumentExplorerResponse response = service.search(
                null, null, null, null, null, null, null, false,
                null, null, null, false, null, null, null, null, null, 2, 25
        );
        assertThat(response.results().page()).isEqualTo(2);
        assertThat(response.results().totalElements()).isEqualTo(130L);
        assertThat(response.results().totalPages()).isEqualTo(6);
        // Fully unfiltered (module/status/person/etc. all blank) -> the
        // default fast path, not search(filter, ...). Pagination math
        // (page clamp, offset) is computed once in the service before
        // branching, so this is equally valid coverage of it.
        verify(repository).searchDefaultPage(eq(25), eq(50), eq("documentNumber"));
    }

    @Test
    void paginationClampsAndComputesOffsetOnTheFilteredPathToo() {
        stub(List.of(), 130L);
        DocumentExplorerResponse response = service.search(
                null, null, null, null, null, null, null, false,
                null, "Smith", null, false, null, null, null, null, null, 2, 25
        );
        assertThat(response.results().page()).isEqualTo(2);
        assertThat(response.results().totalElements()).isEqualTo(130L);
        assertThat(response.results().totalPages()).isEqualTo(6);
        verify(repository).search(any(), eq(25), eq(50));
    }

    // --- Scenario 24: SQL-injection-like input passed through as data ---

    @Test
    void sqlInjectionLikeInputIsPassedThroughUnmodifiedAsOrdinaryText() {
        stub(List.of(), 0L);
        String malicious = "'; DROP TABLE archive.award_version; --";
        service.search(
                malicious, null, null, null, null, null, null, false,
                null, null, null, false, null, null, null, null, null, 0, 25
        );
        verify(repository).count(argThat(filter -> filter.documentNumber().equals(malicious)));
    }

    // --- includeAnyUnit / piOnly pass through unchanged ---

    @Test
    void includeAnyUnitAndPiOnlyFlagsPassThroughToTheFilter() {
        stub(List.of(), 0L);
        service.search(
                null, null, null, "AWARD", null, null, "1202020000", true,
                null, null, null, true, null, null, null, null, null, 0, 25
        );
        verify(repository).count(argThat(filter -> filter.includeAnyUnit() && filter.piOnly()));
    }

    // --- Default-page fast path routing (2026-08-12 performance fix) ---

    @Test
    void emptyFilterCountUsesTheLightweightDefaultPathNotTheFullCte() {
        stub(List.of(), 0L);
        search(null);
        verify(repository).countDefault();
        verify(repository, org.mockito.Mockito.never()).count(any());
    }

    @Test
    void emptyFilterFacetsUseDirectPerModuleCountsNotTheFullCte() {
        stub(List.of(), 0L);
        search(null);
        verify(repository).moduleFacetsDefault();
        verify(repository, org.mockito.Mockito.never()).moduleFacets(any());
    }

    @Test
    void emptyFilterResultSelectionUsesTheLightPaginateFirstPathNotTheFullCte() {
        stub(List.of(), 0L);
        search(null);
        verify(repository).searchDefaultPage(eq(25), eq(0), eq("documentNumber"));
        verify(repository, org.mockito.Mockito.never()).search(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void anySingleFilterFallsBackToTheOriginalFullCtePathEntirely() {
        stub(List.of(), 0L);
        service.search(
                null, null, null, null, null, null, null, false,
                null, "Smith", null, false, null, null, null, null, null, 0, 25
        );
        verify(repository).count(any());
        verify(repository).search(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(repository).moduleFacets(any());
        verify(repository, org.mockito.Mockito.never()).countDefault();
        verify(repository, org.mockito.Mockito.never()).searchDefaultPage(
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(repository, org.mockito.Mockito.never()).moduleFacetsDefault();
    }

    @Test
    void dateRangeFilterAloneAlsoFallsBackToTheFilteredPath() {
        // A filter type not covered by the other routing tests -
        // dateFrom/dateTo depend on document_date, which the light
        // union DOES carry directly, but isUnfiltered() correctly still
        // routes to the filtered path since ANY filter (not just
        // person/unit ones) must use it.
        stub(List.of(), 0L);
        service.search(
                null, null, null, null, null, null, null, false,
                null, null, null, false, null, null,
                LocalDate.of(2020, 1, 1), null, null, 0, 25
        );
        verify(repository).count(any());
        verify(repository, org.mockito.Mockito.never()).countDefault();
    }

}
