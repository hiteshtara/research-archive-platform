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
    private final DocumentExplorerService service = new DocumentExplorerService(repository,
                null, null);

    private void stub(List<DocumentExplorerRow> rows, long total) {
        // Stubs both the filtered path (count/search/moduleFacets) and
        // the default-unfiltered fast path (countDefault/
        // searchDefaultPage/moduleFacetsDefault) identically, so tests
        // that only care about row-mapping/routing/pagination logic
        // (not which path a specific request takes) work regardless of
        // whether service.search()'s isUnfiltered() check routes to one
        // or the other - which path is actually taken is verified
        // separately by the isUnfiltered()-routing tests below.
        // DEF-5: the response's totalElements is now derived by summing
        // the per-module facet counts rather than from a separate
        // COUNT(*), so every facet stub has to carry `total` for the
        // pagination assertions to mean anything. One row is enough -
        // the service sums whatever rows it is given.
        List<DocumentExplorerRepository.ModuleFacetRow> facets =
                List.of(new DocumentExplorerRepository.ModuleFacetRow("AWARD", total));
        when(repository.count(any())).thenReturn(total);
        when(repository.search(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(rows);
        when(repository.moduleFacets(any())).thenReturn(facets);
        when(repository.countDefault()).thenReturn(total);
        when(repository.searchDefaultPage(
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(rows);
        when(repository.moduleFacetsDefault()).thenReturn(facets);
        // DEF-5 filtered light path.
        when(repository.moduleFacetsFilteredLight(any())).thenReturn(facets);
        when(repository.searchFilteredLight(any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(rows);
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
    void proposalRowRoutesToItsOwnVersionNotTheFamilyPage() {
        /*
         * The target is proposal_id - one version - not proposal_number,
         * which every version of a proposal shares. Routing on the
         * family number sent all of a proposal's documents to
         * "/proposals/{number}", which redirects to the current version,
         * so opening version 2 showed version 4.
         */
        stub(List.of(new DocumentExplorerRow(
                "PROPOSAL", "430102", "01128961", "CARB-X", "ACTIVE", "3",
                "Funded", "3", null, null, "pi-1", "Jane Smith",
                "Principal Investigator", "NIH", "National Institutes of Health", null, null,
                LocalDate.of(2019, 6, 1), "7003", 0, 1, 0
        )), 1L);
        DocumentExplorerResultResponse row = search(null).results().content().get(0);
        assertThat(row.targetRoute()).isEqualTo("/proposals/dashboard/7003");
        // The family number is still carried for display.
        assertThat(row.businessRecordNumber()).isEqualTo("01128961");
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
        verify(repository).moduleFacetsFilteredLight(
                argThat(filter -> filter.module().equals("AWARD")));
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
        verify(repository).moduleFacets(argThat(filter -> filter.module().equals("")));
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
        verify(repository).moduleFacetsFilteredLight(
                argThat(filter -> filter.documentNumber().equals(malicious)));
    }

    // --- includeAnyUnit / piOnly pass through unchanged ---

    @Test
    void includeAnyUnitAndPiOnlyFlagsPassThroughToTheFilter() {
        stub(List.of(), 0L);
        service.search(
                null, null, null, "AWARD", null, null, "1202020000", true,
                null, null, null, true, null, null, null, null, null, 0, 25
        );
        verify(repository).moduleFacets(
                argThat(filter -> filter.includeAnyUnit() && filter.piOnly()));
        // Both flags read a column the light union does not carry, so
        // this request must NOT be routed to the light path.
        verify(repository, org.mockito.Mockito.never()).moduleFacetsFilteredLight(any());
        verify(repository, org.mockito.Mockito.never()).searchFilteredLight(
                any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    // --- Default-page fast path routing (2026-08-12 performance fix) ---

    @Test
    void emptyFilterTotalComesFromTheDefaultFacetsAndNeverTheFullCte() {
        // DEF-5 replaced the separate COUNT(*) with a sum of the facet
        // counts, so the assertion is no longer "countDefault() ran" but
        // "no COUNT(*) query ran at all, on either path".
        stub(List.of(), 7L);
        DocumentExplorerResponse response = search(null);
        assertThat(response.results().totalElements()).isEqualTo(7L);
        verify(repository).moduleFacetsDefault();
        verify(repository, org.mockito.Mockito.never()).count(any());
        verify(repository, org.mockito.Mockito.never()).countDefault();
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
        verify(repository).search(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(repository).moduleFacets(any());
        verify(repository, org.mockito.Mockito.never()).count(any());
        verify(repository, org.mockito.Mockito.never()).countDefault();
        verify(repository, org.mockito.Mockito.never()).searchDefaultPage(
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(repository, org.mockito.Mockito.never()).moduleFacetsDefault();
    }

    @Test
    void dateRangeFilterAloneUsesTheFilteredLightPathNotTheHeavyCte() {
        // dateFrom/dateTo match on document_date, which the light union
        // carries directly for all four modules, so DEF-5 routes this
        // to the filtered light path. Before DEF-5 a date filter paid
        // the full Award person/unit aggregation for nothing.
        stub(List.of(), 0L);
        service.search(
                null, null, null, null, null, null, null, false,
                null, null, null, false, null, null,
                LocalDate.of(2020, 1, 1), null, null, 0, 25
        );
        verify(repository).moduleFacetsFilteredLight(any());
        verify(repository).searchFilteredLight(any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(repository, org.mockito.Mockito.never()).moduleFacets(any());
        verify(repository, org.mockito.Mockito.never()).search(
                any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(repository, org.mockito.Mockito.never()).countDefault();
    }

    // --- DEF-5: filtered light path routing ---

    private void assertUsedLightFilteredPath() {
        verify(repository).moduleFacetsFilteredLight(any());
        verify(repository).searchFilteredLight(any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(repository, org.mockito.Mockito.never()).moduleFacets(any());
        verify(repository, org.mockito.Mockito.never()).search(
                any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(repository, org.mockito.Mockito.never()).count(any());
    }

    private void assertUsedHeavyFilteredPath() {
        verify(repository).moduleFacets(any());
        verify(repository).search(any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(repository, org.mockito.Mockito.never()).moduleFacetsFilteredLight(any());
        verify(repository, org.mockito.Mockito.never()).searchFilteredLight(
                any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(repository, org.mockito.Mockito.never()).count(any());
    }

    @Test
    void aTextQueryUsesTheLightFilteredPath() {
        // The measured DEF-5 case: Documents + a text query across all
        // modules, 43.4s before this routing existed.
        stub(List.of(), 0L);
        service.search(
                null, null, "NSF", null, null, null, null, false,
                null, null, null, false, null, null, null, null, null, 0, 25
        );
        assertUsedLightFilteredPath();
    }

    @Test
    void aLeadUnitFilterWithoutIncludeAnyUnitUsesTheLightFilteredPath() {
        stub(List.of(), 0L);
        service.search(
                null, null, null, null, null, null, "1202020000", false,
                null, null, null, false, null, null, null, null, null, 0, 25
        );
        assertUsedLightFilteredPath();
    }

    @Test
    void aSponsorCodeFilterUsesTheLightFilteredPath() {
        stub(List.of(), 0L);
        service.search(
                null, null, null, null, null, null, null, false,
                null, null, null, false, "0100", null, null, null, null, 0, 25
        );
        assertUsedLightFilteredPath();
    }

    @Test
    void aLeadUnitFilterWithIncludeAnyUnitStaysOnTheHeavyPath() {
        // "Any associated unit" widens the match through
        // award_any_units, which the light union does not carry.
        stub(List.of(), 0L);
        service.search(
                null, null, null, null, null, null, "1202020000", true,
                null, null, null, false, null, null, null, null, null, 0, 25
        );
        assertUsedHeavyFilteredPath();
    }

    @Test
    void includeAnyUnitWithoutAUnitNumberIsStillLightEligible() {
        // The flag alone filters nothing - the unit clause is a
        // tautology when unitNumber is empty - so it must not force the
        // expensive path.
        stub(List.of(), 0L);
        service.search(
                null, null, "NSF", null, null, null, null, true,
                null, null, null, false, null, null, null, null, null, 0, 25
        );
        assertUsedLightFilteredPath();
    }

    @Test
    void aPersonIdFilterStaysOnTheHeavyPath() {
        stub(List.of(), 0L);
        service.search(
                null, null, null, null, null, null, null, false,
                "12345", null, null, false, null, null, null, null, null, 0, 25
        );
        assertUsedHeavyFilteredPath();
    }

    @Test
    void aPersonRoleFilterStaysOnTheHeavyPath() {
        stub(List.of(), 0L);
        service.search(
                null, null, null, null, null, null, null, false,
                null, null, "PI", false, null, null, null, null, null, 0, 25
        );
        assertUsedHeavyFilteredPath();
    }

    @Test
    void aPiOnlyFilterStaysOnTheHeavyPath() {
        stub(List.of(), 0L);
        service.search(
                null, null, null, null, null, null, null, false,
                null, null, null, true, null, null, null, null, null, 0, 25
        );
        assertUsedHeavyFilteredPath();
    }

    @Test
    void noFilteredRequestIssuesASeparateCountQueryOnEitherPath() {
        // DEF-5's other half: the total is summed from the facets, so a
        // filtered request evaluates the union twice, not three times.
        stub(List.of(), 9L);

        DocumentExplorerResponse light = service.search(
                null, null, "NSF", null, null, null, null, false,
                null, null, null, false, null, null, null, null, null, 0, 25
        );
        assertThat(light.results().totalElements()).isEqualTo(9L);

        DocumentExplorerResponse heavy = service.search(
                null, null, null, null, null, null, null, false,
                null, "Smith", null, false, null, null, null, null, null, 0, 25
        );
        assertThat(heavy.results().totalElements()).isEqualTo(9L);

        verify(repository, org.mockito.Mockito.never()).count(any());
        verify(repository, org.mockito.Mockito.never()).countDefault();
    }

    @Test
    void theTotalIsTheSumOfEveryModuleFacetNotJustTheFirst() {
        // A module that matches nothing contributes no GROUP BY row, so
        // the sum has to come from whatever rows arrive - never from a
        // single row or a hardcoded module list.
        when(repository.moduleFacetsFilteredLight(any())).thenReturn(List.of(
                new DocumentExplorerRepository.ModuleFacetRow("AWARD", 1734L),
                new DocumentExplorerRepository.ModuleFacetRow("PROPOSAL", 12L),
                new DocumentExplorerRepository.ModuleFacetRow("SUBAWARD", 3L)));
        when(repository.searchFilteredLight(any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        DocumentExplorerResponse response = service.search(
                null, null, "NSF", null, null, null, null, false,
                null, null, null, false, null, null, null, null, null, 0, 25
        );

        assertThat(response.results().totalElements()).isEqualTo(1749L);
        assertThat(response.moduleFacets()).hasSize(3);
    }

}
