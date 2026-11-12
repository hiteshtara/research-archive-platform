package edu.bu.archive.application.service;

import edu.bu.archive.adapter.in.web.dto.GlobalSearchItemResponse;
import edu.bu.archive.adapter.in.web.dto.GlobalSearchResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardPageResponse;
import edu.bu.archive.adapter.out.persistence.GlobalSearchRepository;
import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;
import edu.bu.archive.adapter.out.persistence.SemanticSearchRepository;
import edu.bu.archive.application.port.out.EmbeddingProvider;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.negotiation.NegotiationArchiveService;
import edu.bu.archive.application.subaward.SubawardArchiveService;
import edu.bu.archive.config.SemanticSearchProperties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * DEF-5 bounded fan-out.
 *
 * The bound is an OPTIMISATION ONLY, so these tests assert two things and
 * nothing else matters as much:
 *
 *   1. EQUIVALENCE - the response is identical at every bound. Same rows,
 *      same order, same count, same failedModules. Asserted by comparing
 *      bounded runs against the unbounded run rather than against
 *      hand-written expectations, so a future change that alters results
 *      at one bound fails here even if each run looks reasonable alone.
 *
 *   2. The bound is actually ENFORCED - peak observed concurrency never
 *      exceeds it. A "limit" that does not limit would make the whole
 *      experiment meaningless while appearing to work.
 *
 * The legs are stubbed to sleep briefly and record concurrency, so the
 * overlap is real rather than simulated.
 */
class GlobalSearchBoundedFanOutTest {

    /** Records the highest number of legs ever running at the same instant. */
    private static final class ConcurrencyProbe {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        void enter() {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
        }

        void exit() {
            inFlight.decrementAndGet();
        }

        int peak() {
            return peak.get();
        }
    }

    private GlobalSearchService serviceWith(int bound, ConcurrencyProbe probe) {
        return serviceWith(bound, probe, false);
    }

    private GlobalSearchService serviceWith(int bound, ConcurrencyProbe probe, boolean allFail) {
        GlobalSearchRepository irb = mock(GlobalSearchRepository.class);
        AwardArchiveService awards = mock(AwardArchiveService.class);
        NegotiationArchiveService negotiations = mock(NegotiationArchiveService.class);
        SubawardArchiveService subawards = mock(SubawardArchiveService.class);
        ProposalArchiveRepository proposals = mock(ProposalArchiveRepository.class);
        SemanticSearchRepository semantic = mock(SemanticSearchRepository.class);
        SemanticSearchProperties semanticProperties = new SemanticSearchProperties();

        // Every leg: observe concurrency, take a little time, return nothing.
        // Returning nothing keeps the response deterministic while still
        // exercising the real fan-out, join order and failedModules path.
        java.util.function.Supplier<Object> busy = () -> {
            probe.enter();
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                probe.exit();
            }
            return null;
        };

        when(irb.search(anyString(), anyInt()))
                .thenAnswer(i -> { busy.get();
                        if (allFail) { throw new IllegalStateException("irb down"); }
                        return List.of(); });
        // The Award leg THROWS on purpose: failedModules behaviour under a
        // bound is the property most likely to break silently.
        when(awards.search(anyString(), anyInt(), anyInt()))
                .thenAnswer(i -> { busy.get(); throw new IllegalStateException("award leg down"); });
        when(negotiations.findPage(anyString(), anyInt(), anyInt()))
                .thenAnswer(i -> { busy.get();
                        if (allFail) { throw new IllegalStateException("negotiation down"); }
                        return new PageResponse<>(List.of(), 0, 25, 0, 0, true, true); });
        when(subawards.findPage(anyString(), anyInt(), anyInt()))
                .thenAnswer(i -> { busy.get();
                        if (allFail) { throw new IllegalStateException("subaward down"); }
                        return new SubawardPageResponse(List.of(), 0, 25, 0, 0, true, true); });
        when(proposals.findFamilies(anyString(), any(), anyInt()))
                .thenAnswer(i -> { busy.get();
                        if (allFail) { throw new IllegalStateException("proposal down"); }
                        return List.of(); });

        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingProvider> embeddings = mock(ObjectProvider.class);
        when(embeddings.getIfAvailable()).thenReturn(null);

