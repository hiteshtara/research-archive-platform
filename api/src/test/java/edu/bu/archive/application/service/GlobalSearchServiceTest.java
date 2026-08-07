package edu.bu.archive.application.service;

import edu.bu.archive.adapter.in.web.dto.GlobalSearchItemResponse;
import edu.bu.archive.adapter.in.web.dto.GlobalSearchResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardPageResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;
import edu.bu.archive.adapter.out.persistence.GlobalSearchRepository;
import edu.bu.archive.adapter.out.persistence.IrbGlobalSearchRow;
import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;
import edu.bu.archive.adapter.out.persistence.SemanticSearchRepository;
import edu.bu.archive.adapter.out.persistence.SemanticSearchRow;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.negotiation.NegotiationArchiveService;
import edu.bu.archive.application.port.out.EmbeddingProvider;
import edu.bu.archive.application.subaward.SubawardArchiveService;
import edu.bu.archive.config.SemanticSearchProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/*
 * GlobalSearchService orchestrates Global Search as a fan-out over
 * IRB's own repository search and each other domain's own reused
 * search method - never a shared cross-domain SQL view. These tests
 * mock each domain dependency directly rather than their own
 * lower-level repositories, since this service's only job is
 * fan-out/mapping/ranking/merge, not domain query logic (already
 * covered by each repository's own tests).
 */
class GlobalSearchServiceTest {

    private GlobalSearchRepository irbSearchRepository;
    private AwardArchiveService awardArchiveService;
    private NegotiationArchiveService negotiationArchiveService;
    private SubawardArchiveService subawardArchiveService;
    private ProposalArchiveRepository proposalArchiveRepository;
    private SemanticSearchRepository semanticSearchRepository;
    private SemanticSearchProperties semanticSearchProperties;
    private EmbeddingProvider embeddingProvider;
    private ObjectProvider<EmbeddingProvider> embeddingProviderObjectProvider;
    private GlobalSearchService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        irbSearchRepository = mock(GlobalSearchRepository.class);
        awardArchiveService = mock(AwardArchiveService.class);
        negotiationArchiveService = mock(NegotiationArchiveService.class);
        subawardArchiveService = mock(SubawardArchiveService.class);
        proposalArchiveRepository = mock(ProposalArchiveRepository.class);
        semanticSearchRepository = mock(SemanticSearchRepository.class);
        // A real properties object, not a mock, so tests can flip
        // .setEnabled(true) directly - disabled (the real default) is
        // what most existing tests below implicitly rely on.
        semanticSearchProperties = new SemanticSearchProperties();
        embeddingProvider = mock(EmbeddingProvider.class);
        // NOT stubbed here (unlike the other mocks above) - Mockito's
        // own when(...) call would itself register as an invocation,
        // which would defeat the verify(..., never()).getIfAvailable()
        // assertions the semantic-disabled/identifier-skip tests below
        // rely on. Each test that actually needs semantic search to run
        // stubs this itself.
        embeddingProviderObjectProvider = mock(ObjectProvider.class);
        service = new GlobalSearchService(
                irbSearchRepository,
                awardArchiveService,
                negotiationArchiveService,
                subawardArchiveService,
                proposalArchiveRepository,
                semanticSearchRepository,
                semanticSearchProperties,
                embeddingProviderObjectProvider
        );

        // Default: empty results for every domain unless a test
        // overrides them - avoids every test needing to stub every
        // domain's call.
        when(awardArchiveService.search(anyString(), eq(0), anyInt()))
                .thenReturn(new AwardSearchResponse(
                        null,
                        new PageResponse<>(List.of(), 0, 25, 0, 0, true, true)
                ));
        when(negotiationArchiveService.findPage(anyString(), eq(0), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 25, 0, 0, true, true));
        when(subawardArchiveService.findPage(anyString(), eq(0), anyInt()))
                .thenReturn(new SubawardPageResponse(List.of(), 0, 25, 0, 0, true, true));
        when(proposalArchiveRepository.findFamilies(anyString(), anyInt()))
                .thenReturn(List.of());
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

    // --- Negotiation mapping ------------------------------------------------

