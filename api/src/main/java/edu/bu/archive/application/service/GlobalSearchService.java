package edu.bu.archive.application.service;

import edu.bu.archive.adapter.in.web.dto.GlobalSearchItemResponse;
import edu.bu.archive.application.search.LexicalMatch;
import edu.bu.archive.application.search.MatchExplanation;
import edu.bu.archive.adapter.in.web.dto.GlobalSearchModuleTotal;
import edu.bu.archive.adapter.in.web.dto.GlobalSearchResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;
import edu.bu.archive.adapter.out.persistence.AwardSemanticSummaryRow;
import edu.bu.archive.adapter.out.persistence.GlobalSearchRepository;
import edu.bu.archive.adapter.out.persistence.IrbGlobalSearchRow;
import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;
import edu.bu.archive.adapter.out.persistence.ProposalSemanticSummaryRow;
import edu.bu.archive.adapter.out.persistence.SubawardSemanticSummaryRow;
import edu.bu.archive.adapter.out.persistence.NegotiationSemanticSummaryRow;
import edu.bu.archive.adapter.out.persistence.SemanticSearchRepository;
import edu.bu.archive.adapter.out.persistence.SemanticSearchRow;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.negotiation.NegotiationArchiveService;
import edu.bu.archive.application.port.out.EmbeddingProvider;
import edu.bu.archive.application.subaward.SubawardArchiveService;
import edu.bu.archive.config.SemanticSearchProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/*
 * Orchestrates Global Search as a fan-out over each domain's OWN search
 * implementation, rather than a single cross-domain SQL view - see
 * GlobalSearchRepository (IRB's own, unchanged ranking/ILIKE logic
 * against archive.v_global_search), AwardArchiveService.search,
 * NegotiationArchiveService.findPage, SubawardArchiveService.findPage,
 * and ProposalArchiveRepository.findFamilies (all reused as-is, never
 * duplicated). Proposal is called via its repository directly, not a
 * service, mirroring the same precedent IRB already set here -
 * ProposalArchiveV1Service is bound to a different repository
 * (ProposalV1Repository) than the one findFamilies lives on
 * (ProposalArchiveRepository, the legacy one ProposalArchiveController
 * already uses), so threading it through that service would only add
 * cross-repository coupling for no benefit. Adding a future domain
 * means adding one more fan-out branch here, not extending
 * v_global_search or touching another domain's own search logic.
 *
 * All domain calls run concurrently (bounded by PER_DOMAIN_LIMIT each)
 * and are independently fault-tolerant: a failing domain is logged and
 * named in failedModules, never allowed to fail the whole request.
 */
@Service
public class GlobalSearchService {

    /** SEC-008; a disabled recorder unless activity auditing is switched on. */
    private final edu.bu.archive.application.authorization.audit.ArchiveActivityRecorder activity;

    private static final Logger log =
            LoggerFactory.getLogger(GlobalSearchService.class);

    private static final int PER_DOMAIN_LIMIT = 25;

    // Shared cross-domain rank tiers - lower sorts first. Each domain's
    // own internal ordering (IRB's search_rank CASE, Award's
    // exactDocumentMatch-then-id-lookup-then-searchAwards order) is
    // preserved within a tier by a stable sort over the concatenated
    // list, never re-derived here.
    private static final int RANK_EXACT_IDENTIFIER = 0;
    private static final int RANK_EXACT_NUMBER = 1;
    private static final int RANK_SUBSTRING = 3;
    private static final int RANK_FALLBACK = 4;
    // Always sorts after every structured tier, including the generic
    // fallback - a semantic result must never outrank anything
    // structured, per the semantic-search integration's proven rules.
    private static final int RANK_SEMANTIC = 5;

    // Sentinel matchedField value for semantic-sourced items, recognized
    // by rankOf() only - never shown to the user (the UI keys off
    // matchType instead).
    private static final String SEMANTIC_MATCHED_FIELD = "Semantic";
    private static final String MATCH_TYPE_RELATED = "RELATED";

    private final GlobalSearchRepository irbSearchRepository;
    private final AwardArchiveService awardArchiveService;
    private final NegotiationArchiveService negotiationArchiveService;
    private final SubawardArchiveService subawardArchiveService;
    private final ProposalArchiveRepository proposalArchiveRepository;
    private final SemanticSearchRepository semanticSearchRepository;
    private final SemanticSearchProperties semanticSearchProperties;
    private final ObjectProvider<EmbeddingProvider> embeddingProviderObjectProvider;
    // Temporary TC-042 measurement instrumentation, off by default.
    private final SearchTimingLog timingLog;

    /*
     * DEF-5 / Global Search. How many legs may execute AT THE SAME TIME
     * within one request. 0 (the default) means unbounded, which is exactly
     * today's behaviour, so merging this changes nothing until the property
     * is set.
     *
     * Why this exists: the API task is not CPU-bound - a measured 4x CPU
     * increase left Global Search flat and made the concurrent case 31%
     * WORSE - so the binding constraint is downstream of the application.
     * Reducing how many legs hit the database simultaneously is the
     * remaining lever that does not require buying capacity.
     *
     * Deliberately a SEMAPHORE held for the duration of one request, not a
     * shared executor. A shared bounded pool would couple concurrent users
     * to each other and introduce head-of-line blocking between them; a
     * per-request permit set bounds each request's own database load and
     * nothing else.
     *
     * Every future is still created and still joined in the same order, so
     * result content, ordering, counts and failedModules are untouched - a
     * leg that would have failed still fails, and still appears in
     * failedModules.
     */
    private final int fanOutConcurrency;
    /**
     * Carries the request's access decision onto the leg threads, and
     * answers the module-agnostic "is this caller scoped at all" question.
     * Defaults to {@link edu.bu.archive.application.authorization.RecordVisibility#ALL},
     * which propagates nothing - today's behaviour with enforcement off.
     */
    private final edu.bu.archive.application.authorization.RecordVisibility visibility;

    /**
     * Proposal record authorization, for the Proposal leg's SQL predicate
     * and its per-row enrichment filter. Defaults to PERMIT_ALL, which adds
     * no predicate. Award needs no equivalent here because its leg goes
     * through AwardArchiveService, which carries its own port.
     */
    private final edu.bu.archive.application.authorization.ProposalRecordAccess proposalAccess;

    /**
     * Whether record-level enforcement is switched on at all.
     *
     * <p>Distinct from {@code visibility.unrestricted()}, which conflates
     * "enforcement is off" with "this caller is Central". The IRB isolation
     * below keys on ENFORCEMENT, not on the caller: once enforcement is on,
     * IRB leaves shared Global Search for everyone, Central included,
     * because IRB has no record rule and shared search is the one surface
     * where its rows would travel beside authorized ones.
     */
    private final org.springframework.beans.factory.ObjectProvider<
            edu.bu.archive.application.authorization.AuthorizationProperties>
            authorizationProperties;

