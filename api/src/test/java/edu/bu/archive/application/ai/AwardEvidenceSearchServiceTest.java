package edu.bu.archive.application.ai;

import edu.bu.archive.adapter.in.web.dto.ai.AwardEvidenceResultResponse;
import edu.bu.archive.adapter.in.web.dto.ai.AwardEvidenceSearchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
import edu.bu.archive.adapter.out.persistence.AwardEvidenceRetrievalRepository;
import edu.bu.archive.adapter.out.persistence.AwardEvidenceRow;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.port.out.EmbeddingProvider;
import edu.bu.archive.adapter.out.search.EmbeddingProviderException;
import edu.bu.archive.config.SemanticSearchProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * Fake/mocked EmbeddingProvider only - no real Bedrock call anywhere in
 * this file. Real fixtures reused from etl/build_evidence_embedding.py's
 * own pinned test data: Award 204713-00001 (award_funding_proposal_id
 * 1768708, proposal 01128961 "CARB-X") for RELATED_PROPOSAL.
 */
class AwardEvidenceSearchServiceTest {

    private AwardArchiveService awardArchiveService;
    private AwardEvidenceRetrievalRepository repository;
    private EmbeddingProvider embeddingProvider;
    private SensitiveFieldRedactor redactor;
    private SemanticSearchProperties properties;
    private AwardEvidenceSearchService service;

