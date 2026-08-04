package edu.bu.archive.application.service;

import edu.bu.archive.adapter.in.web.dto.GlobalSearchItemResponse;
import edu.bu.archive.adapter.in.web.dto.GlobalSearchResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.out.persistence.GlobalSearchRepository;
import edu.bu.archive.adapter.out.persistence.IrbGlobalSearchRow;
import edu.bu.archive.application.award.AwardArchiveService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * GlobalSearchService orchestrates Global Search as a fan-out over
 * IRB's own repository search and Award's own reused
 * AwardArchiveService.search - never a shared cross-domain SQL view.
 * These tests mock both domain dependencies directly rather than their
 * own lower-level repositories, since this service's only job is
 * fan-out/mapping/ranking/merge, not domain query logic (already
 * covered by GlobalSearchRepository's/AwardArchiveRepository's own
 * tests).
 */
class GlobalSearchServiceTest {

    private GlobalSearchRepository irbSearchRepository;
    private AwardArchiveService awardArchiveService;
    private GlobalSearchService service;

    @BeforeEach
    void setUp() {
        irbSearchRepository = mock(GlobalSearchRepository.class);
        awardArchiveService = mock(AwardArchiveService.class);
        service = new GlobalSearchService(irbSearchRepository, awardArchiveService);

        // Default: empty Award results unless a test overrides them -
        // avoids every test needing to stub the full search() call.
        when(awardArchiveService.search(anyString(), eq(0), anyInt()))
                .thenReturn(new AwardSearchResponse(
                        null,
                        new PageResponse<>(List.of(), 0, 25, 0, 0, true, true)
                ));
    }

    private IrbGlobalSearchRow irbRow(
            Long recordId,
            Long protocolId,
            String identifier,
            String title,
            String matchedField,
            int searchRank
    ) {
        return new IrbGlobalSearchRow(
                recordId, protocolId, "IRB", identifier, "PN-1",
                title, "Active", "Jane PI", "Human Subjects",
                searchRank, matchedField, identifier
        );
    }

    // --- Normalization / fan-out -----------------------------------------

    @Test
    void normalizesTheQueryOnceAndPassesTheTrimmedValueToBothDomains() {
        when(irbSearchRepository.search("campbell", 25)).thenReturn(List.of());

        service.search("  campbell  ");

        org.mockito.Mockito.verify(irbSearchRepository).search("campbell", 25);
        org.mockito.Mockito.verify(awardArchiveService).search("campbell", 0, 25);
    }

    @Test
    void appliesTheSamePerDomainLimitToBothSearches() {
        when(irbSearchRepository.search(anyString(), eq(25))).thenReturn(List.of());

        service.search("test");

        org.mockito.Mockito.verify(irbSearchRepository).search("test", 25);
        org.mockito.Mockito.verify(awardArchiveService).search("test", 0, 25);
    }

    // --- IRB mapping -------------------------------------------------------

    @Test
    void mapsAnIrbRowWithARecordIdToTheRecordRoute() {
        when(irbSearchRepository.search("campbell", 25)).thenReturn(List.of(
                irbRow(42L, 99L, "STU-1", "Campbell Study", "Title", 7)
        ));

        GlobalSearchResponse response = service.search("campbell");

        GlobalSearchItemResponse item = response.results().get(0);
        assertThat(item.module()).isEqualTo("IRB");
        assertThat(item.recordId()).isEqualTo(42L);
        assertThat(item.route()).isEqualTo("/irb/record/42");
        assertThat(item.protocolId()).isEqualTo(99L);
    }

    @Test
    void mapsAnIrbRowWithNoRecordIdToTheHistoryRoute() {
        when(irbSearchRepository.search("campbell", 25)).thenReturn(List.of(
                irbRow(null, 99L, "STU-1", "Campbell Study", "Title", 7)
        ));

        GlobalSearchResponse response = service.search("campbell");

        GlobalSearchItemResponse item = response.results().get(0);
        assertThat(item.route()).isEqualTo("/irb/history/99");
    }

    @Test
    void anIrbRowWithNeitherRecordIdNorProtocolIdHasANullRoute() {
        when(irbSearchRepository.search("campbell", 25)).thenReturn(List.of(
                irbRow(null, null, "STU-1", "Campbell Study", "Title", 7)
        ));

        GlobalSearchResponse response = service.search("campbell");

        assertThat(response.results().get(0).route()).isNull();
    }