    /** Collection off: what every existing caller and test gets. */
    public GlobalSearchService(
            GlobalSearchRepository irbSearchRepository,
            AwardArchiveService awardArchiveService,
            NegotiationArchiveService negotiationArchiveService,
            SubawardArchiveService subawardArchiveService,
            ProposalArchiveRepository proposalArchiveRepository,
            SemanticSearchRepository semanticSearchRepository,
            SemanticSearchProperties semanticSearchProperties,
            ObjectProvider<EmbeddingProvider> embeddingProviderObjectProvider,
            SearchTimingLog timingLog,
            ObjectProvider<edu.bu.archive.application.authorization.RecordVisibility> visibility,
            ObjectProvider<edu.bu.archive.application.authorization.ProposalRecordAccess>
                    proposalAccessProvider,
            ObjectProvider<edu.bu.archive.application.authorization.AuthorizationProperties>
                    authorizationProperties
    ) {
        this(irbSearchRepository, awardArchiveService, negotiationArchiveService, subawardArchiveService, proposalArchiveRepository, semanticSearchRepository, semanticSearchProperties, embeddingProviderObjectProvider, timingLog, visibility, proposalAccessProvider, authorizationProperties,
             edu.bu.archive.application.authorization.audit.ArchiveActivityRecorder.disabled(java.time.Clock.systemUTC()),
             // 0 = unbounded: exactly today's fan-out.
             0);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GlobalSearchService(
            GlobalSearchRepository irbSearchRepository,
            AwardArchiveService awardArchiveService,
            NegotiationArchiveService negotiationArchiveService,
            SubawardArchiveService subawardArchiveService,
            ProposalArchiveRepository proposalArchiveRepository,
            SemanticSearchRepository semanticSearchRepository,
            SemanticSearchProperties semanticSearchProperties,
            ObjectProvider<EmbeddingProvider> embeddingProviderObjectProvider,
            SearchTimingLog timingLog,
            ObjectProvider<edu.bu.archive.application.authorization.RecordVisibility> visibility,
            ObjectProvider<edu.bu.archive.application.authorization.ProposalRecordAccess>
                    proposalAccessProvider,
            ObjectProvider<edu.bu.archive.application.authorization.AuthorizationProperties>
                    authorizationProperties
    ,
            edu.bu.archive.application.authorization.audit.ArchiveActivityRecorder activity,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.search.fan-out-concurrency:0}") int fanOutConcurrency) {
        this.activity = activity;
        this.fanOutConcurrency = fanOutConcurrency;

        this.authorizationProperties = authorizationProperties;
        this.irbSearchRepository = irbSearchRepository;
        this.awardArchiveService = awardArchiveService;
        this.negotiationArchiveService = negotiationArchiveService;
        this.subawardArchiveService = subawardArchiveService;
        this.proposalArchiveRepository = proposalArchiveRepository;
        this.semanticSearchRepository = semanticSearchRepository;
        this.semanticSearchProperties = semanticSearchProperties;
        this.embeddingProviderObjectProvider = embeddingProviderObjectProvider;
        this.timingLog = timingLog;
        edu.bu.archive.application.authorization.RecordVisibility resolvedVisibility =
                visibility == null ? null : visibility.getIfAvailable();
        this.visibility = resolvedVisibility != null
                ? resolvedVisibility
                : edu.bu.archive.application.authorization.RecordVisibility.ALL;
        edu.bu.archive.application.authorization.ProposalRecordAccess resolvedProposal =
                proposalAccessProvider == null ? null : proposalAccessProvider.getIfAvailable();
        this.proposalAccess = resolvedProposal != null
                ? resolvedProposal
                : edu.bu.archive.application.authorization.ProposalRecordAccess.PERMIT_ALL;
    }

    /**
     * The record types a caller may restrict Global Search to. The only
     * Global Search filter with the same meaning in every module - fields
     * like sponsor or status mean different things per domain (see
     * GlobalSearchItemResponse), so they are deliberately not offered here.
     */
    /**
     * IRB is OUT OF SCOPE for record authorization and will not get an
     * engine. But shared Global Search is a surface where its rows would
     * sit beside authorized Award/Proposal/Subaward/Negotiation results, so
     * once enforcement is on IRB is removed from shared search entirely -
     * no keyword leg, no semantic rows, and therefore no IRB identifiers,
     * links or contribution to totalResults.
     *
     * <p>The IRB module's own endpoints are untouched, as are its tables
     * and migrations. This is isolation of a shared surface, not an
     * authorization rule.
     *
     * <p>With enforcement OFF - today's deployed configuration - this is
     * false and IRB search behaves exactly as it always has.
     */
    private boolean irbExcludedFromSharedSearch() {
        if (authorizationProperties == null) {
            return false;
        }
        var settings = authorizationProperties.getIfAvailable();
        return settings != null && settings.isEnforcementEnabled();
    }

    public static final Set<String> SELECTABLE_MODULES =
            Set.of("AWARD", "PROPOSAL", "NEGOTIATION", "SUBAWARD");

    public GlobalSearchResponse search(String query) {
        return search(query, Set.of());
    }

    /**
     * @param modules record types to search; empty means every module
     *                (the original behaviour). An unselected module is not
     *                queried at all, and semantic hits are kept only for
     *                selected modules, so the result set is exactly what
     *                those modules return rather than a client-side slice.
     */
    public GlobalSearchResponse search(String query, Set<String> modules) {
        String normalizedQuery = query == null ? "" : query.trim();
        Set<String> selected = normalizeModules(modules);
        // TC-042: the id for this request, set by
        // SearchRequestTimingInterceptor, so the whole-request line and
        // these stages read as one request.
        String cid = timingLog.correlationId();
        long requestStartNanos = System.nanoTime();

        /*
         * QA TC-040. Where a module's own search already counts its matches,
         * that count is recorded here as the leg runs and reported beside the
         * rows. A ConcurrentHashMap rather than a return value because the
         * legs are fault-tolerant futures whose shared shape
         * (List<GlobalSearchItemResponse>) is relied on by supplyLeg,
         * startIfSelected, timedLeg and joinOrRecordFailure alike; widening
         * all four to carry a count would have been a larger change to the
         * fan-out than the question warrants.
         *
         * A module that fails leaves no entry, which is correct: a failed
         * module has no total, and reporting zero would read as "matched
         * nothing".
         */
        Map<String, Long> knownTotals = new java.util.concurrent.ConcurrentHashMap<>();

        /*
         * DEF-5. One permit set for THIS request. Null when unbounded, which
         * is the default and today's behaviour. Every leg still gets a
         * future; the permit only decides how many RUN at once, so nothing
         * about results, ordering, counts or failedModules moves.
         */
        final java.util.concurrent.Semaphore fanOutPermits =
                fanOutConcurrency > 0
                        ? new java.util.concurrent.Semaphore(fanOutConcurrency)
                        : null;

        // Not merely filtered afterwards: the leg never runs, so no IRB
        // row, identifier or count is computed for an enforced session.
        CompletableFuture<List<GlobalSearchItemResponse>> irbFuture =
                selected.isEmpty() && !irbExcludedFromSharedSearch()
                        ? supplyLeg(cid, "IRB", () -> searchIrb(normalizedQuery, knownTotals), fanOutPermits)
                        : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<GlobalSearchItemResponse>> awardFuture =
                startIfSelected(selected, cid, "AWARD", () -> searchAward(normalizedQuery, knownTotals), fanOutPermits);
        CompletableFuture<List<GlobalSearchItemResponse>> negotiationFuture =
                startIfSelected(selected, cid, "NEGOTIATION", () -> searchNegotiation(normalizedQuery, knownTotals), fanOutPermits);
        CompletableFuture<List<GlobalSearchItemResponse>> subawardFuture =
                startIfSelected(selected, cid, "SUBAWARD", () -> searchSubaward(normalizedQuery, knownTotals), fanOutPermits);
        CompletableFuture<List<GlobalSearchItemResponse>> proposalFuture =
                startIfSelected(selected, cid, "PROPOSAL", () -> searchProposal(normalizedQuery, knownTotals), fanOutPermits);

        // Semantic search is a strictly optional 6th input - see the
        // class comment. It is never started at all (no Bedrock call,
        // no pgvector query) when the flag is off or the query already
        // looks like an exact identifier, since structured search alone
        // is sufficient for those and semantic search would only add
        // latency for no benefit.
        boolean semanticEligible = semanticSearchProperties.isEnabled()
                && !LikelyIdentifierDetector.looksLikeIdentifier(normalizedQuery);
        /*
         * The semantic leg needs the SAME thread propagation as the five
         * module legs, and did not have it. It does not go through
         * supplyLeg (its timing instrumentation differs), so it was the one
         * leg running on a worker with no access decision reinstated - and
         * off the request thread RecordAuthorizationService.outcome() fails
         * closed to Denied(EVALUATION_FAILED). Its enrichment lookups call
         * requireProvisioned(), so once enforcement is on every semantic
         * search by a scoped caller threw IdentityAccessDeniedException and
         * was reported as a failed module. Fail-closed, so never a leak -
         * but a silently broken feature, which is its own kind of wrong
         * answer. With enforcement off propagate() returns the supplier
         * unchanged and nothing moves.
         */
        java.util.function.Supplier<List<GlobalSearchItemResponse>> semanticWork =
                visibility.propagate(() ->
                        timed("SEMANTIC", () -> timingLog.time(
                                cid,
                                "SEMANTIC_TOTAL",
                                () -> searchSemantic(normalizedQuery, cid),
                                List::size)));
        CompletableFuture<List<GlobalSearchItemResponse>> semanticFuture =
                semanticEligible
                        // Bounded like every other leg: it issues a pgvector
                        // query plus enrichment lookups, so leaving it outside
                        // the permit set would under-count the concurrency the
                        // bound is meant to limit.
                        ? CompletableFuture.supplyAsync(
                                () -> gated(fanOutPermits, cid, "SEMANTIC", semanticWork))
                        : null;

        List<String> failedModules = new ArrayList<>();
        List<GlobalSearchItemResponse> irbResults =
                joinOrRecordFailure(irbFuture, "IRB", failedModules);
        List<GlobalSearchItemResponse> awardResults =
                joinOrRecordFailure(awardFuture, "AWARD", failedModules);
        List<GlobalSearchItemResponse> negotiationResults =
                joinOrRecordFailure(negotiationFuture, "NEGOTIATION", failedModules);
        List<GlobalSearchItemResponse> subawardResults =
                joinOrRecordFailure(subawardFuture, "SUBAWARD", failedModules);
        List<GlobalSearchItemResponse> proposalResults =
                joinOrRecordFailure(proposalFuture, "PROPOSAL", failedModules);
        List<GlobalSearchItemResponse> semanticResults =
                semanticFuture != null
                        ? joinOrRecordFailure(semanticFuture, "SEMANTIC", failedModules)
                        : List.of();
        if (!selected.isEmpty()) {
            semanticResults = semanticResults.stream()
                    .filter(item -> selected.contains(item.module()))
                    .toList();
        }

        /*
         * Semantic results are NOT withheld when nothing matched
         * lexically. An earlier attempt did exactly that and was
         * rejected: it silently threw away the one kind of result that
         * helps a searcher who used a different word than the archive
         * does - a synonym, a general term, a phrase from a grant
         * rather than a title. Returning nothing is not more honest
         * than returning something clearly labelled as related.
         *
         * Relevance is a presentation problem, not a filtering one.
         * Every semantic row carries matchType RELATED, and the page
         * separates them: direct matches first, related results in
         * their own section, and - when nothing matched directly -
         * behind an explicit action, so a loose suggestion is never
         * mistaken for an answer.
         *
         * failedModules stays untouched here on purpose. A module that
         * failed is not a module that found nothing, and the page has
         * to be able to tell a searcher which happened.
         */

        List<GlobalSearchItemResponse> merged = new ArrayList<>(
                irbResults.size() + awardResults.size()
                        + negotiationResults.size() + subawardResults.size()
                        + proposalResults.size() + semanticResults.size()
        );
        merged.addAll(irbResults);
        merged.addAll(awardResults);
        merged.addAll(negotiationResults);
        merged.addAll(subawardResults);
        merged.addAll(proposalResults);
        merged.addAll(semanticResults);
        // Stable sort: items already in tier order within each domain
        // keep that relative order after this sort, so cross-domain
        // ranking never reshuffles either domain's own careful ordering.
        merged.sort((a, b) -> Integer.compare(rankOf(a), rankOf(b)));

        List<GlobalSearchItemResponse> deduplicated = deduplicate(merged);

        // TC-042: the service call only. The WHOLE request - including
        // parameter validation before this method and response writing
        // after it - is REQUEST_TOTAL, recorded by
        // SearchRequestTimingInterceptor. Keeping them separate is the
        // point: the gap between the two is the part this method cannot
        // see.
        timingLog.record(
                cid,
                "SERVICE_TOTAL",
                (System.nanoTime() - requestStartNanos) / 1_000_000,
                deduplicated.size()
        );

        // SEC-008: that a search ran, and how many rows came back. The
        // query itself is deliberately not passed - the recorder has no
        // parameter that could carry it.
        activity.searchPerformed("GLOBAL", deduplicated.size());

        return new GlobalSearchResponse(
                normalizedQuery,
                deduplicated.size(),
                deduplicated,
                failedModules,
                moduleTotals(deduplicated, knownTotals)
        );
    }