    @Test
    void mapsANegotiationResultWithARouteAndDocumentNumberMatch() {
        when(negotiationArchiveService.findPage("763869", 0, 25)).thenReturn(
                new PageResponse<>(
                        List.of(new NegotiationSummaryResponse(
                                12345L, "763869", null, null, "Executed",
                                null, null, "License Agreement",
                                null, null, null, "202505-00002",
                                null, "Jane Negotiator", null, null, null
                        )),
                        0, 25, 1, 1, true, true
                )
        );
        // "763869" is numeric, so the same Award-ID direct-lookup path
        // covered by mapsAnAwardIdDirectLookupForANumericQuery also
        // fires here - it finds no Award with that ID and is silently
        // omitted, same as any other numeric-query miss.
        when(awardArchiveService.findSummary(763869L))
                .thenThrow(new NoSuchElementException("Award not found: 763869"));

        GlobalSearchResponse response = service.search("763869");

        GlobalSearchItemResponse item = response.results().stream()
                .filter(r -> "NEGOTIATION".equals(r.module()))
                .findFirst().orElseThrow();
        assertThat(item.recordId()).isEqualTo(12345L);
        assertThat(item.identifier()).isEqualTo("763869");
        assertThat(item.documentNumber()).isEqualTo("763869");
        assertThat(item.route()).isEqualTo("/negotiations/12345");
        assertThat(item.matchedField()).isEqualTo("Document Number");
    }

    // --- Subaward mapping ----------------------------------------------------

    @Test
    void mapsASubawardResultWithARouteAndSubawardCodeMatch() {
        when(subawardArchiveService.findPage("1012", 0, 25)).thenReturn(
                new SubawardPageResponse(
                        List.of(new SubawardSummaryResponse(
                                16279L, "1012", 3, "SW-DOC-1", "Diabetes Subaward",
                                null, "Executed", "302659", null,
                                null, null, "ACTIVE", null
                        )),
                        0, 25, 1, 1, true, true
                )
        );
        // "1012" is numeric, so the same Award-ID direct-lookup path
        // also fires here - see the equivalent comment in
        // mapsANegotiationResultWithARouteAndDocumentNumberMatch.
        when(awardArchiveService.findSummary(1012L))
                .thenThrow(new NoSuchElementException("Award not found: 1012"));

        GlobalSearchResponse response = service.search("1012");

        GlobalSearchItemResponse item = response.results().stream()
                .filter(r -> "SUBAWARD".equals(r.module()))
                .findFirst().orElseThrow();
        assertThat(item.recordId()).isEqualTo(16279L);
        assertThat(item.identifier()).isEqualTo("1012");
        assertThat(item.route()).isEqualTo("/subawards/16279");
        assertThat(item.matchedField()).isEqualTo("Subaward Code");
    }

    // --- Proposal mapping ----------------------------------------------------

    @Test
    void mapsAProposalResultWithARouteToTheCurrentProposalId() {
        when(proposalArchiveRepository.findFamilies("01157400", 25)).thenReturn(
                List.of(new ProposalFamilySummaryResponse(
                        "01157400", "Autism Research", "Funded", "NIH",
                        "Medicine", "Dr. Smacher", 4, 998877L
                ))
        );
        // "01157400" is numeric, so the same Award-ID direct-lookup
        // path also fires here (parsed as 1157400, leading zero
        // stripped) - see the equivalent comment in
        // mapsANegotiationResultWithARouteAndDocumentNumberMatch.
        when(awardArchiveService.findSummary(1157400L))
                .thenThrow(new NoSuchElementException("Award not found: 1157400"));

        GlobalSearchResponse response = service.search("01157400");

        GlobalSearchItemResponse item = response.results().stream()
                .filter(r -> "PROPOSAL".equals(r.module()))
                .findFirst().orElseThrow();
        assertThat(item.recordId()).isEqualTo(998877L);
        assertThat(item.identifier()).isEqualTo("01157400");
        assertThat(item.route()).isEqualTo("/proposals/dashboard/998877");
        assertThat(item.matchedField()).isEqualTo("Proposal Number");
    }