    // --- Award mapping -------------------------------------------------------

    @Test
    void mapsAnExactWorkflowDocumentMatchRankedFirst() {
        // "1054966" is numeric, so the Award-ID direct-lookup path also
        // fires - it finds no Award with that ID (it's a document
        // number, not an awardId) and is silently omitted, same as any
        // other numeric-query miss.
        when(awardArchiveService.findSummary(1054966L))
                .thenThrow(new NoSuchElementException("Award not found: 1054966"));
        when(awardArchiveService.search("1054966", 0, 25)).thenReturn(
                new AwardSearchResponse(
                        new AwardDocumentNumberMatchResponse(
                                3831872L, "103692-00002", 46, "1054966",
                                "Award", "Cancer Research Grant", "Active"
                        ),
                        new PageResponse<>(List.of(), 0, 25, 0, 0, true, true)
                )
        );

        GlobalSearchResponse response = service.search("1054966");

        GlobalSearchItemResponse item = response.results().get(0);
        assertThat(item.module()).isEqualTo("AWARD");
        assertThat(item.awardId()).isEqualTo(3831872L);
        assertThat(item.sequenceNumber()).isEqualTo(46);
        assertThat(item.documentNumber()).isEqualTo("1054966");
        assertThat(item.route()).isEqualTo("/awards/3831872");
        assertThat(item.matchedField()).isEqualTo("Workflow Document Number");
    }

    @Test
    void mapsAnAwardIdDirectLookupForANumericQuery() {
        when(awardArchiveService.findSummary(3831872L)).thenReturn(
                new AwardSummaryResponse(
                        3831872L, "103692-00002", 46, "Cancer Research Grant",
                        "Active", "NIH", null, "Dr. Smith", "Medicine",
                        null, null, null, null, BigDecimal.TEN, BigDecimal.TEN,
                        null, null, null, null, null, null
                )
        );

        GlobalSearchResponse response = service.search("3831872");

        GlobalSearchItemResponse item = response.results().stream()
                .filter(r -> "Award ID".equals(r.matchedField()))
                .findFirst().orElseThrow();
        assertThat(item.awardId()).isEqualTo(3831872L);
        assertThat(item.route()).isEqualTo("/awards/3831872");
    }

    @Test
    void doesNotAttemptAnAwardIdLookupForANonNumericQuery() {
        service.search("campbell");

        org.mockito.Mockito.verify(awardArchiveService, org.mockito.Mockito.never())
                .findSummary(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void silentlyOmitsTheAwardIdLookupWhenTheIdDoesNotExist() {
        when(awardArchiveService.findSummary(999L))
                .thenThrow(new NoSuchElementException("Award not found: 999"));

        GlobalSearchResponse response = service.search("999");

        assertThat(response.failedModules()).isEmpty();
        assertThat(response.results()).isEmpty();
    }

    @Test
    void mapsBroadAwardResultsAsPrimaryCurrentWithARouteToTheAward() {
        when(awardArchiveService.search("campbell", 0, 25)).thenReturn(
                new AwardSearchResponse(
                        null,
                        new PageResponse<>(
                                List.of(new AwardSearchResultResponse(
                                        555L, "100200-00001", 3, "Campbell Research",
                                        "Active", "Dr. Campbell", "NSF", "Biology",
                                        BigDecimal.TEN, null, null
                                )),
                                0, 25, 1, 1, true, true
                        )
                )
        );

        GlobalSearchResponse response = service.search("campbell");

        GlobalSearchItemResponse item = response.results().get(0);
        assertThat(item.primaryCurrent()).isTrue();
        assertThat(item.route()).isEqualTo("/awards/555");
        // Title checked before sponsor/leadUnit/PI in the matched-field
        // heuristic - "Campbell Research" contains the query.
        assertThat(item.matchedField()).isEqualTo("Title");
    }

    // --- Partial failure --------------------------------------------------

    @Test
    void returnsIrbResultsAndNamesAwardAsFailedWhenAwardSearchThrows() {
        when(irbSearchRepository.search("campbell", 25)).thenReturn(List.of(
                irbRow(42L, 99L, "STU-1", "Campbell Study", "Title", 7)
        ));
        when(awardArchiveService.search("campbell", 0, 25))
                .thenThrow(new RuntimeException("Award search unavailable"));

        GlobalSearchResponse response = service.search("campbell");

        assertThat(response.failedModules()).containsExactly("AWARD");
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).module()).isEqualTo("IRB");
    }