        return new GlobalSearchService(
                irb, awards, negotiations, subawards, proposals,
                semantic, semanticProperties, embeddings,
                new SearchTimingLog(false),
                null, null, null,
                edu.bu.archive.application.authorization.audit.ArchiveActivityRecorder
                        .disabled(java.time.Clock.systemUTC()),
                bound);
    }

    private static String shapeOf(GlobalSearchResponse response) {
        StringBuilder shape = new StringBuilder();
        shape.append("total=").append(response.totalResults())
             .append(" failed=").append(response.failedModules())
             .append(" rows=");
        for (GlobalSearchItemResponse item : response.results()) {
            shape.append('[').append(item.module()).append('|')
                 .append(item.identifier()).append('|')
                 .append(item.matchType()).append(']');
        }
        return shape.toString();
    }

    @Test
    void theResponseIsIdenticalAtEveryBound() {
        ConcurrencyProbe unboundedProbe = new ConcurrencyProbe();
        GlobalSearchResponse unbounded = serviceWith(0, unboundedProbe).search("NSF");
        String expected = shapeOf(unbounded);

        for (int bound : new int[] {3, 2, 1}) {
            ConcurrencyProbe probe = new ConcurrencyProbe();
            GlobalSearchResponse actual = serviceWith(bound, probe).search("NSF");

            assertThat(shapeOf(actual))
                    .as("response must be byte-identical at bound %d", bound)
                    .isEqualTo(expected);
            assertThat(actual.failedModules())
                    .as("failedModules must be preserved at bound %d", bound)
                    .isEqualTo(unbounded.failedModules());
            assertThat(actual.totalResults())
                    .as("count must be preserved at bound %d", bound)
                    .isEqualTo(unbounded.totalResults());
        }
    }

    @Test
    void aFailedLegStillAppearsInFailedModulesAtEveryBound() {
        // The Award leg throws in every configuration above. A bound must
        // not turn "failed" into "found nothing" - that would be a wrong
        // answer wearing the clothes of an empty result.
        for (int bound : new int[] {0, 3, 2, 1}) {
            GlobalSearchResponse response =
                    serviceWith(bound, new ConcurrencyProbe()).search("NSF");
            assertThat(response.failedModules())
                    .as("bound %d", bound)
                    .contains("AWARD");
        }
    }

    @Test
    void theBoundIsActuallyEnforced() {
        for (int bound : new int[] {3, 2, 1}) {
            ConcurrencyProbe probe = new ConcurrencyProbe();
            serviceWith(bound, probe).search("NSF");
            assertThat(probe.peak())
                    .as("peak concurrent legs must never exceed the bound of %d", bound)
                    .isLessThanOrEqualTo(bound);
            assertThat(probe.peak())
                    .as("at least one leg must have run at bound %d", bound)
                    .isGreaterThan(0);
        }
    }

    @Test
    void permitsAreReleasedEvenWhenEveryLegThrows() {
        // The release is in a finally, so a request in which every leg
        // fails must not strand permits. With a bound of 1, a stranded
        // permit would hang the next leg forever and the call would never
        // return - so completing at all is the assertion, and a timeout
        // here is the failure mode being guarded against.
        GlobalSearchService service = serviceWith(1, new ConcurrencyProbe(), true);

        GlobalSearchResponse response = service.search("NSF");

        assertThat(response).isNotNull();
        // Every module that threw is reported rather than collapsed into
        // an empty result.
        assertThat(response.failedModules()).isNotEmpty();
    }

    @Test
    void repeatedRequestsDoNotLeakPermitsAcrossCalls() {
        // Permits are per-request, so a leaked permit would show up as the
        // second or third call being unable to run any leg. Each call is
        // asserted to still reach its legs.
        for (int i = 0; i < 3; i++) {
            ConcurrencyProbe probe = new ConcurrencyProbe();
            serviceWith(1, probe).search("NSF");
            assertThat(probe.peak())
                    .as("call %d must still execute legs", i + 1)
                    .isGreaterThan(0);
        }
    }

    @Test
    void theUnboundedDefaultStillOverlapsSoTheProbeIsMeaningful() {
        // Guard against the enforcement test passing vacuously: if the legs
        // never overlapped even when unbounded, a peak <= 1 would "satisfy"
        // every bound without the semaphore doing anything.
        ConcurrencyProbe probe = new ConcurrencyProbe();
        serviceWith(0, probe).search("NSF");
        assertThat(probe.peak())
                .as("unbounded fan-out must genuinely overlap, or the bound tests prove nothing")
                .isGreaterThan(1);
    }
}
