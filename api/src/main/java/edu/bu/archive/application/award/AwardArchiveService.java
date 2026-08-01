package edu.bu.archive.application.award;

import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyEdgeRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyNodeResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryCardRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardWorkspaceResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.PaginationSupport;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class AwardArchiveService {

    private final AwardArchiveRepository repository;

    public AwardArchiveService(
            AwardArchiveRepository repository
    ) {
        this.repository = repository;
    }

    public AwardWorkspaceResponse findWorkspace(
            String awardNumber
    ) {
        String normalizedAwardNumber =
                normalizeAwardNumber(awardNumber);

        AwardRowResponse current =
                repository.findCurrent(normalizedAwardNumber)
                        .orElseThrow(() ->
                                new NoSuchElementException(
                                        "Award not found: "
                                                + normalizedAwardNumber
                                )
                        );

        return new AwardWorkspaceResponse(
                normalizedAwardNumber,
                current
        );
    }

    public PageResponse<AwardSequenceSummaryResponse> findSequencePage(
            String awardNumber,
            int page,
            int size
    ) {
        String normalizedAwardNumber =
                normalizeAwardNumber(awardNumber);

        if (repository.findCurrent(normalizedAwardNumber).isEmpty()) {
            throw new NoSuchElementException(
                    "Award not found: "
                            + normalizedAwardNumber
            );
        }

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        long totalElements =
                repository.countSequences(
                        normalizedAwardNumber
                );

        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(
                        safePage,
                        safeSize,
                        totalElements
                );

        int offset = safePage * safeSize;

        List<AwardSequenceSummaryResponse> content =
                repository.findSequenceSummaries(
                        normalizedAwardNumber,
                        safeSize,
                        offset
                );

        return new PageResponse<>(
                content,
                safePage,
                safeSize,
                totalElements,
                pageMetadata.totalPages(),
                pageMetadata.first(),
                pageMetadata.last()
        );
    }

    public AwardSequenceDetailResponse findSequence(
            String awardNumber,
            int sequenceNumber
    ) {
        String normalizedAwardNumber =
                normalizeAwardNumber(awardNumber);

        List<AwardRowResponse> rows =
                repository.findSequenceRows(
                        normalizedAwardNumber,
                        sequenceNumber
                );

        if (rows.isEmpty()) {
            throw new NoSuchElementException(
                    "Award sequence not found: "
                            + normalizedAwardNumber
                            + ", sequence "
                            + sequenceNumber
            );
        }

        boolean currentSequence =
                rows.stream()
                        .anyMatch(row ->
                                Boolean.TRUE.equals(
                                        row.current()
                                )
                        );

        return new AwardSequenceDetailResponse(
                normalizedAwardNumber,
                sequenceNumber,
                currentSequence,
                rows
        );
    }

    /*
     * Existing proof-of-concept response.
     * Keep temporarily until the React history tab uses pagination.
     */
    public AwardFamilyResponse findFamily(
            String awardNumber
    ) {
        String normalizedAwardNumber =
                normalizeAwardNumber(awardNumber);

        List<AwardRowResponse> rows =
                repository.findHistoryRows(
                        normalizedAwardNumber
                );

        if (rows.isEmpty()) {
            throw new NoSuchElementException(
                    "Award not found: "
                            + normalizedAwardNumber
            );
        }

        AwardRowResponse current = rows.stream()
                .filter(row ->
                        Boolean.TRUE.equals(
                                row.primaryCurrent()
                        )
                )
                .findFirst()
                .orElse(rows.getFirst());

        Map<Integer, List<AwardRowResponse>> groupedRows =
                new LinkedHashMap<>();

        for (AwardRowResponse row : rows) {
            groupedRows
                    .computeIfAbsent(
                            row.sequenceNumber(),
                            ignored -> new ArrayList<>()
                    )
                    .add(row);
        }

        List<AwardSequenceResponse> sequences =
                groupedRows.entrySet()
                        .stream()
                        .map(entry ->
                                new AwardSequenceResponse(
                                        entry.getKey(),
                                        entry.getValue()
                                                .stream()
                                                .anyMatch(row ->
                                                        Boolean.TRUE.equals(
                                                                row.current()
                                                        )
                                                ),
                                        List.copyOf(
                                                entry.getValue()
                                        )
                                )
                        )
                        .toList();

        return new AwardFamilyResponse(
                normalizedAwardNumber,
                current,
                sequences
        );
    }


    public List<
            edu.bu.archive.adapter.in.web.dto.award.AwardPersonResponse
            > findCurrentPeople(
                    String awardNumber
            ) {
        String normalizedAwardNumber =
                normalizeAwardNumber(awardNumber);

        if (repository.findCurrent(normalizedAwardNumber).isEmpty()) {
            throw new NoSuchElementException(
                    "Award not found: "
                            + normalizedAwardNumber
            );
        }

        return repository.findCurrentPeople(
                normalizedAwardNumber
        );
    }


    public List<
            edu.bu.archive.adapter.in.web.dto.award
                    .AwardAmountResponse
            > findCurrentAmounts(
                    String awardNumber
            ) {
        String normalizedAwardNumber =
                normalizeAwardNumber(awardNumber);

        if (repository.findCurrent(
                normalizedAwardNumber
        ).isEmpty()) {
            throw new NoSuchElementException(
                    "Award not found: "
                            + normalizedAwardNumber
            );
        }

        return repository.findCurrentAmounts(
                normalizedAwardNumber
        );
    }

    public List<
            edu.bu.archive.adapter.in.web.dto.award
                    .AwardProposalResponse
            > findCurrentProposals(
                    String awardNumber
            ) {
        String normalizedAwardNumber =
                normalizeAwardNumber(awardNumber);

        if (repository.findCurrent(
                normalizedAwardNumber
        ).isEmpty()) {
            throw new NoSuchElementException(
                    "Award not found: "
                            + normalizedAwardNumber
            );
        }

        return repository.findCurrentProposals(
                normalizedAwardNumber
        );
    }

    public edu.bu.archive.adapter.in.web.dto.award
            .AwardFundingResponse findCurrentFunding(
                    String awardNumber
            ) {
        String normalizedAwardNumber =
                normalizeAwardNumber(awardNumber);

        AwardRowResponse current =
                repository.findCurrent(
                        normalizedAwardNumber
                ).orElseThrow(() ->
                        new NoSuchElementException(
                                "Award not found: "
                                        + normalizedAwardNumber
                        )
                );

        List<
                edu.bu.archive.adapter.in.web.dto.award
                        .AwardProposalResponse
                > proposals =
                repository.findCurrentProposals(
                        normalizedAwardNumber
                );

        long activeProposalCount =
                proposals.stream()
                        .filter(proposal -> {
                            String flag =
                                    proposal.activeFlag();

                            if (flag == null) {
                                return false;
                            }

                            return switch (
                                    flag.trim().toUpperCase()
                            ) {
                                case "Y", "YES", "TRUE", "1" ->
                                        true;
                                default -> false;
                            };
                        })
                        .count();

        return new edu.bu.archive.adapter.in.web.dto.award
                .AwardFundingResponse(
                normalizedAwardNumber,
                current.sponsor(),
                current.primeSponsor(),
                current.sponsorAwardNumber(),
                current.leadUnit(),
                (long) proposals.size(),
                activeProposalCount
        );
    }

    public PageResponse<AwardSearchResultResponse> search(
            String query,
            int page,
            int size
    ) {
        String rawQuery = query == null ? "" : query.trim();
        String pattern = AwardSearchPattern.toLikePattern(rawQuery);

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        long totalElements =
                repository.countSearchAwards(pattern, rawQuery);

        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(
                        safePage,
                        safeSize,
                        totalElements
                );

        int offset = safePage * safeSize;

        List<AwardSearchResultResponse> content =
                repository.searchAwards(
                        pattern,
                        rawQuery,
                        safeSize,
                        offset
                );

        return new PageResponse<>(
                content,
                safePage,
                safeSize,
                totalElements,
                pageMetadata.totalPages(),
                pageMetadata.first(),
                pageMetadata.last()
        );
    }

    /*
     * Root resolution, edge/summary-card fetch, and tree assembly are
     * kept as three separate, targeted queries (never one massive join)
     * - see AwardArchiveRepository. Malformed historical data (cycles,
     * a hierarchy row whose own parent is missing, a hierarchy that has
     * no clean "no parent" root row) is handled defensively here rather
     * than assumed away.
     */
    public AwardHierarchyResponse findHierarchy(String awardNumber) {
        String normalizedAwardNumber = normalizeAwardNumber(awardNumber);

        java.util.Optional<String> hierarchyRoot =
                repository.findHierarchyRoot(normalizedAwardNumber);

        if (hierarchyRoot.isEmpty()) {
            List<AwardSummaryCardRow> selfCard =
                    repository.findSummaryCards(
                            List.of(normalizedAwardNumber)
                    );

            if (selfCard.isEmpty()) {
                throw new NoSuchElementException(
                        "Award not found: " + normalizedAwardNumber
                );
            }

            AwardHierarchyNodeResponse singleNode =
                    toHierarchyNode(selfCard.get(0), null, List.of());

            return new AwardHierarchyResponse(
                    normalizedAwardNumber,
                    normalizedAwardNumber,
                    singleNode,
                    List.of(normalizedAwardNumber)
            );
        }

        String rootAwardNumber = hierarchyRoot.get();

        List<AwardHierarchyEdgeRow> edges =
                repository.findHierarchyEdges(rootAwardNumber);

        Map<String, AwardHierarchyEdgeRow> edgeByAwardNumber =
                new LinkedHashMap<>();

        for (AwardHierarchyEdgeRow edge : edges) {
            edgeByAwardNumber.putIfAbsent(edge.awardNumber(), edge);
        }

        List<AwardSummaryCardRow> summaryCards =
                repository.findSummaryCards(
                        List.copyOf(edgeByAwardNumber.keySet())
                );

        Map<String, AwardSummaryCardRow> cardByAwardNumber =
                new LinkedHashMap<>();

        for (AwardSummaryCardRow card : summaryCards) {
            cardByAwardNumber.put(card.awardNumber(), card);
        }

        /*
         * parent_award_number is NOT NULL on archive.award_hierarchy (a
         * Kuali-sourced row always carries some value, even for the row
         * that represents the root itself - typically a self-reference
         * back to its own award_number). There is no "no parent" marker
         * to filter on, so a child edge is one whose parent points at a
         * *different* award_number that is itself part of this same
         * edge set - a self-referencing row is excluded so the root
         * never becomes its own child.
         */
        Map<String, List<AwardHierarchyEdgeRow>> childrenByParent =
                new LinkedHashMap<>();

        for (AwardHierarchyEdgeRow edge : edges) {
            String parent = edge.parentAwardNumber();

            if (!edge.awardNumber().equals(parent)
                    && edgeByAwardNumber.containsKey(parent)) {
                childrenByParent
                        .computeIfAbsent(
                                parent,
                                ignored -> new ArrayList<>()
                        )
                        .add(edge);
            }
        }

        /*
         * The root is identified by root_award_number, not by an absent
         * parent (there is none). Fall back to the requested award's
         * own edge only when the hierarchy's own root row wasn't
         * returned in this edge set at all - malformed historical data,
         * per the extraction query's own note that parent/root/
         * originating award numbers may point outside the loaded batch.
         */
        AwardHierarchyEdgeRow rootEdge =
                java.util.Optional.ofNullable(
                        edgeByAwardNumber.get(rootAwardNumber)
                )
                .or(() ->
                        java.util.Optional.ofNullable(
                                edgeByAwardNumber.get(
                                        normalizedAwardNumber
                                )
                        )
                )
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Award not found: "
                                        + normalizedAwardNumber
                        )
                );

        Set<String> visited = new HashSet<>();

        AwardHierarchyNodeResponse rootNode = buildHierarchyNode(
                rootEdge,
                cardByAwardNumber,
                childrenByParent,
                visited
        );

        // The resolved root's own parent_award_number (a self-reference
        // or a pointer outside this tree) isn't a meaningful "parent" at
        // the top of the rendered tree.
        AwardHierarchyNodeResponse rootNodeWithoutParent =
                new AwardHierarchyNodeResponse(
                        rootNode.awardNumber(),
                        rootNode.awardId(),
                        rootNode.latestSequenceNumber(),
                        null,
                        rootNode.active(),
                        rootNode.title(),
                        rootNode.status(),
                        rootNode.principalInvestigator(),
                        rootNode.sponsor(),
                        rootNode.leadUnit(),
                        rootNode.currentObligatedAmount(),
                        rootNode.children()
                );

        List<String> selectedAwardPath = buildSelectedAwardPath(
                normalizedAwardNumber,
                edgeByAwardNumber
        );

        return new AwardHierarchyResponse(
                rootEdge.awardNumber(),
                normalizedAwardNumber,
                rootNodeWithoutParent,
                selectedAwardPath
        );
    }

    public AwardSummaryResponse findSummary(long awardId) {
        return repository.findSummaryByAwardId(awardId)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Award not found: " + awardId
                        )
                );
    }

    public List<AwardVersionSummaryResponse> findVersions(long awardId) {
        String awardNumber = repository.findAwardNumberForId(awardId)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Award not found: " + awardId
                        )
                );

        return repository.findVersionSummaries(awardNumber);
    }

    private AwardHierarchyNodeResponse buildHierarchyNode(
            AwardHierarchyEdgeRow edge,
            Map<String, AwardSummaryCardRow> cardByAwardNumber,
            Map<String, List<AwardHierarchyEdgeRow>> childrenByParent,
            Set<String> visited
    ) {
        String awardNumber = edge.awardNumber();

        // A repeat visit means the historical hierarchy data contains a
        // cycle; stop descending rather than recursing forever.
        if (!visited.add(awardNumber)) {
            return toHierarchyNode(
                    cardByAwardNumber.get(awardNumber),
                    edge,
                    List.of()
            );
        }

        List<AwardHierarchyNodeResponse> children =
                childrenByParent
                        .getOrDefault(awardNumber, List.of())
                        .stream()
                        .map(childEdge ->
                                buildHierarchyNode(
                                        childEdge,
                                        cardByAwardNumber,
                                        childrenByParent,
                                        visited
                                )
                        )
                        .toList();

        return toHierarchyNode(
                cardByAwardNumber.get(awardNumber),
                edge,
                children
        );
    }

    private AwardHierarchyNodeResponse toHierarchyNode(
            AwardSummaryCardRow card,
            AwardHierarchyEdgeRow edge,
            List<AwardHierarchyNodeResponse> children
    ) {
        boolean active = edge == null
                || !"N".equalsIgnoreCase(
                        edge.active() == null ? "Y" : edge.active().trim()
                );

        String parentAwardNumber =
                edge == null ? null : edge.parentAwardNumber();

        // A hierarchy edge with no matching is_primary_current
        // award_version row is a real, if unusual, archive state
        // (e.g. a superseded or removed award still referenced by an
        // older hierarchy row) - surface a bare node rather than
        // dropping it from the tree.
        if (card == null) {
            String awardNumber =
                    edge == null ? null : edge.awardNumber();

            return new AwardHierarchyNodeResponse(
                    awardNumber,
                    null,
                    null,
                    parentAwardNumber,
                    active,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    children
            );
        }

        return new AwardHierarchyNodeResponse(
                card.awardNumber(),
                card.awardId(),
                card.latestSequenceNumber(),
                parentAwardNumber,
                active,
                card.title(),
                card.status(),
                card.principalInvestigator(),
                card.sponsor(),
                card.leadUnit(),
                card.currentObligatedAmount(),
                children
        );
    }

    private List<String> buildSelectedAwardPath(
            String requestedAwardNumber,
            Map<String, AwardHierarchyEdgeRow> edgeByAwardNumber
    ) {
        AwardHierarchyEdgeRow startEdge =
                edgeByAwardNumber.get(requestedAwardNumber);

        if (startEdge == null) {
            return List.of(requestedAwardNumber);
        }

        List<String> ascending = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        String current = requestedAwardNumber;
        AwardHierarchyEdgeRow edge = startEdge;

        while (edge != null && visited.add(current)) {
            ascending.add(current);

            String parent = edge.parentAwardNumber();

            if (parent == null || parent.equals(current)) {
                break;
            }

            current = parent;
            edge = edgeByAwardNumber.get(current);
        }

        Collections.reverse(ascending);

        return List.copyOf(ascending);
    }

    private String normalizeAwardNumber(
            String awardNumber
    ) {
        String normalized =
                awardNumber == null
                        ? ""
                        : awardNumber.trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Award number is required"
            );
        }

        return normalized;
    }
}