    @Test
    void returnsAwardResultsAndNamesIrbAsFailedWhenIrbSearchThrows() {
        when(irbSearchRepository.search("campbell", 25))
                .thenThrow(new RuntimeException("IRB search unavailable"));
        when(awardArchiveService.search("campbell", 0, 25)).thenReturn(
                new AwardSearchResponse(
                        null,
                        new PageResponse<>(
                                List.of(new AwardSearchResultResponse(
                                        555L, "100200-00001", 3, "Campbell Research",
                                        "Active", "Dr. Campbell", "NSF", "Biology",
                                        BigDecimal.TEN, null, null
                                )),
                                0, 25, 1, 1, true, true
                        )
                )
        );

        GlobalSearchResponse response = service.search("campbell");

        assertThat(response.failedModules()).containsExactly("IRB");
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).module()).isEqualTo("AWARD");
    }

    // --- Ranking / dedup ---------------------------------------------------

    @Test
    void preservesEachDomainsOwnInternalOrderWithinTheSameRankTier() {
        when(irbSearchRepository.search("campbell", 25)).thenReturn(List.of(
                irbRow(1L, 1L, "STU-1", "First Title Match", "Title", 7),
                irbRow(2L, 2L, "STU-2", "Second Title Match", "Title", 7)
        ));

        GlobalSearchResponse response = service.search("campbell");

        List<String> titles = response.results().stream()
                .map(GlobalSearchItemResponse::title)
                .toList();
        assertThat(titles).containsExactly(
                "First Title Match", "Second Title Match"
        );
    }

    @Test
    void ranksAnExactAwardIdLookupAheadOfABroadTitleSubstringMatch() {
        when(awardArchiveService.findSummary(555L)).thenReturn(
                new AwardSummaryResponse(
                        555L, "100200-00001", 3, "Campbell Research", "Active",
                        "NSF", null, "Dr. Campbell", "Biology",
                        null, null, null, null, BigDecimal.TEN, BigDecimal.TEN,
                        null, null, null, null, null, null
                )
        );
        when(awardArchiveService.search("555", 0, 25)).thenReturn(
                new AwardSearchResponse(
                        null,
                        new PageResponse<>(
                                List.of(new AwardSearchResultResponse(
                                        777L, "555-00001", 1, "Unrelated 555 Title",
                                        "Active", "Dr. Other", "NSF", "Biology",
                                        BigDecimal.ONE, null, null
                                )),
                                0, 25, 1, 1, true, true
                        )
                )
        );

        GlobalSearchResponse response = service.search("555");

        assertThat(response.results().get(0).matchedField()).isEqualTo("Award ID");
    }

    @Test
    void dedupesTheSameAwardVersionAppearingFromBothTheIdLookupAndBroadResults() {
        when(awardArchiveService.findSummary(555L)).thenReturn(
                new AwardSummaryResponse(
                        555L, "100200-00001", 3, "Campbell Research", "Active",
                        "NSF", null, "Dr. Campbell", "Biology",
                        null, null, null, null, BigDecimal.TEN, BigDecimal.TEN,
                        null, null, null, null, null, null
                )
        );
        when(awardArchiveService.search("555", 0, 25)).thenReturn(
                new AwardSearchResponse(
                        null,
                        new PageResponse<>(
                                List.of(new AwardSearchResultResponse(
                                        555L, "100200-00001", 3, "Campbell Research",
                                        "Active", "Dr. Campbell", "NSF", "Biology",
                                        BigDecimal.TEN, null, null
                                )),
                                0, 25, 1, 1, true, true
                        )
                )
        );

        GlobalSearchResponse response = service.search("555");

        assertThat(response.results()).hasSize(1);
        assertThat(response.totalResults()).isEqualTo(1);
        assertThat(response.results().get(0).matchedField()).isEqualTo("Award ID");
    }
}