    @BeforeEach
    void setUp() {
        awardArchiveService = mock(AwardArchiveService.class);
        repository = mock(AwardEvidenceRetrievalRepository.class);
        embeddingProvider = mock(EmbeddingProvider.class);
        redactor = new SensitiveFieldRedactor();
        properties = new SemanticSearchProperties();
        service = new AwardEvidenceSearchService(
                awardArchiveService, repository, embeddingProvider,
                redactor, properties
        );

        when(awardArchiveService.findFamily("204713-00001"))
                .thenReturn(family("204713-00001"));
        when(embeddingProvider.embed(anyString()))
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f});
    }

    private AwardFamilyResponse family(String awardNumber) {
        return new AwardFamilyResponse(
                awardNumber,
                new AwardRowResponse(
                        1768700L, awardNumber, 1, "CARB-X", "Active",
                        "Active", "HHS", null, "Lead Unit", null, null,
                        null, null, true, true
                ),
                List.of()
        );
    }

    private AwardEvidenceRow relatedProposalRow(long sourcePrimaryKey, double distance) {
        return new AwardEvidenceRow(
                "RELATED_PROPOSAL", "204713-00001",
                "Award 204713-00001 version 1 is funded by Proposal 01128961: CARB-X.",
                "archive.award_funding_proposal", sourcePrimaryKey, distance
        );
    }

    // --- 1. Missing Award ---

    @Test
    void missingAwardWrapsNoSuchElementExceptionWithACorrelationId() {
        when(awardArchiveService.findFamily("NO-SUCH-AWARD"))
                .thenThrow(new NoSuchElementException("Award not found: NO-SUCH-AWARD"));

        AwardEvidenceSearchException thrown = (AwardEvidenceSearchException)
                assertThatThrownBy(() ->
                        service.search("NO-SUCH-AWARD", "query", List.of(), null)
                )
                        .isInstanceOf(AwardEvidenceSearchException.class)
                        .actual();

        assertThat(thrown.getCause()).isInstanceOf(NoSuchElementException.class);
        assertThat(thrown.correlationId()).isNotNull();
    }

    // --- 2. Invalid evidence type ---

    @Test
    void invalidEvidenceTypeThrowsIllegalArgumentException() {
        AwardEvidenceSearchException thrown = (AwardEvidenceSearchException)
                assertThatThrownBy(() ->
                        service.search(
                                "204713-00001", "query",
                                List.of("NOT_A_REAL_TYPE"), null
                        )
                )
                        .isInstanceOf(AwardEvidenceSearchException.class)
                        .actual();

        assertThat(thrown.getCause())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOT_A_REAL_TYPE");
        verify(embeddingProvider, never()).embed(anyString());
    }

    // --- 3. AWARD_SUMMARY exclusion ---

    @Test
    void awardSummaryIsNotAnApprovedEvidenceType() {
        assertThatThrownBy(() ->
                service.search("204713-00001", "query", List.of("AWARD_SUMMARY"), null)
        )
                .isInstanceOf(AwardEvidenceSearchException.class)
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AWARD_SUMMARY");
    }

    // --- 4. AWARD_ATTACHMENT exclusion / no attachment-content retrieval ---

    @Test
    void awardAttachmentIsNotAnApprovedEvidenceType() {
        assertThatThrownBy(() ->
                service.search("204713-00001", "query", List.of("AWARD_ATTACHMENT"), null)
        )
                .isInstanceOf(AwardEvidenceSearchException.class)
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AWARD_ATTACHMENT");
    }

    @Test
    void defaultDocumentTypesNeverIncludeAwardSummaryOrAwardAttachment() {
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of());

        service.search("204713-00001", "query", List.of(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).findNearestEvidence(
                anyString(), captor.capture(), any(), anyDouble(), anyInt()
        );
        assertThat(captor.getValue())
                .doesNotContain("AWARD_SUMMARY", "AWARD_ATTACHMENT")
                .containsExactlyInAnyOrder(
                        "AWARD_VERSION", "AWARD_PERSON", "AWARD_AMOUNT",
                        "AWARD_TERM", "AWARD_COMMENT", "RELATED_PROPOSAL",
                        "RELATED_NEGOTIATION", "RELATED_SUBAWARD"
                );
    }

    // --- 5. Excessive topK is clamped, never rejected ---

    @Test
    void excessiveTopKIsClampedToTheHardMaximum() {
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of());

        service.search("204713-00001", "query", List.of(), 999999);

        verify(repository).findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(),
                eq(AwardEvidenceSearchService.MAX_TOP_K)
        );
    }

    @Test
    void nonPositiveTopKIsClampedToAtLeastOne() {
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of());

        service.search("204713-00001", "query", List.of(), -5);

        verify(repository).findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), eq(1)
        );
    }

    @Test
    void missingTopKUsesTheDefault() {
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of());

        service.search("204713-00001", "query", List.of(), null);

        verify(repository).findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(),
                eq(AwardEvidenceSearchService.DEFAULT_TOP_K)
        );
    }

    // --- 6. Exact Award isolation (the awardNumber passed to the repository
    //        is exactly the requested/normalized one, never widened) ---

    @Test
    void searchScopesToExactlyTheRequestedAwardNumber() {
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of());

        service.search("204713-00001", "query", List.of(), null);

        verify(repository).findNearestEvidence(
                eq("204713-00001"), anyList(), any(), anyDouble(), anyInt()
        );
    }

    // --- 7. Evidence-type filtering ---

    @Test
    void requestedDocumentTypesAreForwardedExactlyToTheRepository() {
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of());

        service.search(
                "204713-00001", "query", List.of("RELATED_PROPOSAL"), null
        );

        verify(repository).findNearestEvidence(
                anyString(), eq(List.of("RELATED_PROPOSAL")), any(),
                anyDouble(), anyInt()
        );
    }

    // --- 8. Stable ordering - the service never re-sorts the repository's
    //        own result order ---

    @Test
    void resultsPreserveTheRepositorysOwnOrdering() {
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of(
                relatedProposalRow(300L, 0.05),
                relatedProposalRow(100L, 0.20),
                relatedProposalRow(200L, 0.30)
        ));

        AwardEvidenceSearchResponse response =
                service.search("204713-00001", "query", List.of(), null);

        assertThat(response.results())
                .extracting(AwardEvidenceResultResponse::sourcePrimaryKey)
                .containsExactly("300", "100", "200");
    }

    // --- 9. Threshold filtering (enforced by the repository query itself;
    //        proves the service passes the configured threshold through
    //        unmodified) ---

    @Test
    void configuredEvidenceMaxDistanceIsPassedThroughToTheRepository() {
        properties.setEvidenceMaxDistance(0.42);
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of());

        service.search("204713-00001", "query", List.of(), null);

        verify(repository).findNearestEvidence(
                anyString(), anyList(), any(), eq(0.42), anyInt()
        );
    }

    // --- 10. Duplicate suppression - the service does not introduce
    //         duplicates when mapping repository rows to results ---

    @Test
    void noDuplicateResultForTheSameEvidenceRow() {
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of(relatedProposalRow(1768708L, 0.1)));

        AwardEvidenceSearchResponse response =
                service.search("204713-00001", "query", List.of(), null);

        assertThat(response.results()).hasSize(1);
    }

    // --- 11. No evidence indexed ---

    @Test
    void emptyRepositoryResultProducesInsufficientEvidenceWithoutAnError() {
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of());

        AwardEvidenceSearchResponse response =
                service.search("204713-00001", "query", List.of(), null);

        assertThat(response.results()).isEmpty();
        assertThat(response.insufficientEvidence()).isTrue();
        assertThat(response.correlationId()).isNotNull();
    }

    // --- 12/13. Provider unavailable / embedding failure ---

    @Test
    void embeddingProviderFailureIsWrappedWithACorrelationId() {
        when(embeddingProvider.embed(anyString()))
                .thenThrow(new EmbeddingProviderException(
                        "Failed to embed query text via Bedrock",
                        new RuntimeException("boom")
                ));

        AwardEvidenceSearchException thrown = (AwardEvidenceSearchException)
                assertThatThrownBy(() ->
                        service.search("204713-00001", "query", List.of(), null)
                )
                        .isInstanceOf(AwardEvidenceSearchException.class)
                        .actual();

        assertThat(thrown.getCause()).isInstanceOf(EmbeddingProviderException.class);
        assertThat(thrown.correlationId()).isNotNull();
        verify(repository, never()).findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        );
    }

    // --- 14. Redaction ---

    @Test
    void excerptIsRedactedBeforeItLeavesTheService() {
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of(new AwardEvidenceRow(
                "AWARD_COMMENT", "204713-00001",
                "Contact person@example.edu about this, password=hunter2",
                "archive.award_comment", 42L, 0.1
        )));

        AwardEvidenceSearchResponse response =
                service.search("204713-00001", "query", List.of(), null);

        String excerpt = response.results().get(0).excerpt();
        assertThat(excerpt)
                .doesNotContain("person@example.edu")
                .doesNotContain("hunter2")
                .contains("[REDACTED]");
    }

    // --- 15. Excerpt-length limit ---

    @Test
    void excerptIsTruncatedToTheMaximumLength() {
        String longText = "A".repeat(1000);
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of(new AwardEvidenceRow(
                "AWARD_COMMENT", "204713-00001", longText,
                "archive.award_comment", 42L, 0.1
        )));

        AwardEvidenceSearchResponse response =
                service.search("204713-00001", "query", List.of(), null);

        assertThat(response.results().get(0).excerpt().length())
                .isLessThanOrEqualTo(
                        AwardEvidenceSearchService.MAX_EXCERPT_LENGTH + 1
                );
    }

    // --- 16. Citation metadata ---

    @Test
    void resultCarriesRealSourceTableAndSourcePrimaryKey() {
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of(relatedProposalRow(1768708L, 0.09)));

        AwardEvidenceSearchResponse response =
                service.search("204713-00001",
                        "Which proposal is connected to this Award?",
                        List.of("RELATED_PROPOSAL"), 8);

        AwardEvidenceResultResponse result = response.results().get(0);
        assertThat(result.documentType()).isEqualTo("RELATED_PROPOSAL");
        assertThat(result.awardNumber()).isEqualTo("204713-00001");
        assertThat(result.sourceTable()).isEqualTo("archive.award_funding_proposal");
        assertThat(result.sourcePrimaryKey()).isEqualTo("1768708");
        assertThat(result.excerpt()).contains("01128961", "CARB-X");
        assertThat(result.targetSection()).isEqualTo("fundingProposals");
    }

    // --- 17. Correlation ID ---

    @Test
    void successResponseCarriesACorrelationId() {
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of());

        AwardEvidenceSearchResponse response =
                service.search("204713-00001", "query", List.of(), null);

        assertThat(response.correlationId()).isNotBlank();
    }

    // --- 18. Score is a bounded similarity, not a raw distance leaking negative/huge values ---

    @Test
    void scoreIsDerivedFromDistanceAndNeverNegative() {
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of(relatedProposalRow(1768708L, 1.9)));

        AwardEvidenceSearchResponse response =
                service.search("204713-00001", "query", List.of(), null);

        assertThat(response.results().get(0).score()).isGreaterThanOrEqualTo(0.0);
    }

    // --- 19. Empty/blank query is a validation concern enforced by the
    //         request DTO's @NotBlank - service itself does not need to
    //         re-validate blankness, but never NPEs on it either ---

    @Test
    void queryIsEchoedBackVerbatimInTheResponse() {
        when(repository.findNearestEvidence(
                anyString(), anyList(), any(), anyDouble(), anyInt()
        )).thenReturn(List.of());

        AwardEvidenceSearchResponse response = service.search(
                "204713-00001",
                "Which proposal is connected to this Award?",
                List.of(), null
        );

        assertThat(response.query())
                .isEqualTo("Which proposal is connected to this Award?");
    }
}