    /**
     * Per-module counts: returned, matched where known, and whether capped.
     *
     * <p>QA TC-040 observed that Global Search "appears limited to 25 direct
     * matches per entity type" and asked whether {@code totalResults} counts
     * matching records. It does not - it counts rows - and the cap is real
     * and fixed at {@link #PER_DOMAIN_LIMIT}. Rather than redefine
     * {@code totalResults} underneath existing callers, the honest numbers
     * are reported alongside it.
     *
     * <p>Counted from the DEDUPLICATED list, so the figures describe what the
     * caller actually received. Semantic results are excluded from
     * {@code returned}: they are a different kind of answer and are not part
     * of the module's capped page, so counting them there would make a
     * module look as though its direct search returned more than it did.
     */
    private static List<GlobalSearchModuleTotal> moduleTotals(
            List<GlobalSearchItemResponse> results,
            Map<String, Long> knownTotals
    ) {
        Map<String, Integer> returned = new LinkedHashMap<>();
        for (GlobalSearchItemResponse item : results) {
            if (MATCH_TYPE_RELATED.equals(item.matchType())) {
                continue;
            }
            returned.merge(item.module(), 1, Integer::sum);
        }

        Set<String> modules = new java.util.LinkedHashSet<>(returned.keySet());
        modules.addAll(knownTotals.keySet());

        List<GlobalSearchModuleTotal> totals = new ArrayList<>(modules.size());
        for (String module : modules) {
            int rows = returned.getOrDefault(module, 0);
            totals.add(new GlobalSearchModuleTotal(
                    module,
                    rows,
                    knownTotals.get(module),
                    rows >= PER_DOMAIN_LIMIT));
        }
        return List.copyOf(totals);
    }

    private static Set<String> normalizeModules(Set<String> modules) {
        if (modules == null || modules.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String module : modules) {
            if (module == null || module.isBlank()) {
                continue;
            }
            String upper = module.trim().toUpperCase(java.util.Locale.ROOT);
            if (!SELECTABLE_MODULES.contains(upper)) {
                throw new IllegalArgumentException(
                        "Unknown record type: " + module.trim()
                                + " (expected one of AWARD, PROPOSAL, NEGOTIATION, SUBAWARD)");
            }
            normalized.add(upper);
        }
        return Set.copyOf(normalized);
    }

    private CompletableFuture<List<GlobalSearchItemResponse>> startIfSelected(
            Set<String> selected,
            String cid,
            String module,
            java.util.function.Supplier<List<GlobalSearchItemResponse>> search,
            java.util.concurrent.Semaphore fanOutPermits
    ) {
        if (!selected.isEmpty() && !selected.contains(module)) {
            return CompletableFuture.completedFuture(List.of());
        }
        return supplyLeg(cid, module, search, fanOutPermits);
    }

    /*
     * TC-042. Schedules a leg and records how long it waited before the
     * worker actually began, separately from how long the work took.
     *
     * Why this matters here specifically: the legs are handed to
     * CompletableFuture.supplyAsync with no executor. That uses the
     * common ForkJoinPool - UNLESS its parallelism is 1, in which case
     * the JDK silently falls back to a thread-per-task executor. The
     * deployed task is 0.5 vCPU, and the timing run's own log lines come
     * from threads named Thread-N rather than
     * ForkJoinPool.commonPool-worker-N, which is the fallback's
     * signature. So the Award leg runs on a freshly created thread while
     * the same search through AwardV1Controller runs inline on the
     * Tomcat worker - a real difference between the two paths, and one
     * worth measuring rather than reasoning about.
     *
     * A thread-per-task executor should show a near-zero wait. If it
     * does, scheduling is excluded and the cost is inside the work. If
     * it does not, that is the finding.
     */
    /*
     * DEF-5. Runs the leg under a permit when the fan-out is bounded, and
     * runs it unchanged when it is not.
     *
     * Reviewed for deadlock, cancellation, interruption and release:
     *
     *   - DEADLOCK: each leg acquires exactly one permit, never nested, and
     *     always releases it in a finally. The thread that joins the futures
     *     holds no permit. There is no circular wait, and
     *     fanOutConcurrency > 0 guarantees at least one permit exists.
     *   - CANCELLATION: nothing in this service cancels a leg - there is no
     *     orTimeout, completeOnTimeout or cancel() call, and
     *     joinOrRecordFailure uses a plain join(). Even an externally
     *     cancelled future cannot strand a permit, because
     *     CompletableFuture.cancel does not interrupt a running
     *     supplyAsync task, so the thread still reaches its finally.
     *   - RELEASE: the finally is guarded by `acquired` and runs for an
     *     Error as well as an Exception.
     *   - INTERRUPTION: an interrupt while waiting restores the flag and
     *     fails the leg, which joinOrRecordFailure turns into a
     *     failedModules entry. It does NOT run the work.
     *
     * That last point reverses this method's first version, which ran the
     * work anyway on the theory that a missing leg reading as "found
     * nothing" was worse than running unpermitted. On review that was
     * wrong twice over: it breaks the bound this method exists to enforce,
     * and it runs JDBC and HTTP work with the interrupt flag already set,
     * which some clients treat as an abort - so the "ran anyway" path
     * could fail in a harder-to-read way than simply failing. Failing
     * loudly is also the behaviour the rest of the fan-out already has:
     * a module that failed is reported in failedModules, never silently
     * collapsed into an empty result.
     *
     * The wait is recorded separately from the work so a bounded run can be
     * read as "waiting for a permit" versus "executing", which is the whole
     * point of measuring the bound.
     */
    private List<GlobalSearchItemResponse> gated(
            java.util.concurrent.Semaphore permits,
            String cid,
            String module,
            java.util.function.Supplier<List<GlobalSearchItemResponse>> work
    ) {
        if (permits == null) {
            return work.get();
        }
        long waitStartNanos = System.nanoTime();
        try {
            permits.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while waiting for a fan-out permit", interrupted);
        }
        timingLog.record(
                cid,
                "LEG_" + module + "_PERMIT_WAIT",
                (System.nanoTime() - waitStartNanos) / 1_000_000,
                -1
        );
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }

