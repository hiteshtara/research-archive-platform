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
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;
import edu.bu.archive.adapter.out.persistence.AwardSemanticSummaryRow;
import edu.bu.archive.adapter.out.persistence.GlobalSearchRepository;
import edu.bu.archive.adapter.out.persistence.IrbGlobalSearchRow;
import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;
import edu.bu.archive.adapter.out.persistence.ProposalSemanticSummaryRow;
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
import java.util.NoSuchElementException;
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

    public GlobalSearchService(
            GlobalSearchRepository irbSearchRepository,
            AwardArchiveService awardArchiveService,
            NegotiationArchiveService negotiationArchiveService,
            SubawardArchiveService subawardArchiveService,
            ProposalArchiveRepository proposalArchiveRepository,
            SemanticSearchRepository semanticSearchRepository,
            SemanticSearchProperties semanticSearchProperties,
            ObjectProvider<EmbeddingProvider> embeddingProviderObjectProvider
    ) {
        this.irbSearchRepository = irbSearchRepository;
        this.awardArchiveService = awardArchiveService;
        this.negotiationArchiveService = negotiationArchiveService;
        this.subawardArchiveService = subawardArchiveService;
        this.proposalArchiveRepository = proposalArchiveRepository;
        this.semanticSearchRepository = semanticSearchRepository;
        this.semanticSearchProperties = semanticSearchProperties;
        this.embeddingProviderObjectProvider = embeddingProviderObjectProvider;
    }

    public GlobalSearchResponse search(String query) {
        String normalizedQuery = query == null ? "" : query.trim();

        CompletableFuture<List<GlobalSearchItemResponse>> irbFuture =
                CompletableFuture.supplyAsync(() ->
                        timed("IRB", () -> searchIrb(normalizedQuery)));
        CompletableFuture<List<GlobalSearchItemResponse>> awardFuture =
                CompletableFuture.supplyAsync(() ->
                        timed("AWARD", () -> searchAward(normalizedQuery)));
        CompletableFuture<List<GlobalSearchItemResponse>> negotiationFuture =
                CompletableFuture.supplyAsync(() ->
                        timed("NEGOTIATION", () -> searchNegotiation(normalizedQuery)));
        CompletableFuture<List<GlobalSearchItemResponse>> subawardFuture =
                CompletableFuture.supplyAsync(() ->
                        timed("SUBAWARD", () -> searchSubaward(normalizedQuery)));
        CompletableFuture<List<GlobalSearchItemResponse>> proposalFuture =
                CompletableFuture.supplyAsync(() ->
                        timed("PROPOSAL", () -> searchProposal(normalizedQuery)));

        // Semantic search is a strictly optional 6th input - see the
        // class comment. It is never started at all (no Bedrock call,
        // no pgvector query) when the flag is off or the query already
        // looks like an exact identifier, since structured search alone
        // is sufficient for those and semantic search would only add
        // latency for no benefit.
        boolean semanticEligible = semanticSearchProperties.isEnabled()
                && !LikelyIdentifierDetector.looksLikeIdentifier(normalizedQuery);
        CompletableFuture<List<GlobalSearchItemResponse>> semanticFuture =
                semanticEligible
                        ? CompletableFuture.supplyAsync(() ->
                                timed("SEMANTIC", () -> searchSemantic(normalizedQuery)))
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

        return new GlobalSearchResponse(
                normalizedQuery,
                deduplicated.size(),
                deduplicated,
                failedModules
        );
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

    // --- IRB -----------------------------------------------------------

    private List<GlobalSearchItemResponse> searchIrb(String query) {
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

    private List<GlobalSearchItemResponse> searchAward(String query) {
        List<GlobalSearchItemResponse> mapped = new ArrayList<>();

        if (query.matches("\\d+")) {
            findAwardById(Long.parseLong(query)).ifPresent(mapped::add);
        }

        AwardSearchResponse response =
                awardArchiveService.search(query, 0, PER_DOMAIN_LIMIT);

        if (response.exactDocumentMatch() != null) {
            mapped.add(toGlobalSearchItem(response.exactDocumentMatch()));
        }

        for (AwardSearchResultResponse result : response.results().content()) {
            mapped.add(toGlobalSearchItem(result, query));
        }

        return mapped;
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
            String query
    ) {
        String matchedField = awardMatchedField(result, query);

        return new GlobalSearchItemResponse(
                "AWARD",
                result.awardId(),
                result.awardNumber(),
                result.title(),
                result.sponsor(),
                result.status(),
                null,
                matchedField,
                awardMatchedValue(result, matchedField),
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
    private String awardMatchedField(AwardSearchResultResponse result, String query) {
        String normalizedQuery = query.toLowerCase();

        if (result.awardNumber() != null
                && result.awardNumber().toLowerCase().contains(normalizedQuery)) {
            return "Award Number";
        }
        if (result.title() != null
                && result.title().toLowerCase().contains(normalizedQuery)) {
            return "Title";
        }
        if (result.sponsor() != null
                && result.sponsor().toLowerCase().contains(normalizedQuery)) {
            return "Sponsor";
        }
        if (result.leadUnit() != null
                && result.leadUnit().toLowerCase().contains(normalizedQuery)) {
            return "Lead Unit";
        }
        if (result.principalInvestigator() != null
                && result.principalInvestigator().toLowerCase().contains(normalizedQuery)) {
            return "Principal Investigator";
        }
        return "Award";
    }

    private String awardMatchedValue(
            AwardSearchResultResponse result,
            String matchedField
    ) {
        return switch (matchedField) {
            case "Award Number" -> result.awardNumber();
            case "Title" -> result.title();
            case "Sponsor" -> result.sponsor();
            case "Lead Unit" -> result.leadUnit();
            case "Principal Investigator" -> result.principalInvestigator();
            default -> result.awardNumber();
        };
    }

    // --- Negotiation -------------------------------------------------------

    private List<GlobalSearchItemResponse> searchNegotiation(String query) {
        PageResponse<NegotiationSummaryResponse> page =
                negotiationArchiveService.findPage(query, 0, PER_DOMAIN_LIMIT);

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
    private String negotiationMatchedField(
            NegotiationSummaryResponse result,
            String query
    ) {
        String normalizedQuery = query.toLowerCase();

        if (result.documentNumber() != null
                && result.documentNumber().toLowerCase().contains(normalizedQuery)) {
            return "Document Number";
        }
        if (result.negotiationStatusDescription() != null
                && result.negotiationStatusDescription().toLowerCase()
                        .contains(normalizedQuery)) {
            return "Status";
        }
        if (result.negotiationAgreementTypeDescription() != null
                && result.negotiationAgreementTypeDescription().toLowerCase()
                        .contains(normalizedQuery)) {
            return "Agreement Type";
        }
        if (result.negotiatorFullName() != null
                && result.negotiatorFullName().toLowerCase().contains(normalizedQuery)) {
            return "Negotiator";
        }
        return "Negotiation";
    }

    private String negotiationMatchedValue(
            NegotiationSummaryResponse result,
            String matchedField
    ) {
        return switch (matchedField) {
            case "Document Number" -> result.documentNumber();
            case "Status" -> result.negotiationStatusDescription();
            case "Agreement Type" -> result.negotiationAgreementTypeDescription();
            case "Negotiator" -> result.negotiatorFullName();
            default -> result.documentNumber();
        };
    }

    // --- Subaward ----------------------------------------------------------

    private List<GlobalSearchItemResponse> searchSubaward(String query) {
        var page = subawardArchiveService.findPage(query, 0, PER_DOMAIN_LIMIT);

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

    // findSubawards' own SQL doesn't expose which column matched, same
    // heuristic approach as awardMatchedField - see
    // SubawardArchiveRepository.findSubawards.
    private String subawardMatchedField(SubawardSummaryResponse result, String query) {
        String normalizedQuery = query.toLowerCase();

        if (result.subawardCode() != null
                && result.subawardCode().toLowerCase().contains(normalizedQuery)) {
            return "Subaward Code";
        }
        if (result.documentNumber() != null
                && result.documentNumber().toLowerCase().contains(normalizedQuery)) {
            return "Document Number";
        }
        if (result.title() != null
                && result.title().toLowerCase().contains(normalizedQuery)) {
            return "Title";
        }
        if (result.organizationId() != null
                && result.organizationId().toLowerCase().contains(normalizedQuery)) {
            return "Organization";
        }
        return "Subaward";
    }

    private String subawardMatchedValue(
            SubawardSummaryResponse result,
            String matchedField
    ) {
        return switch (matchedField) {
            case "Subaward Code" -> result.subawardCode();
            case "Document Number" -> result.documentNumber();
            case "Title" -> result.title();
            case "Organization" -> result.organizationId();
            default -> result.subawardCode();
        };
    }

    // --- Proposal ------------------------------------------------------

    private List<GlobalSearchItemResponse> searchProposal(String query) {
        List<ProposalFamilySummaryResponse> results =
                proposalArchiveRepository.findFamilies(query, PER_DOMAIN_LIMIT);

        List<GlobalSearchItemResponse> mapped = new ArrayList<>(results.size());
        for (ProposalFamilySummaryResponse result : results) {
            mapped.add(toGlobalSearchItem(result, query));
        }
        return mapped;
    }

    private GlobalSearchItemResponse toGlobalSearchItem(
            ProposalFamilySummaryResponse result,
            String query
    ) {
        String matchedField = proposalMatchedField(result, query);
        Long currentProposalId = result.currentProposalId();

        return new GlobalSearchItemResponse(
                "PROPOSAL",
                currentProposalId,
                result.proposalNumber(),
                result.title(),
                result.principalInvestigator(),
                result.status(),
                null,
                matchedField,
                proposalMatchedValue(result, matchedField),
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

    // findFamilies' own SQL doesn't expose which column matched, same
    // heuristic approach as awardMatchedField - see
    // ProposalArchiveRepository.findFamilies.
    private String proposalMatchedField(
            ProposalFamilySummaryResponse result,
            String query
    ) {
        String normalizedQuery = query.toLowerCase();

        if (result.proposalNumber() != null
                && result.proposalNumber().toLowerCase().contains(normalizedQuery)) {
            return "Proposal Number";
        }
        if (result.title() != null
                && result.title().toLowerCase().contains(normalizedQuery)) {
            return "Title";
        }
        if (result.sponsorName() != null
                && result.sponsorName().toLowerCase().contains(normalizedQuery)) {
            return "Sponsor";
        }
        if (result.leadUnitName() != null
                && result.leadUnitName().toLowerCase().contains(normalizedQuery)) {
            return "Lead Unit";
        }
        if (result.principalInvestigator() != null
                && result.principalInvestigator().toLowerCase().contains(normalizedQuery)) {
            return "Principal Investigator";
        }
        return "Proposal";
    }

    private String proposalMatchedValue(
            ProposalFamilySummaryResponse result,
            String matchedField
    ) {
        return switch (matchedField) {
            case "Proposal Number" -> result.proposalNumber();
            case "Title" -> result.title();
            case "Sponsor" -> result.sponsorName();
            case "Lead Unit" -> result.leadUnitName();
            case "Principal Investigator" -> result.principalInvestigator();
            default -> result.proposalNumber();
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

    private List<GlobalSearchItemResponse> searchSemantic(String query) {
        EmbeddingProvider embeddingProvider =
                embeddingProviderObjectProvider.getIfAvailable();
        if (embeddingProvider == null) {
            return List.of();
        }

        float[] queryEmbedding = embeddingProvider.embed(query);
        List<SemanticSearchRow> candidates =
                semanticSearchRepository.findNearest(queryEmbedding, SEMANTIC_CANDIDATE_LIMIT);

        List<SemanticSearchRow> deduped = dedupeByBusinessRecord(candidates);

        int topK = Math.min(semanticSearchProperties.getTopK(), SEMANTIC_MAX_RESULTS);
        if (deduped.size() > topK) {
            deduped = deduped.subList(0, topK);
        }

        return enrichSemanticResults(deduped);
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
            List<SemanticSearchRow> rows
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

        Map<String, AwardSemanticSummaryRow> awardSummaries = awardNumbers.isEmpty()
                ? Map.of()
                : awardArchiveService.findSummariesForAwardNumbers(awardNumbers).stream()
                        .collect(Collectors.toMap(
                                AwardSemanticSummaryRow::awardNumber,
                                summary -> summary,
                                (first, second) -> first
                        ));
        Map<String, ProposalSemanticSummaryRow> proposalSummaries = proposalNumbers.isEmpty()
                ? Map.of()
                : proposalArchiveRepository.findCurrentSummariesForNumbers(proposalNumbers).stream()
                        .collect(Collectors.toMap(
                                ProposalSemanticSummaryRow::proposalNumber,
                                summary -> summary,
                                (first, second) -> first
                        ));

        List<GlobalSearchItemResponse> mapped = new ArrayList<>(rows.size());
        for (SemanticSearchRow row : rows) {
            mapped.add(toGlobalSearchItem(
                    row,
                    awardSummaries.get(row.businessNumber()),
                    proposalSummaries.get(row.businessNumber())
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
            ProposalSemanticSummaryRow proposalSummary
    ) {
        String route = switch (row.module()) {
            case "AWARD" -> "/awards/" + row.canonicalFamilyId();
            case "PROPOSAL" -> "/proposals/dashboard/" + row.canonicalFamilyId();
            case "NEGOTIATION" -> "/negotiations/" + row.canonicalFamilyId();
            case "SUBAWARD" -> "/subawards/" + row.canonicalFamilyId();
            default -> null;
        };
        Long awardId = "AWARD".equals(row.module()) ? row.canonicalFamilyId() : null;

        boolean enriched = awardSummary != null || proposalSummary != null;
        String title = row.businessNumber();
        String sponsor = null;
        String status = null;
        String principalInvestigator = null;
        if (awardSummary != null) {
            title = awardSummary.title() != null ? awardSummary.title() : title;
            sponsor = awardSummary.sponsor();
            status = awardSummary.status();
            principalInvestigator = awardSummary.principalInvestigator();
        } else if (proposalSummary != null) {
            title = proposalSummary.title() != null ? proposalSummary.title() : title;
            sponsor = proposalSummary.sponsor();
            status = proposalSummary.status();
            principalInvestigator = proposalSummary.principalInvestigator();
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
}
