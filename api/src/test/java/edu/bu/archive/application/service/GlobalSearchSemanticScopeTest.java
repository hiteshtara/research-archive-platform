package edu.bu.archive.application.service;

import edu.bu.archive.adapter.in.web.dto.GlobalSearchItemResponse;
import edu.bu.archive.adapter.in.web.dto.GlobalSearchResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardPageResponse;
import edu.bu.archive.adapter.out.persistence.GlobalSearchRepository;
import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;
import edu.bu.archive.adapter.out.persistence.SemanticSearchRepository;
import edu.bu.archive.adapter.out.persistence.SemanticSearchRow;
import edu.bu.archive.adapter.out.persistence.SubawardSemanticSummaryRow;
import edu.bu.archive.application.authorization.RecordVisibility;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.negotiation.NegotiationArchiveService;
import edu.bu.archive.application.port.out.EmbeddingProvider;
import edu.bu.archive.application.subaward.SubawardArchiveService;
import edu.bu.archive.config.SemanticSearchProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a SCOPED caller gets back from the semantic leg of Global Search.
 *
 * <p>A semantic row is read from the embedding index, not from the business
 * tables, so it arrives already carrying a module, a business number and
 * enough to build a link. The enrichment lookups are the only thing that
 * applies record authorization to it. So an absent summary for a scoped
 * caller means "not authorised", and the row has to be DROPPED: returning it
 * unenriched still publishes the identifier and the route, which is exactly
 * the disclosure the filter exists to prevent.
 *
 * <p>The gate is {@code !unrestricted()}. With enforcement off - today's
 * deployed configuration - the lookups are unfiltered, so an absent summary
 * means a real data gap and the row must still render, bare identifier and
 * all. These tests pin both sides of that gate for SUBAWARD, the module the
 * approved funding-inheritance rule just brought under it.
 */
class GlobalSearchSemanticScopeTest {

    private SemanticSearchRepository semanticSearchRepository;
    private SemanticSearchProperties semanticSearchProperties;
    private EmbeddingProvider embeddingProvider;
    private ObjectProvider<EmbeddingProvider> embeddingProviderObjectProvider;
    private SubawardArchiveService subawardArchiveService;
    private AwardArchiveService awardArchiveService;
    private NegotiationArchiveService negotiationArchiveService;
    private ProposalArchiveRepository proposalArchiveRepository;
    private GlobalSearchRepository irbSearchRepository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        irbSearchRepository = mock(GlobalSearchRepository.class);
        awardArchiveService = mock(AwardArchiveService.class);
        negotiationArchiveService = mock(NegotiationArchiveService.class);
        subawardArchiveService = mock(SubawardArchiveService.class);
        proposalArchiveRepository = mock(ProposalArchiveRepository.class);
        semanticSearchRepository = mock(SemanticSearchRepository.class);
        semanticSearchProperties = new SemanticSearchProperties();
        embeddingProvider = mock(EmbeddingProvider.class);
        embeddingProviderObjectProvider = mock(ObjectProvider.class);

        when(irbSearchRepository.search(anyString(), anyInt())).thenReturn(List.of());
        when(awardArchiveService.search(anyString(), eq(0), anyInt()))
                .thenReturn(new AwardSearchResponse(
                        null, new PageResponse<>(List.of(), 0, 25, 0, 0, true, true)));
        when(negotiationArchiveService.findPage(anyString(), eq(0), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 25, 0, 0, true, true));
        when(subawardArchiveService.findPage(anyString(), eq(0), anyInt()))
                .thenReturn(new SubawardPageResponse(List.of(), 0, 25, 0, 0, true, true));
        when(proposalArchiveRepository.findFamilies(anyString(), any(), anyInt()))
                .thenReturn(List.of());