    private CompletableFuture<List<GlobalSearchItemResponse>> supplyLeg(
            String cid,
            String module,
            java.util.function.Supplier<List<GlobalSearchItemResponse>> work,
            java.util.concurrent.Semaphore fanOutPermits
    ) {
        /*
         * THREAD PROPAGATION (2026-10-26). Each leg runs on another thread,
         * and the per-request access decision is held in a request-scoped
         * attribute that does not follow it. Off the request thread
         * RecordAuthorizationService.outcome() fails closed, so without this
         * the Award leg would return NOTHING once enforcement is on - safe,
         * but silently and wrongly empty, which is its own kind of lie.
         *
         * propagate() captures the decision on the calling (request) thread
         * and reinstates it inside the worker. With enforcement off it
         * returns the supplier unchanged, so nothing about today's behaviour
         * moves. Applied to EVERY leg, not just Award: the same reasoning
         * holds for Proposal, Subaward and Negotiation as they are wired.
         */
        java.util.function.Supplier<List<GlobalSearchItemResponse>> scopedWork =
                visibility.propagate(work);

        long scheduledAtNanos = System.nanoTime();
        return CompletableFuture.supplyAsync(() -> {
            timingLog.record(
                    cid,
                    "LEG_" + module + "_QUEUE_WAIT",
                    (System.nanoTime() - scheduledAtNanos) / 1_000_000,
                    -1
            );
            return gated(fanOutPermits, cid, module, () -> timedLeg(cid, module, scopedWork));
        });
    }

    private List<GlobalSearchItemResponse> joinOrRecordFailure(
            CompletableFuture<List<GlobalSearchItemResponse>> future,
            String moduleName,
            List<String> failedModules
    ) {
        try {
            return future.join();
        } catch (RuntimeException exception) {
            log.warn(
                    "Global Search: {} module failed, returning partial results",
                    moduleName,
                    exception
            );
            failedModules.add(moduleName);
            return List.of();
        }
    }

    private <T> T timed(String moduleName, java.util.function.Supplier<T> work) {
        long startNanos = System.nanoTime();
        try {
            return work.get();
        } finally {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
            log.debug(
                    "Global Search: {} module took {}ms",
                    moduleName,
                    elapsedMillis
            );
        }
    }

    /*
     * TC-042: the same per-module measurement, but emitted through the
     * timing log so one run shows the lexical legs and the semantic
     * stages together under a single correlation id. The debug line
     * above is left exactly as it was.
     */
    private <T extends java.util.Collection<?>> T timedLeg(
            String cid,
            String moduleName,
            java.util.function.Supplier<T> work
    ) {
        return timingLog.time(cid, "LEG_" + moduleName, () -> timed(moduleName, work),
                java.util.Collection::size);
    }

    // --- IRB -----------------------------------------------------------

    private List<GlobalSearchItemResponse> searchIrb(
            String query, Map<String, Long> knownTotals) {
        List<IrbGlobalSearchRow> rows =
                irbSearchRepository.search(query, PER_DOMAIN_LIMIT);

        List<GlobalSearchItemResponse> mapped = new ArrayList<>(rows.size());
        for (IrbGlobalSearchRow row : rows) {
            mapped.add(toGlobalSearchItem(row));
        }
        return mapped;
    }

    private GlobalSearchItemResponse toGlobalSearchItem(IrbGlobalSearchRow row) {
        String route = row.recordId() != null
                ? "/irb/record/" + row.recordId()
                : (row.protocolId() != null
                        ? "/irb/history/" + row.protocolId()
                        : null);

        return new GlobalSearchItemResponse(
                row.module(),
                row.recordId(),
                row.identifier(),
                row.title(),
                row.personName(),
                row.status(),
                null,
                row.matchedField(),
                row.matchedValue(),
                route,
                row.protocolId(),
                null,
                null,
                null,
                null,
                null
        );
    }

    // --- Award -----------------------------------------------------------