    @Test
    void aProposalFamilyWithNoCurrentlyArchivedVersionHasANullRoute() {
        when(proposalArchiveRepository.findFamilies("01157400", 25)).thenReturn(
                List.of(new ProposalFamilySummaryResponse(
                        "01157400", "Autism Research", "Funded", "NIH",
                        "Medicine", "Dr. Smacher", 4, null
                ))
        );
        when(awardArchiveService.findSummary(1157400L))
                .thenThrow(new NoSuchElementException("Award not found: 1157400"));

        GlobalSearchResponse response = service.search("01157400");

        GlobalSearchItemResponse item = response.results().stream()
                .filter(r -> "PROPOSAL".equals(r.module()))
                .findFirst().orElseThrow();
        assertThat(item.route()).isNull();
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

    @Test
    void namesNegotiationAsFailedWithoutFailingTheWholeRequest() {
        when(negotiationArchiveService.findPage("campbell", 0, 25))
                .thenThrow(new RuntimeException("Negotiation search unavailable"));

        GlobalSearchResponse response = service.search("campbell");

        assertThat(response.failedModules()).containsExactly("NEGOTIATION");
        assertThat(response.results()).isEmpty();
    }

    @Test
    void namesSubawardAsFailedWithoutFailingTheWholeRequest() {
        when(subawardArchiveService.findPage("campbell", 0, 25))
                .thenThrow(new RuntimeException("Subaward search unavailable"));

        GlobalSearchResponse response = service.search("campbell");

        assertThat(response.failedModules()).containsExactly("SUBAWARD");
        assertThat(response.results()).isEmpty();
    }

    @Test
    void namesProposalAsFailedWithoutFailingTheWholeRequest() {
        when(proposalArchiveRepository.findFamilies("campbell", 25))
                .thenThrow(new RuntimeException("Proposal search unavailable"));

        GlobalSearchResponse response = service.search("campbell");

        assertThat(response.failedModules()).containsExactly("PROPOSAL");
        assertThat(response.results()).isEmpty();
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

    // --- Semantic search integration ---------------------------------------

    private SemanticSearchRow semanticRow(
            String module,
            long recordId,
            long canonicalFamilyId,
            String businessNumber
    ) {
        return new SemanticSearchRow(
                module, recordId, canonicalFamilyId, businessNumber, 0.42
        );
    }

    @Test
    void anExactAwardNumberMatchStillRanksFirstWhenSemanticIsEnabledAndReturnsResults() {
        semanticSearchProperties.setEnabled(true);
        when(embeddingProviderObjectProvider.getIfAvailable()).thenReturn(embeddingProvider);
        when(embeddingProvider.embed(anyString())).thenReturn(new float[]{0.1f});
        when(semanticSearchRepository.findNearest(any(float[].class), eq(5)))
                .thenReturn(List.of(
                        semanticRow("PROPOSAL", 900L, 900L, "unrelated-01")
                ));
        when(awardArchiveService.search("campbell", 0, 25)).thenReturn(
                new AwardSearchResponse(
                        null,
                        new PageResponse<>(
                                List.of(new AwardSearchResultResponse(
                                        555L, "campbell-00001", 3, "Some Title",
                                        "Active", "Dr. Other", "NSF", "Biology",
                                        BigDecimal.TEN, null, null
                                )),
                                0, 25, 1, 1, true, true
                        )
                )
        );

        GlobalSearchResponse response = service.search("campbell");

        assertThat(response.results().get(0).module()).isEqualTo("AWARD");
        assertThat(response.results().get(0).matchedField()).isEqualTo("Award Number");
        assertThat(response.results().get(response.results().size() - 1).matchType())
                .isEqualTo("RELATED");
    }

    @Test
    void anExactProposalNumberMatchStillRanksFirstWhenSemanticIsEnabledAndReturnsResults() {
        semanticSearchProperties.setEnabled(true);
        when(embeddingProviderObjectProvider.getIfAvailable()).thenReturn(embeddingProvider);
        when(embeddingProvider.embed(anyString())).thenReturn(new float[]{0.1f});
        when(semanticSearchRepository.findNearest(any(float[].class), eq(5)))
                .thenReturn(List.of(
                        semanticRow("SUBAWARD", 900L, 900L, "unrelated-01")
                ));
        when(proposalArchiveRepository.findFamilies("smacher", 25)).thenReturn(
                List.of(new ProposalFamilySummaryResponse(
                        "smacher-01", "Some Title", "Funded", "NIH",
                        "Medicine", "Dr. Other", 4, 998877L
                ))
        );

        GlobalSearchResponse response = service.search("smacher");

        assertThat(response.results().get(0).module()).isEqualTo("PROPOSAL");
        assertThat(response.results().get(0).matchedField()).isEqualTo("Proposal Number");
        assertThat(response.results().get(response.results().size() - 1).matchType())
                .isEqualTo("RELATED");
    }

    @Test
    void aNaturalLanguageQueryWithNoStructuredMatchesReturnsLabeledSemanticResults() {
        semanticSearchProperties.setEnabled(true);
        when(embeddingProviderObjectProvider.getIfAvailable()).thenReturn(embeddingProvider);
        when(embeddingProvider.embed(anyString())).thenReturn(new float[]{0.1f});
        when(semanticSearchRepository.findNearest(any(float[].class), eq(5)))
                .thenReturn(List.of(
                        semanticRow("AWARD", 111L, 111L, "111-00001"),
                        semanticRow("PROPOSAL", 222L, 222L, "222-01")
                ));

        GlobalSearchResponse response =
                service.search("diabetes research involving children");

        assertThat(response.results()).hasSize(2);
        assertThat(response.results())
                .allSatisfy(item -> assertThat(item.matchType()).isEqualTo("RELATED"));
    }

    @Test
    void semanticResultsAreCappedAtFiveEvenWhenMoreCandidatesAreReturned() {
        semanticSearchProperties.setEnabled(true);
        when(embeddingProviderObjectProvider.getIfAvailable()).thenReturn(embeddingProvider);
        when(embeddingProvider.embed(anyString())).thenReturn(new float[]{0.1f});
        List<SemanticSearchRow> eightCandidates = List.of(
                semanticRow("AWARD", 1L, 1L, "1"),
                semanticRow("AWARD", 2L, 2L, "2"),
                semanticRow("AWARD", 3L, 3L, "3"),
                semanticRow("AWARD", 4L, 4L, "4"),
                semanticRow("AWARD", 5L, 5L, "5"),
                semanticRow("AWARD", 6L, 6L, "6"),
                semanticRow("AWARD", 7L, 7L, "7"),
                semanticRow("AWARD", 8L, 8L, "8")
        );
        when(semanticSearchRepository.findNearest(any(float[].class), eq(5)))
                .thenReturn(eightCandidates);

        GlobalSearchResponse response = service.search("large federally funded projects");

        assertThat(response.results()).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void aStructuredAndSemanticMatchForTheSameCanonicalRecordCollapseToOne() {
        semanticSearchProperties.setEnabled(true);
        when(embeddingProviderObjectProvider.getIfAvailable()).thenReturn(embeddingProvider);
        when(embeddingProvider.embed(anyString())).thenReturn(new float[]{0.1f});
        when(semanticSearchRepository.findNearest(any(float[].class), eq(5)))
                .thenReturn(List.of(
                        semanticRow("AWARD", 555L, 555L, "100200-00001")
                ));
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

        assertThat(response.results()).hasSize(1);
        GlobalSearchItemResponse survivor = response.results().get(0);
        assertThat(survivor.matchedField()).isEqualTo("Title");
        assertThat(survivor.matchType()).isNull();
    }

    @Test
    void aSemanticEmbeddingFailureDoesNotBreakTheRestOfGlobalSearch() {
        semanticSearchProperties.setEnabled(true);
        when(embeddingProviderObjectProvider.getIfAvailable()).thenReturn(embeddingProvider);
        when(embeddingProvider.embed(anyString()))
                .thenThrow(new RuntimeException("Bedrock unavailable"));
        when(irbSearchRepository.search("campbell", 25)).thenReturn(List.of(
                irbRow(42L, 99L, "STU-1", "Campbell Study", "Title", 7)
        ));

        GlobalSearchResponse response = service.search("campbell");

        assertThat(response.failedModules()).contains("SEMANTIC");
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).module()).isEqualTo("IRB");
    }

    @Test
    void semanticDisabledNeverInvokesTheEmbeddingProvider() {
        // semanticSearchProperties.isEnabled() is false by default -
        // this is the exact behavior of every test above this section,
        // none of which touch semantic search at all.
        service.search("campbell");

        verify(embeddingProviderObjectProvider, never()).getIfAvailable();
        verifyNoInteractions(semanticSearchRepository);
    }

    @Test
    void anObviousExactIdentifierQueryNeverInvokesSemanticSearchEvenWhenEnabled() {
        semanticSearchProperties.setEnabled(true);

        // "202505-00002" is Award-number shaped (not pure numeric), so
        // the numeric Award-ID direct-lookup path never fires either -
        // only the semantic short-circuit is under test here.
        service.search("202505-00002");

        verify(embeddingProviderObjectProvider, never()).getIfAvailable();
        verifyNoInteractions(semanticSearchRepository);
    }
}
