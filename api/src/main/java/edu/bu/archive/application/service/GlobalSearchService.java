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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;

/*
 * Orchestrates Global Search as a fan-out over each domain's OWN search
 * implementation, rather than a single cross-domain SQL view - see
 * GlobalSearchRepository (IRB's own, unchanged ranking/ILIKE logic
 * against archive.v_global_search) and AwardArchiveService.search
 * (reused as-is, never duplicated). Adding a future domain means adding
 * one more fan-out branch here, not extending v_global_search or
 * touching IRB's/Award's own search logic.
 *
 * Both domain calls run concurrently (bounded by PER_DOMAIN_LIMIT each)
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

    private final GlobalSearchRepository irbSearchRepository;
    private final AwardArchiveService awardArchiveService;

    public GlobalSearchService(
            GlobalSearchRepository irbSearchRepository,
            AwardArchiveService awardArchiveService
    ) {
        this.irbSearchRepository = irbSearchRepository;
        this.awardArchiveService = awardArchiveService;
    }

    public GlobalSearchResponse search(String query) {
        String normalizedQuery = query == null ? "" : query.trim();

        CompletableFuture<List<GlobalSearchItemResponse>> irbFuture =
                CompletableFuture.supplyAsync(() ->
                        timed("IRB", () -> searchIrb(normalizedQuery)));
        CompletableFuture<List<GlobalSearchItemResponse>> awardFuture =
                CompletableFuture.supplyAsync(() ->
                        timed("AWARD", () -> searchAward(normalizedQuery)));

        List<String> failedModules = new ArrayList<>();
        List<GlobalSearchItemResponse> irbResults =
                joinOrRecordFailure(irbFuture, "IRB", failedModules);
        List<GlobalSearchItemResponse> awardResults =
                joinOrRecordFailure(awardFuture, "AWARD", failedModules);

        List<GlobalSearchItemResponse> merged =
                new ArrayList<>(irbResults.size() + awardResults.size());
        merged.addAll(irbResults);
        merged.addAll(awardResults);
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
                    null
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
                Boolean.TRUE
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

    // --- Ranking/dedup ---------------------------------------------------

    private int rankOf(GlobalSearchItemResponse item) {
        String matchedField = item.matchedField() == null ? "" : item.matchedField();

        return switch (matchedField) {
            case "Document Number", "Workflow Document Number", "Award ID" ->
                    RANK_EXACT_IDENTIFIER;
            case "CRC Protocol Number", "Protocol Number", "Study ID", "Award Number" ->
                    RANK_EXACT_NUMBER;
            case "Funding Source", "Title", "PI", "Sponsor", "Lead Unit", "Principal Investigator" ->
                    RANK_SUBSTRING;
            default -> RANK_FALLBACK;
        };
    }

    // Dedupes by (module, recordId or awardId, sequenceNumber) so the
    // same specific record/version never appears twice (e.g. an Award
    // matched by both the numeric-ID lookup and the broad text search) -
    // keyed AFTER the rank sort, so the highest-ranked occurrence always
    // wins and survives.
    private List<GlobalSearchItemResponse> deduplicate(
            List<GlobalSearchItemResponse> items
    ) {
        Map<String, GlobalSearchItemResponse> byKey = new LinkedHashMap<>();
        for (GlobalSearchItemResponse item : items) {
            String key = item.module() + ":"
                    + (item.awardId() != null ? item.awardId() : item.recordId()) + ":"
                    + item.sequenceNumber();
            byKey.putIfAbsent(key, item);
        }
        return new ArrayList<>(byKey.values());
    }
}