        semanticSearchProperties.setEnabled(true);
        when(embeddingProviderObjectProvider.getIfAvailable()).thenReturn(embeddingProvider);
        when(embeddingProvider.embed(anyString())).thenReturn(new float[]{0.1f});
        when(semanticSearchRepository.findNearest(any(float[].class), eq(50)))
                .thenReturn(List.of(new SemanticSearchRow(
                        "SUBAWARD", 9001L, 9001L, "SUB-SECRET", 0.42)));
    }

    @Test
    void aScopedCallerNeverSeesAnUnauthorisedSubawardsCodeOrLink() {
        // The enrichment lookup is itself scoped, so an unauthorised code
        // comes back with no summary.
        when(subawardArchiveService.findActiveSummariesForCodes(anyList()))
                .thenReturn(List.of());

        GlobalSearchResponse response = service(scoped()).search("gene therapy consortium");

        assertThat(response.results()).isEmpty();
        assertThat(response.toString()).doesNotContain("SUB-SECRET");
    }

    @Test
    void aScopedCallerStillSeesAnAuthorisedSubaward() {
        when(subawardArchiveService.findActiveSummariesForCodes(anyList()))
                .thenReturn(List.of(new SubawardSemanticSummaryRow(
                        "SUB-SECRET", "A visible subaward", "Active", "NIH")));

        GlobalSearchResponse response = service(scoped()).search("gene therapy consortium");

        assertThat(response.results()).hasSize(1);
        GlobalSearchItemResponse item = response.results().getFirst();
        assertThat(item.module()).isEqualTo("SUBAWARD");
        assertThat(item.title()).isEqualTo("A visible subaward");
    }

    @Test
    void withEnforcementOffAnUnenrichedSubawardRowStillRendersExactlyAsBefore() {
        /*
         * The gate that matters. Dropping here would change the behaviour of
         * the deployed, enforcement-disabled system: a Subaward whose ACTIVE
         * version is simply missing has always rendered with the code as its
         * title, and must keep doing so.
         */
        when(subawardArchiveService.findActiveSummariesForCodes(anyList()))
                .thenReturn(List.of());

        GlobalSearchResponse response = service(null).search("gene therapy consortium");

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().getFirst().identifier()).isEqualTo("SUB-SECRET");
    }

    @Test
    void theSemanticLegRunsWithTheRequestsAccessDecisionReinstated() {
        /*
         * Regression. Every module leg goes through supplyLeg, which wraps
         * it in visibility.propagate(); the semantic leg has its own timing
         * instrumentation and did not. Off the request thread with nothing
         * propagated, outcome() fails closed to Denied(EVALUATION_FAILED),
         * so the enrichment's requireProvisioned() threw and the whole
         * semantic leg was reported as a failed module for every scoped
         * caller once enforcement was on. No leak - but silently empty.
         *
         * Modelled the way it actually breaks: the enrichment lookup
         * refuses to answer unless the decision is active on its thread,
         * exactly as RecordAuthorizationService does.
         */
        // Per THREAD, like the real PROPAGATED ThreadLocal - the five
        // module legs run concurrently and would flap a shared flag.
        ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);
        java.util.concurrent.atomic.AtomicBoolean ranUnderPropagation =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        when(subawardArchiveService.findActiveSummariesForCodes(anyList()))
                .thenAnswer(invocation -> {
                    if (!active.get()) {
                        throw new IllegalStateException(
                                "no access decision on this thread - would fail closed");
                    }
                    ranUnderPropagation.set(true);
                    return List.of(new SubawardSemanticSummaryRow(
                            "SUB-SECRET", "A visible subaward", "Active", "NIH"));
                });

        GlobalSearchResponse response =
                service(propagatingScope(active)).search("gene therapy consortium");

        assertThat(ranUnderPropagation).isTrue();
        assertThat(response.failedModules()).doesNotContain("SEMANTIC");
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().getFirst().title()).isEqualTo("A visible subaward");
    }

    // --- helpers ----------------------------------------------------------

    private GlobalSearchService service(RecordVisibility visibility) {
        return new GlobalSearchService(
                irbSearchRepository,
                awardArchiveService,
                negotiationArchiveService,
                subawardArchiveService,
                proposalArchiveRepository,
                semanticSearchRepository,
                semanticSearchProperties,
                embeddingProviderObjectProvider,
                new SearchTimingLog(false),
                provider(visibility),
                null);
    }

    /** A caller who is neither Central nor unenforced. */
    private static RecordVisibility scoped() {
        return new RecordVisibility() {
            @Override public boolean unrestricted() {
                return false;
            }

            @Override public boolean canSeeAward(long awardId) {
                return false;
            }

            @Override public boolean canSeeAwardNumber(String awardNumber) {
                return false;
            }

            @Override public boolean canSeeProposalNumber(String proposalNumber) {
                return false;
            }

            @Override public boolean canSeeProposal(long proposalId) {
                return false;
            }

            @Override public boolean canSeeEveryProposalVersion(String proposalNumber) {
                return false;
            }

            @Override public boolean canSeeEveryAwardVersion(String awardNumber) {
                return false;
            }

            @Override public <T> java.util.function.Supplier<T> propagate(
                    java.util.function.Supplier<T> work) {
                return work;
            }
        };
    }

    /** A scoped caller whose decision is only "active" inside propagate(). */
    private static RecordVisibility propagatingScope(ThreadLocal<Boolean> active) {
        RecordVisibility base = scoped();
        return new RecordVisibility() {
            @Override public boolean unrestricted() {
                return base.unrestricted();
            }

            @Override public boolean canSeeAward(long awardId) {
                return base.canSeeAward(awardId);
            }

            @Override public boolean canSeeAwardNumber(String awardNumber) {
                return base.canSeeAwardNumber(awardNumber);
            }

            @Override public boolean canSeeProposalNumber(String proposalNumber) {
                return base.canSeeProposalNumber(proposalNumber);
            }

            @Override public boolean canSeeProposal(long proposalId) {
                return base.canSeeProposal(proposalId);
            }

            @Override public boolean canSeeEveryProposalVersion(String proposalNumber) {
                return base.canSeeEveryProposalVersion(proposalNumber);
            }

            @Override public boolean canSeeEveryAwardVersion(String awardNumber) {
                return base.canSeeEveryAwardVersion(awardNumber);
            }

            @Override public <T> java.util.function.Supplier<T> propagate(
                    java.util.function.Supplier<T> work) {
                return () -> {
                    Boolean previous = active.get();
                    active.set(true);
                    try {
                        return work.get();
                    } finally {
                        active.set(previous);
                    }
                };
            }
        };
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject() {
                return value;
            }

            @Override public T getObject(Object... args) {
                return value;
            }

            @Override public T getIfAvailable() {
                return value;
            }

            @Override public T getIfUnique() {
                return value;
            }

            @Override public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }
        };
    }
}