    private List<GlobalSearchItemResponse> searchAward(
            String query, Map<String, Long> knownTotals) {
        List<GlobalSearchItemResponse> mapped = new ArrayList<>();

        if (query.matches("\\d+")) {
            findAwardById(Long.parseLong(query)).ifPresent(mapped::add);
        }

        AwardSearchResponse response =
                awardArchiveService.search(query, 0, PER_DOMAIN_LIMIT);
        knownTotals.put("AWARD", response.results().totalElements());

        if (response.exactDocumentMatch() != null) {
            mapped.add(toGlobalSearchItem(response.exactDocumentMatch()));
        }

        /*
         * QA TC-040. An Award whose match cannot be explained from its own
         * displayed fields - a non-PI team member, the sponsor's acronym, the
         * sponsor code, the modification number - previously said
         * "Matched on: Award (<number>)", which explains nothing. The reason
         * is now asked of the database, ONCE for the whole page rather than
         * once per row, and only for the rows that need it. A row whose
         * reason still cannot be established carries no explanation, rather
         * than a fabricated one.
         */
        List<AwardSearchResultResponse> rows = response.results().content();
        List<Long> unexplained = rows.stream()
                .filter(r -> awardMatchedField(r, query) == null)
                .map(AwardSearchResultResponse::awardId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, MatchExplanation> explanations = explainAwards(unexplained, query);

        for (AwardSearchResultResponse result : rows) {
            mapped.add(toGlobalSearchItem(result, query, explanations));
        }

        return mapped;
    }

    /**
     * The database's own account of why these Awards matched.
     *
     * <p>Fault-tolerant on purpose: an explanation is a nicety, and a failure
     * to produce one must never cost the caller their results. The leg itself
     * is already fault-tolerant, but losing 25 Awards because a cosmetic
     * query failed would be a poor trade.
     */
    private Map<Long, MatchExplanation> explainAwards(List<Long> awardIds, String query) {
        if (awardIds.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, MatchExplanation> byId = new LinkedHashMap<>();
            for (MatchExplanation explanation : awardArchiveService.explainMatches(awardIds, query)) {
                byId.putIfAbsent(explanation.recordId(), explanation);
            }
            return byId;
        } catch (RuntimeException exception) {
            log.warn("Global Search: could not explain {} Award match(es)",
                    awardIds.size(), exception);
            return Map.of();
        }
    }

    private java.util.Optional<GlobalSearchItemResponse> findAwardById(
            long awardId
    ) {
        try {
            AwardSummaryResponse summary = awardArchiveService.findSummary(awardId);
            return java.util.Optional.of(new GlobalSearchItemResponse(
                    "AWARD",
                    summary.awardId(),
                    summary.awardNumber(),
                    summary.title(),
                    summary.sponsor(),
                    summary.status(),
                    null,
                    "Award ID",
                    String.valueOf(summary.awardId()),
                    "/awards/" + summary.awardId(),
                    null,
                    summary.awardId(),
                    summary.sequenceNumber(),
                    null,
                    null,
                    summary.principalInvestigator()
            ));
        } catch (NoSuchElementException notFound) {
            return java.util.Optional.empty();
        }
    }

    private GlobalSearchItemResponse toGlobalSearchItem(
            AwardDocumentNumberMatchResponse match
    ) {
        return new GlobalSearchItemResponse(
                "AWARD",
                match.awardId(),
                match.awardNumber(),
                match.title(),
                "Sequence " + match.sequenceNumber(),
                match.status(),
                match.workflowDocumentNumber(),
                "Workflow Document Number",
                match.workflowDocumentNumber(),
                "/awards/" + match.awardId(),
                null,
                match.awardId(),
                match.sequenceNumber(),
                null,
                null,
                null
        );
    }

    private GlobalSearchItemResponse toGlobalSearchItem(
            AwardSearchResultResponse result,
            String query,
            Map<Long, MatchExplanation> explanations
    ) {
        String matchedField = awardMatchedField(result, query);
        String matchedValue = awardMatchedValue(result, matchedField);
        if (matchedField == null) {
            MatchExplanation explained = explanations.get(result.awardId());
            if (explained != null) {
                matchedField = explained.field();
                matchedValue = explained.value();
            }
        }

        return new GlobalSearchItemResponse(
                "AWARD",
                result.awardId(),
                result.awardNumber(),
                result.title(),
                result.sponsor(),
                result.status(),
                null,
                matchedField,
                matchedValue,
                "/awards/" + result.awardId(),
                null,
                result.awardId(),
                result.latestSequenceNumber(),
                Boolean.TRUE,
                null,
                result.principalInvestigator()
        );
    }

    // searchAwards' own SQL doesn't expose which column matched, so this
    // heuristic re-derives a human-readable label from the same fields
    // the query WHERE clause searches - see
    // AwardArchiveRepository.searchAwards.
    /**
     * Which of the Award's OWN displayed fields the term matched, or null.
     *
     * <p>Null means "this result cannot explain itself from what it carries",
     * not "it did not match". The Award search also matches any team member's
     * name, the sponsor's acronym, the sponsor code and the modification
     * number, none of which are in this DTO; those are explained by
     * {@code explainAwardMatches} instead of guessed at here.
     *
     * <p>QA TC-040: this previously fell through to the literal "Award",
     * rendered as "Matched on: Award (100527-00001)", and used a plain
     * substring test - so a search for "NSF" reported "Matched on: Title"
     * against "ENERGY TRANSFER IN PROTEINS". Both are gone: the test is now
     * {@link LexicalMatch#matches}, the same rule the SQL applies, and an
     * unexplainable match says nothing at all.
     */
    private String awardMatchedField(AwardSearchResultResponse result, String query) {
        if (LexicalMatch.matches(query, result.awardNumber())) {
            return "Award Number";
        }
        if (LexicalMatch.matches(query, result.title())) {
            return "Title";
        }
        if (LexicalMatch.matches(query, result.principalInvestigator())) {
            return "Principal Investigator";
        }
        if (LexicalMatch.matches(query, result.sponsor())) {
            return "Sponsor";
        }
        if (LexicalMatch.matches(query, result.leadUnit())) {
            return "Lead Unit";
        }
        if (LexicalMatch.matches(query, result.grantNumber())) {
            return "Grant Number";
        }
        return null;
    }

    private String awardMatchedValue(
            AwardSearchResultResponse result,
            String matchedField
    ) {
        if (matchedField == null) {
            // No field to quote. The caller shows no caption rather than
            // echoing the award number as though it were the match.
            return null;
        }
        return switch (matchedField) {
            case "Award Number" -> result.awardNumber();
            case "Title" -> result.title();
            case "Sponsor" -> result.sponsor();
            case "Lead Unit" -> result.leadUnit();
            case "Principal Investigator" -> result.principalInvestigator();
            case "Grant Number" -> result.grantNumber();
            default -> null;
        };
    }

    // --- Negotiation -------------------------------------------------------

    private List<GlobalSearchItemResponse> searchNegotiation(
            String query, Map<String, Long> knownTotals) {
        PageResponse<NegotiationSummaryResponse> page =
                negotiationArchiveService.findPage(query, 0, PER_DOMAIN_LIMIT);
        knownTotals.put("NEGOTIATION", page.totalElements());

        List<GlobalSearchItemResponse> mapped =
                new ArrayList<>(page.content().size());
        for (NegotiationSummaryResponse result : page.content()) {
            mapped.add(toGlobalSearchItem(result, query));
        }
        return mapped;
    }

    private GlobalSearchItemResponse toGlobalSearchItem(
            NegotiationSummaryResponse result,
            String query
    ) {
        String matchedField = negotiationMatchedField(result, query);

        return new GlobalSearchItemResponse(
                "NEGOTIATION",
                result.negotiationId(),
                result.documentNumber(),
                result.negotiationAgreementTypeDescription(),
                result.negotiatorFullName(),
                result.negotiationStatusDescription(),
                result.documentNumber(),
                matchedField,
                negotiationMatchedValue(result, matchedField),
                "/negotiations/" + result.negotiationId(),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    // findNegotiations' own SQL doesn't expose which column matched,
    // same heuristic approach as awardMatchedField - see
    // NegotiationArchiveRepository.findNegotiations.
    /**
     * Which of the Negotiation's displayed fields matched, or null.
     *
     * <p>QA TC-040: all 25 rows for "WILSON WONG" were explained as
     * "Negotiation", because the match was the associated Award's principal
     * investigator - a field this DTO has carried all along and that this
     * method never tested. The title, sponsor and lead unit were missing for
     * the same reason. No extra query is needed to explain a Negotiation;
     * the fields were simply not being read.
     */
    private String negotiationMatchedField(
            NegotiationSummaryResponse result,
            String query
    ) {
        if (LexicalMatch.matches(query, result.documentNumber())) {
            return "Document Number";
        }
        if (result.negotiationId() != null
                && LexicalMatch.matches(query, String.valueOf(result.negotiationId()))) {
            return "Negotiation ID";
        }
        if (LexicalMatch.matches(query, result.principalInvestigatorName())) {
            return "Principal Investigator";
        }
        if (LexicalMatch.matches(query, result.negotiatorFullName())) {
            return "Negotiator";
        }
        if (LexicalMatch.matches(query, result.title())) {
            return "Associated Award Title";
        }
        if (LexicalMatch.matches(query, result.sponsorName())) {
            return "Sponsor";
        }
        if (LexicalMatch.matches(query, result.leadUnitName())) {
            return "Lead Unit";
        }
        if (LexicalMatch.matches(query, result.negotiationAgreementTypeDescription())) {
            return "Agreement Type";
        }
        if (LexicalMatch.matches(query, result.negotiationStatusDescription())) {
            return "Status";
        }
        if (LexicalMatch.matches(query, result.associatedDocumentId())) {
            return "Associated Document";
        }
        return null;
    }

    private String negotiationMatchedValue(
            NegotiationSummaryResponse result,
            String matchedField
    ) {
        if (matchedField == null) {
            return null;
        }
        return switch (matchedField) {
            case "Document Number" -> result.documentNumber();
            case "Negotiation ID" -> String.valueOf(result.negotiationId());
            case "Principal Investigator" -> result.principalInvestigatorName();
            case "Negotiator" -> result.negotiatorFullName();
            case "Associated Award Title" -> result.title();
            case "Sponsor" -> result.sponsorName();
            case "Lead Unit" -> result.leadUnitName();
            case "Agreement Type" -> result.negotiationAgreementTypeDescription();
            case "Status" -> result.negotiationStatusDescription();
            case "Associated Document" -> result.associatedDocumentId();
            default -> null;
        };
    }

    // --- Subaward ----------------------------------------------------------

    private List<GlobalSearchItemResponse> searchSubaward(
            String query, Map<String, Long> knownTotals) {
        var page = subawardArchiveService.findPage(query, 0, PER_DOMAIN_LIMIT);
        knownTotals.put("SUBAWARD", page.totalElements());

        List<GlobalSearchItemResponse> mapped =
                new ArrayList<>(page.content().size());
        for (SubawardSummaryResponse result : page.content()) {
            mapped.add(toGlobalSearchItem(result, query));
        }
        return mapped;
    }

    private GlobalSearchItemResponse toGlobalSearchItem(
            SubawardSummaryResponse result,
            String query
    ) {
        String matchedField = subawardMatchedField(result, query);

        return new GlobalSearchItemResponse(
                "SUBAWARD",
                result.subawardId(),
                result.subawardCode(),
                result.title(),
                result.organizationId(),
                result.statusDescription(),
                result.documentNumber(),
                matchedField,
                subawardMatchedValue(result, matchedField),
                "/subawards/" + result.subawardId(),
                null,
                null,
                result.sequenceNumber(),
                null,
                null,
                null
        );
    }

    /**
     * Which of the Subaward's displayed fields matched, or null.
     *
     * <p>QA TC-040, the Subaward half. This used a plain substring test and
     * fell through to the literal "Subaward", so a search for "NSF"
     * reported "Matched on: Title" against "phosphoglycosyl transferase"
     * and "Matched on: Subaward (4319)" for anything it could not place.
     * Both are gone: the test is {@link LexicalMatch#matches}, the same rule
     * the SQL now applies, and an unexplainable match says nothing at all.
     *
     * <p>Account number, status and the subaward id are tested too. The SQL
     * has always matched them and this method never read them - the same
     * omission that reported all 25 "WILSON WONG" Negotiations as
     * "Negotiation".
     *
     * <p>Note what is NOT here: the two sponsor names the SQL also matches
     * are not carried by this DTO, so a sponsor match is deliberately
     * unexplainable and shows no caption, rather than being attributed to
     * whichever field happens to contain the letters.
     */
    private String subawardMatchedField(SubawardSummaryResponse result, String query) {
        if (LexicalMatch.matches(query, result.subawardCode())) {
            return "Subaward Code";
        }
        if (LexicalMatch.matches(query, result.documentNumber())) {
            return "Document Number";
        }
        if (result.subawardId() != null
                && LexicalMatch.matches(query, String.valueOf(result.subawardId()))) {
            return "Subaward ID";
        }
        if (LexicalMatch.matches(query, result.title())) {
            return "Title";
        }
        if (LexicalMatch.matches(query, result.organizationId())) {
            return "Organization";
        }
        if (LexicalMatch.matches(query, result.accountNumber())) {
            return "Account Number";
        }
        if (LexicalMatch.matches(query, result.statusDescription())) {
            return "Status";
        }
        return null;
    }

    private String subawardMatchedValue(
            SubawardSummaryResponse result,
            String matchedField
    ) {
        if (matchedField == null) {
            // No field to quote. The caller shows no caption rather than
            // echoing the subaward code as though it were the match.
            return null;
        }
        return switch (matchedField) {
            case "Subaward Code" -> result.subawardCode();
            case "Document Number" -> result.documentNumber();
            case "Subaward ID" -> String.valueOf(result.subawardId());
            case "Title" -> result.title();
            case "Organization" -> result.organizationId();
            case "Account Number" -> result.accountNumber();
            case "Status" -> result.statusDescription();
            default -> null;
        };
    }

    // --- Proposal ------------------------------------------------------

    private List<GlobalSearchItemResponse> searchProposal(
            String query, Map<String, Long> knownTotals) {
        List<ProposalFamilySummaryResponse> results =
                proposalArchiveRepository.findFamilies(
                        query, proposalAccess.proposalScopeSql("ranked"), PER_DOMAIN_LIMIT);

        /*
         * QA TC-040. A Proposal that matched only through its sponsor's
         * acronym - 3,746 of them are NSF-sponsored - cannot explain itself
         * from the fields it carries, so the reason is asked of the database
         * ONCE for the whole page and only for the rows that need it. A row
         * whose reason still cannot be established carries no explanation
         * rather than a fabricated one.
         */
        List<Long> unexplained = results.stream()
                .filter(r -> proposalMatchedField(r, query) == null)
                .map(ProposalFamilySummaryResponse::currentProposalId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, MatchExplanation> explanations = explainProposals(unexplained, query);

        List<GlobalSearchItemResponse> mapped = new ArrayList<>(results.size());
        for (ProposalFamilySummaryResponse result : results) {
            mapped.add(toGlobalSearchItem(result, query, explanations));
        }
        return mapped;
    }

    /** Same fault tolerance as {@link #explainAwards}: a caption is a nicety. */
    private Map<Long, MatchExplanation> explainProposals(
            List<Long> proposalIds, String query) {
        String rawQuery = query == null ? "" : query.trim();
        if (proposalIds.isEmpty() || rawQuery.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, MatchExplanation> byId = new LinkedHashMap<>();
            for (MatchExplanation explanation
                    : proposalArchiveRepository.explainProposalMatches(
                            proposalIds,
                            rawQuery,
                            LexicalMatch.anchorsToWordStart(rawQuery),
                            LexicalMatch.wordStartRegex(rawQuery))) {
                byId.putIfAbsent(explanation.recordId(), explanation);
            }
            return byId;
        } catch (RuntimeException exception) {
            log.warn("Global Search: could not explain {} Proposal match(es)",
                    proposalIds.size(), exception);
            return Map.of();
        }
    }

    private GlobalSearchItemResponse toGlobalSearchItem(
            ProposalFamilySummaryResponse result,
            String query,
            Map<Long, MatchExplanation> explanations
    ) {
        String matchedField = proposalMatchedField(result, query);
        Long currentProposalId = result.currentProposalId();
        if (matchedField == null && currentProposalId != null) {
            MatchExplanation explained = explanations.get(currentProposalId);
            if (explained != null) {
                return proposalItem(
                        result, currentProposalId, explained.field(), explained.value());
            }
        }

        return proposalItem(
                result,
                currentProposalId,
                matchedField,
                proposalMatchedValue(result, matchedField));
    }

    /**
     * One place where a Proposal result card is built, so the self-explained
     * and database-explained paths cannot drift in anything but the caption.
     */
    private GlobalSearchItemResponse proposalItem(
            ProposalFamilySummaryResponse result,
            Long currentProposalId,
            String matchedField,
            String matchedValue
    ) {
        return new GlobalSearchItemResponse(
                "PROPOSAL",
                currentProposalId,
                result.proposalNumber(),
                result.title(),
                result.principalInvestigator(),
                result.status(),
                null,
                matchedField,
                matchedValue,
                currentProposalId != null
                        ? "/proposals/dashboard/" + currentProposalId
                        : null,
                null,
                null,
                result.latestVersionNumber(),
                null,
                null,
                result.principalInvestigator()
        );
    }

    /**
     * Which of the Proposal's displayed fields matched, or null.
     *
     * <p>QA TC-040, the Proposal half. The test was a plain substring one,
     * so a search for "NSF" reported "Matched on: Title" against "Priority
     * Sector Targeting and Structural Transformation in Ethiopia". The test
     * is now {@link LexicalMatch#matches}, the same rule the SQL applies.
     *
     * <p>Null means "this result cannot explain itself from what it
     * carries", not "it did not match". The sponsor's acronym is now a
     * retrieval rule and is not in this DTO; those rows are explained by
     * {@code explainProposalMatches} instead of guessed at here.
     *
     * <p>The literal "Proposal" fallback is gone. With the predicate and
     * the explanation sharing one rule it should now be unreachable, and if
     * it is ever reached the honest answer is no caption rather than
     * "Matched on: Proposal (01116997)".
     */
    private String proposalMatchedField(
            ProposalFamilySummaryResponse result,
            String query
    ) {
        if (LexicalMatch.matches(query, result.proposalNumber())) {
            return "Proposal Number";
        }
        if (LexicalMatch.matches(query, result.title())) {
            return "Title";
        }
        if (LexicalMatch.matches(query, result.sponsorName())) {
            return "Sponsor";
        }
        if (LexicalMatch.matches(query, result.leadUnitName())) {
            return "Lead Unit";
        }
        if (LexicalMatch.matches(query, result.principalInvestigator())) {
            return "Principal Investigator";
        }
        return null;
    }

    private String proposalMatchedValue(
            ProposalFamilySummaryResponse result,
            String matchedField
    ) {
        if (matchedField == null) {
            return null;
        }
        return switch (matchedField) {
            case "Proposal Number" -> result.proposalNumber();
            case "Title" -> result.title();
            case "Sponsor" -> result.sponsorName();
            case "Lead Unit" -> result.leadUnitName();
            case "Principal Investigator" -> result.principalInvestigator();
            default -> null;
        };
    }

    // --- Semantic --------------------------------------------------------

    // Embeds the query text and looks up its nearest neighbors in
    // archive.search_embedding (production table, V070 - never the PoC
    // table, archive.search_embedding_poc, which stays a permanently
    // separate regression benchmark). No similarity threshold, per the
    // threshold experiment's finding that no single global cutoff works
    // - just a hard Top-5 cap on the FINAL, deduplicated result list
    // (min'd again here even though the repository already applies its
    // own LIMIT, since SemanticSearchProperties.topK is
    // operator-configurable and must never be trusted to exceed the
    // proven-safe maximum). Any failure here (embedding call, pgvector
    // query, enrichment query) propagates up to joinOrRecordFailure
    // exactly like every other domain's failure - Global Search still
    // returns full structured results.
    private static final int SEMANTIC_MAX_RESULTS = 5;

    // The repository is asked for more than SEMANTIC_MAX_RESULTS raw
    // embedding rows on purpose: a single business record (e.g. one
    // Award) can legitimately have several archived-version rows in
    // archive.search_embedding, each embedded and ranked independently,
    // so the nearest-neighbor list can contain several rows that all
    // resolve to the same Award/Proposal before per-business-record
    // dedup runs. Oversampling a bounded, fixed candidate window (never
    // unbounded, never operator-configurable) lets dedup collapse those
    // duplicates without starving the Top-5 cap of otherwise-distinct
    // matches - see dedupeByBusinessRecord.
    private static final int SEMANTIC_CANDIDATE_LIMIT = 50;

    private List<GlobalSearchItemResponse> searchSemantic(String query, String cid) {
        EmbeddingProvider embeddingProvider =
                embeddingProviderObjectProvider.getIfAvailable();
        if (embeddingProvider == null) {
            return List.of();
        }

        /*
         * TC-042. The embedding call is a network round trip to Bedrock.
         * Its duration here INCLUDES any retries the AWS SDK performs
         * internally - the SDK retries without telling the caller, so a
         * single slow figure covering the retries is what we can
         * honestly measure from this side. A configured timeout bounds
         * one attempt, not this number.
         */
        float[] queryEmbedding = timingLog.time(
                cid, "SEMANTIC_EMBED", () -> embeddingProvider.embed(query));

        List<SemanticSearchRow> candidates = timingLog.time(
                cid,
                "SEMANTIC_VECTOR_QUERY",
                () -> semanticSearchRepository.findNearest(
                        queryEmbedding, SEMANTIC_CANDIDATE_LIMIT),
                List::size);

        List<SemanticSearchRow> deduped = dedupeByBusinessRecord(candidates);
        recordRelevanceShape(cid, candidates, deduped);

        int topK = Math.min(semanticSearchProperties.getTopK(), SEMANTIC_MAX_RESULTS);
        if (deduped.size() > topK) {
            deduped = deduped.subList(0, topK);
        }

        List<SemanticSearchRow> toEnrich = deduped;
        return timingLog.time(
                cid,
                "SEMANTIC_ENRICH_TOTAL",
                () -> enrichSemanticResults(toEnrich, cid),
                List::size);
    }

    /**
     * The shape of this query's nearest-neighbour distances, for measurement.
     *
     * <h2>Why this exists</h2>
     *
     * <p>QA TC-041 asks whether Global Search returns its top five nearest
     * vectors regardless of relevance. It does: there is no similarity
     * threshold anywhere in the pipeline, by an earlier deliberate decision
     * that no single global cutoff separated useful from useless results.
     *
     * <p>That decision cannot be revisited on evidence, because the evidence
     * is not observable. Cosine distance is computed in
     * {@code SemanticSearchRepository}, used to order the rows, and then
     * discarded - {@code GlobalSearchItemResponse} documents, deliberately,
     * that no distance or score ever leaves the backend, and that is a
     * reasonable rule for a client-facing DTO. So the distances are recorded
     * HERE instead, where a labelled set of relevant and irrelevant queries
     * can be run against DEV and the separation actually measured.
     *
     * <p>What is logged is only the shape of the distance distribution: the
     * nearest, the furthest kept, and the candidate-window median. No
     * identifier, no business number, no query text - the activity audit
     * deliberately does not record what someone searched for, and this must
     * not become the place that does. The median is the useful one: a
     * meaningful query is expected to have a nearest much closer than its
     * median, and a nonsense query to have them nearly equal, which is the
     * basis of a relative rejection rule that needs no global constant.
     */
    private void recordRelevanceShape(
            String cid,
            List<SemanticSearchRow> candidates,
            List<SemanticSearchRow> deduped
    ) {
        if (candidates.isEmpty()) {
            return;
        }
        double[] sorted = candidates.stream()
                .mapToDouble(SemanticSearchRow::distance)
                .sorted()
                .toArray();
        double nearest = sorted[0];
        double median = sorted[sorted.length / 2];
        double furthestKept = deduped.isEmpty()
                ? nearest
                : deduped.get(deduped.size() - 1).distance();

        log.info(
                "SEMANTIC_RELEVANCE cid={} candidates={} nearest={} furthestKept={} "
                        + "candidateMedian={} nearestToMedianRatio={}",
                cid,
                candidates.size(),
                String.format("%.4f", nearest),
                String.format("%.4f", furthestKept),
                String.format("%.4f", median),
                median == 0 ? "n/a" : String.format("%.4f", nearest / median)
        );
    }

    // Keeps the single best-scoring (lowest-distance) row per business
    // record - candidates arrive pre-sorted by ascending distance
    // (nearest first), so the first occurrence of a (module,
    // businessNumber) key encountered here is already its best match.
    // Keyed on the exact business identifier rather than
    // canonicalFamilyId so this dedup holds even if two rows for the
    // same Award/Proposal were embedded from different archived
    // versions and therefore carry different record/family IDs.
    private List<SemanticSearchRow> dedupeByBusinessRecord(
            List<SemanticSearchRow> rows
    ) {
        Map<String, SemanticSearchRow> byBusinessRecord = new LinkedHashMap<>();
        for (SemanticSearchRow row : rows) {
            String key = row.module() + ":" + row.businessNumber();
            byBusinessRecord.putIfAbsent(key, row);
        }
        return new ArrayList<>(byBusinessRecord.values());
    }

    // Resolves title/PI/sponsor/status for every AWARD/PROPOSAL row in
    // one set-based query per module (never one query per result) - see
    // AwardArchiveService.findSummariesForAwardNumbers and
    // ProposalArchiveRepository.findCurrentSummariesForNumbers. NEGOTIATION
    // and SUBAWARD semantic rows are intentionally left unenriched (out
    // of scope for this pass) and keep the bare-identifier presentation
    // they've always had.
    private List<GlobalSearchItemResponse> enrichSemanticResults(
            List<SemanticSearchRow> rows,
            String cid
    ) {
        List<String> awardNumbers = rows.stream()
                .filter(row -> "AWARD".equals(row.module()))
                .map(SemanticSearchRow::businessNumber)
                .distinct()
                .toList();
        List<String> proposalNumbers = rows.stream()
                .filter(row -> "PROPOSAL".equals(row.module()))
                .map(SemanticSearchRow::businessNumber)
                .distinct()
                .toList();

        // TC-042: each enrichment lookup is timed separately, with the
        // number of identifiers it was given - never the identifiers.
        Map<String, AwardSemanticSummaryRow> awardSummaries = awardNumbers.isEmpty()
                ? Map.of()
                : timingLog.time(
                        cid,
                        "SEMANTIC_ENRICH_AWARD",
                        () -> awardArchiveService
                                .findSummariesForAwardNumbers(awardNumbers).stream()
                                .collect(Collectors.toMap(
                                        AwardSemanticSummaryRow::awardNumber,
                                        summary -> summary,
                                        (first, second) -> first
                                )),
                        Map::size);
        Map<String, ProposalSemanticSummaryRow> proposalSummaries = proposalNumbers.isEmpty()
                ? Map.of()
                : timingLog.time(
                        cid,
                        "SEMANTIC_ENRICH_PROPOSAL",
                        () -> proposalArchiveRepository
                                .findCurrentSummariesForNumbers(proposalNumbers).stream()
                                // Per row: a semantic batch legitimately
                                // spans Proposals the caller may only partly
                                // see. The drop above removes rows left
                                // unenriched by this filter.
                                .filter(row -> proposalAccess
                                        .canSeeProposalNumber(row.proposalNumber()))
                                .collect(Collectors.toMap(
                                        ProposalSemanticSummaryRow::proposalNumber,
                                        summary -> summary,
                                        (first, second) -> first
                                )),
                        Map::size);

        /*
         * Negotiation and Subaward were previously left unenriched, so
         * their cards showed the identifier where the title belongs -
         * "713214" rather than what the Negotiation is about (QA
         * TC-041). Both are resolved the same set-based way as Award
         * and Proposal: one query per module for the whole page, never
         * one per result.
         *
         * The keys differ by module because the embedding does:
         * build_search_embedding.py stores document_number as the
         * business number for NEGOTIATION and subaward_code for
         * SUBAWARD.
         */
        List<String> negotiationDocumentNumbers = rows.stream()
                .filter(row -> "NEGOTIATION".equals(row.module()))
                .map(SemanticSearchRow::businessNumber)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<String> subawardCodes = rows.stream()
                .filter(row -> "SUBAWARD".equals(row.module()))
                .map(SemanticSearchRow::businessNumber)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<String, NegotiationSemanticSummaryRow> negotiationSummaries =
                negotiationDocumentNumbers.isEmpty()
                        ? Map.of()
                        : timingLog.time(
                                cid,
                                "SEMANTIC_ENRICH_NEGOTIATION",
                                () -> negotiationArchiveService
                                        .findSummariesForDocumentNumbers(
                                                negotiationDocumentNumbers)
                                        .stream()
                                        .collect(Collectors.toMap(
                                                NegotiationSemanticSummaryRow::documentNumber,
                                                summary -> summary,
                                                (first, second) -> first
                                        )),
                                Map::size);
        Map<String, SubawardSemanticSummaryRow> subawardSummaries = subawardCodes.isEmpty()
                ? Map.of()
                : timingLog.time(
                        cid,
                        "SEMANTIC_ENRICH_SUBAWARD",
                        () -> subawardArchiveService
                                .findActiveSummariesForCodes(subawardCodes).stream()
                                .collect(Collectors.toMap(
                                        SubawardSemanticSummaryRow::subawardCode,
                                        summary -> summary,
                                        (first, second) -> first
                                )),
                        Map::size);

        List<GlobalSearchItemResponse> mapped = new ArrayList<>(rows.size());
        for (SemanticSearchRow row : rows) {
            AwardSemanticSummaryRow awardSummary = awardSummaries.get(row.businessNumber());
            ProposalSemanticSummaryRow proposalSummary =
                    proposalSummaries.get(row.businessNumber());

            /*
             * A semantic AWARD or PROPOSAL row was read from a record that
             * exists, and the enrichment lookups filter to what the caller
             * may see. So an absent summary for a SCOPED caller means "not
             * authorised", not "no data", and the row must be DROPPED rather
             * than left unenriched: an unenriched row still carries the
             * business number and a module link, which is the disclosure the
             * enrichment filter existed to prevent.
             *
             * SUBAWARD joined them once the approved funding-inheritance
             * rule was wired: findActiveSummariesForCodes now filters by the
             * same predicate, so a missing Subaward summary under a scoped
             * caller means the same thing. It also covers the one case where
             * a code has no ACTIVE version - dropping rather than publishing
             * the code is the fail-closed answer, and the keyword leg still
             * finds whatever the caller is entitled to.
             *
             * All four business modules are now covered. IRB is excluded:
             * it has no approved record rule, so an absent summary there
             * genuinely means unenriched - the existing documented design.
             *
             * Gated on NOT being unrestricted, which matters: with
             * enforcement off, or for a Central caller, the summary lists are
             * unfiltered, so an absent summary means a real data gap and
             * today's behaviour is to keep the row. Dropping it there would
             * change enforcement-disabled behaviour, which this work must not
             * do. Unit tests cover exactly that case for both modules.
             */
            boolean scoped = !visibility.unrestricted();
            if (scoped && "AWARD".equals(row.module()) && awardSummary == null) {
                continue;
            }
            if (scoped && "PROPOSAL".equals(row.module()) && proposalSummary == null) {
                continue;
            }
            if (scoped && "SUBAWARD".equals(row.module())
                    && subawardSummaries.get(row.businessNumber()) == null) {
                continue;
            }
            /*
             * NEGOTIATION joined the drop list on 2026-10-27, when the
             * hybrid rule was approved and findSummariesForDocumentNumbers
             * became scoped. Before that it had no record rule, so an
             * absent summary genuinely meant unenriched. Now it means the
             * same as the other three: not authorised, and the row must go
             * rather than render with its document number and a link.
             */
            if (scoped && "NEGOTIATION".equals(row.module())
                    && negotiationSummaries.get(row.businessNumber()) == null) {
                continue;
            }
            // IRB leaves shared search entirely once enforcement is on -
            // keyed on enforcement, not on the caller, so Central drops it
            // too. See irbExcludedFromSharedSearch.
            if ("IRB".equals(row.module()) && irbExcludedFromSharedSearch()) {
                continue;
            }

            mapped.add(toGlobalSearchItem(
                    row,
                    awardSummary,
                    proposalSummary,
                    negotiationSummaries.get(row.businessNumber()),
                    subawardSummaries.get(row.businessNumber())
            ));
        }
        return mapped;
    }

    // awardSummary/proposalSummary are the enrichment rows resolved for
    // this row's businessNumber, if any (exactly one of the two is ever
    // non-null, since a row's module determines which map it was looked
    // up in) - either or both can legitimately be null: no row at all
    // when the business record is stale/removed since the embedding was
    // built (falls back to the pre-enrichment bare-identifier
    // presentation), or a found row whose own principalInvestigator/
    // sponsor columns are individually null (renders with those fields
    // simply absent). Never carries a raw similarity score, distance,
    // embedding, or model name - none of those leave the backend, per
    // GlobalSearchItemResponse's own contract.
    private GlobalSearchItemResponse toGlobalSearchItem(
            SemanticSearchRow row,
            AwardSemanticSummaryRow awardSummary,
            ProposalSemanticSummaryRow proposalSummary,
            NegotiationSemanticSummaryRow negotiationSummary,
            SubawardSemanticSummaryRow subawardSummary
    ) {
        String route = switch (row.module()) {
            case "AWARD" -> "/awards/" + row.canonicalFamilyId();
            case "PROPOSAL" -> "/proposals/dashboard/" + row.canonicalFamilyId();
            case "NEGOTIATION" -> "/negotiations/" + row.canonicalFamilyId();
            case "SUBAWARD" -> "/subawards/" + row.canonicalFamilyId();
            default -> null;
        };
        Long awardId = "AWARD".equals(row.module()) ? row.canonicalFamilyId() : null;

        boolean enriched = awardSummary != null
                || proposalSummary != null
                || negotiationSummary != null
                || subawardSummary != null;
        String title = row.businessNumber();
        String sponsor = null;
        String status = null;
        String principalInvestigator = null;
        if (awardSummary != null) {
            title = usableTitle(awardSummary.title(), title);
            sponsor = awardSummary.sponsor();
            status = awardSummary.status();
            principalInvestigator = awardSummary.principalInvestigator();
        } else if (proposalSummary != null) {
            title = usableTitle(proposalSummary.title(), title);
            sponsor = proposalSummary.sponsor();
            status = proposalSummary.status();
            principalInvestigator = proposalSummary.principalInvestigator();
        } else if (negotiationSummary != null) {
            title = usableTitle(negotiationSummary.title(), title);
            status = negotiationSummary.status();
            principalInvestigator = negotiationSummary.negotiator();
        } else if (subawardSummary != null) {
            title = usableTitle(subawardSummary.title(), title);
            sponsor = subawardSummary.sponsor();
            status = subawardSummary.status();
        }

        return new GlobalSearchItemResponse(
                row.module(),
                row.canonicalFamilyId(),
                row.businessNumber(),
                title,
                sponsor,
                status,
                null,
                enriched ? null : SEMANTIC_MATCHED_FIELD,
                enriched ? null : row.businessNumber(),
                route,
                null,
                awardId,
                null,
                null,
                MATCH_TYPE_RELATED,
                principalInvestigator
        );
    }

    // --- Ranking/dedup ---------------------------------------------------

    private int rankOf(GlobalSearchItemResponse item) {
        String matchedField = item.matchedField() == null ? "" : item.matchedField();

        return switch (matchedField) {
            case "Document Number", "Workflow Document Number", "Award ID" ->
                    RANK_EXACT_IDENTIFIER;
            case "CRC Protocol Number", "Protocol Number", "Study ID", "Award Number",
                    "Subaward Code", "Proposal Number" ->
                    RANK_EXACT_NUMBER;
            case "Funding Source", "Title", "PI", "Sponsor", "Lead Unit", "Principal Investigator",
                    "Organization", "Negotiator", "Agreement Type" ->
                    RANK_SUBSTRING;
            case "Semantic" -> RANK_SEMANTIC;
            default -> RANK_FALLBACK;
        };
    }

    // Dedupes by (module, recordId or awardId) - the same canonical
    // record/family never appears twice (e.g. an Award matched by both
    // the numeric-ID lookup and the broad text search, or a semantic
    // result that also surfaced structurally) - keyed AFTER the rank
    // sort, so the highest-ranked occurrence always wins and survives.
    //
    // sequenceNumber is deliberately NOT part of this key (dropped from
    // the prior version). Within a single domain's own dual lookup
    // paths, both occurrences already resolve to the same current row
    // and therefore the same sequence number, so this changes no
    // existing structured-only dedup behavior. Dropping it is what lets
    // a semantic result collapse into its structured twin: the semantic
    // branch has no live "current sequence number" to report (it only
    // knows canonicalFamilyId as of whenever the embedding was last
    // built, which can go stale between reloads), so requiring an exact
    // sequenceNumber match would silently defeat deduplication - see
    // GlobalSearchService's semantic-search integration.
    private List<GlobalSearchItemResponse> deduplicate(
            List<GlobalSearchItemResponse> items
    ) {
        Map<String, GlobalSearchItemResponse> byKey = new LinkedHashMap<>();
        for (GlobalSearchItemResponse item : items) {
            String key = item.module() + ":"
                    + (item.awardId() != null ? item.awardId() : item.recordId());
            byKey.putIfAbsent(key, item);
        }
        return new ArrayList<>(byKey.values());
    }

    /*
     * A resolved title, or the identifier we already had. Blank counts
     * as missing: a record whose title is an empty string would
     * otherwise render a card with no heading at all, which is worse
     * than showing its number (QA TC-041).
     */
    private static String usableTitle(String resolved, String fallback) {
        return resolved != null && !resolved.isBlank() ? resolved : fallback;
    }

}
