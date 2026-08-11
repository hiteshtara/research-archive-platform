package edu.bu.archive.application.award;

import edu.bu.archive.adapter.in.web.dto.award.AwardAmountHistoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAssociatedNegotiationResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetLineItemResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetPeriodResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetPersonnelResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardBudgetVersionResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentCategoryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardCreditSplitResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardDocumentNumberMatchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyPositionRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFundingProposalResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardFundingSubawardResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyEdgeRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyNodeResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardHierarchyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardIdentifierResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardNotepadEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonCreditSplitRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonUnitCreditSplitRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonUnitResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardPersonUnitRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardReportTermRecipientResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardReportTermRecipientRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardReportTermResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardReportTermRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionChildResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionChildRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSapTransmissionRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSponsorTermResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryCardRow;
import edu.bu.archive.adapter.in.web.dto.award.AwardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardTermsResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardWorkspaceResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyActionResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyDocumentResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyHistoryEntryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionHeaderRow;
import edu.bu.archive.adapter.in.web.dto.award.TimeAndMoneyTransactionResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.PaginationSupport;
import edu.bu.archive.adapter.out.persistence.AwardArchivedAttachment;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.adapter.out.persistence.AwardAttachmentStorage;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class AwardArchiveService {

    private final AwardArchiveRepository repository;
    private final AwardAttachmentStorage attachmentStorage;

    public AwardArchiveService(
            AwardArchiveRepository repository,
            AwardAttachmentStorage attachmentStorage
    ) {
        this.repository = repository;
        this.attachmentStorage = attachmentStorage;
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

    /*
     * Resolve-only: a caller from another domain (e.g. Institutional
     * Proposal's Funded Awards section) supplies the stable, human-
     * meaningful awardNumber and gets back the current version's
     * internal awardId to navigate with - never handed a raw awardId
     * by that other domain's own response payload.
     */
    public AwardIdentifierResponse resolveIdentifier(String awardNumber) {
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

        return new AwardIdentifierResponse(
                current.awardId(),
                current.awardNumber(),
                current.sequenceNumber()
        );
    }

    /*
     * The Funding Proposal(s) behind this Award - family-wide (every
     * award_id in this Award's whole award_number family), from
     * archive.award_funding_proposal - the bidirectional counterpart
     * to Institutional Proposal's own Funded Awards. One row per real
     * relationship, including inactive ones (never silently dropped -
     * see AwardFundingProposalResponse). navigableActiveProposalId
     * resolves each linked Proposal family to its own ACTIVE version,
     * server-side, so the API response carries the ID a client
     * navigates to directly.
     */
    public List<AwardFundingProposalResponse> findFundingProposals(
            long awardId
    ) {
        String awardNumber = requireAwardNumberForId(awardId);
        return repository.findFundingProposalRows(awardNumber);
    }

    /*
     * The Subaward(s) funded by this Award - family-wide, from
     * archive.subaward_funding - the bidirectional counterpart to
     * SubawardArchiveService.findFunding. See
     * AwardArchiveRepository.findFundingSubawardRows for the
     * resolution rules.
     */
    public List<AwardFundingSubawardResponse> findFundingSubawards(
            long awardId
    ) {
        String awardNumber = requireAwardNumberForId(awardId);
        return repository.findFundingSubawardRows(awardNumber);
    }

    /*
     * The Negotiation(s) associated with this Award - family-wide, the
     * bidirectional counterpart to NegotiationWorkspacePage's own
     * "Associated Record" link. See
     * AwardArchiveRepository.findAssociatedNegotiationRows for the
     * resolution rules.
     */
    public List<AwardAssociatedNegotiationResponse> findAssociatedNegotiations(
            long awardId
    ) {
        String awardNumber = requireAwardNumberForId(awardId);
        return repository.findAssociatedNegotiationRows(awardNumber);
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

    public AwardSearchResponse search(
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

        // Additive, unrelated to the family-level results above (never
        // scoped to is_primary_current) - an exact match against a real
        // workflow document number ranks ahead of any title/sponsor/etc
        // substring match by construction, since the frontend renders
        // exactDocumentMatch before results.content rather than merging
        // the two into one ordered list.
        AwardDocumentNumberMatchResponse exactDocumentMatch =
                repository.findExactWorkflowDocumentMatch(rawQuery)
                        .orElse(null);

        PageResponse<AwardSearchResultResponse> results = new PageResponse<>(
                content,
                safePage,
                safeSize,
                totalElements,
                pageMetadata.totalPages(),
                pageMetadata.first(),
                pageMetadata.last()
        );

        return new AwardSearchResponse(exactDocumentMatch, results);
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

    public PageResponse<AwardVersionSummaryResponse> findVersions(
            long awardId,
            int page,
            int size
    ) {
        String awardNumber = repository.findAwardNumberForId(awardId)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Award not found: " + awardId
                        )
                );

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        long totalElements = repository.countVersions(awardNumber);

        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(
                        safePage,
                        safeSize,
                        totalElements
                );

        int offset = safePage * safeSize;

        List<AwardVersionSummaryResponse> content =
                repository.findVersionSummaries(
                        awardNumber,
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

    public List<AwardPersonDetailResponse> findPeople(long awardId) {
        requireAwardNumberForId(awardId);

        List<AwardPersonRow> personRows = repository.findPersonRows(awardId);
        List<AwardPersonUnitRow> unitRows =
                repository.findPersonUnitRows(awardId);
        List<AwardPersonCreditSplitRow> personSplitRows =
                repository.findPersonCreditSplitRows(awardId);
        List<AwardPersonUnitCreditSplitRow> unitSplitRows =
                repository.findPersonUnitCreditSplitRows(awardId);

        Map<Long, List<AwardCreditSplitResponse>> unitSplitsByUnitId =
                new LinkedHashMap<>();
        for (AwardPersonUnitCreditSplitRow row : unitSplitRows) {
            unitSplitsByUnitId
                    .computeIfAbsent(
                            row.awardPersonUnitId(),
                            ignored -> new ArrayList<>()
                    )
                    .add(new AwardCreditSplitResponse(
                            row.invCreditTypeCode(),
                            row.credit()
                    ));
        }

        Map<Long, List<AwardPersonUnitResponse>> unitsByPersonId =
                new LinkedHashMap<>();
        for (AwardPersonUnitRow row : unitRows) {
            unitsByPersonId
                    .computeIfAbsent(
                            row.awardPersonId(),
                            ignored -> new ArrayList<>()
                    )
                    .add(new AwardPersonUnitResponse(
                            row.unitNumber(),
                            isAffirmative(row.leadUnitFlag()),
                            unitSplitsByUnitId.getOrDefault(
                                    row.awardPersonUnitId(),
                                    List.of()
                            )
                    ));
        }

        Map<Long, List<AwardCreditSplitResponse>> personSplitsByPersonId =
                new LinkedHashMap<>();
        for (AwardPersonCreditSplitRow row : personSplitRows) {
            personSplitsByPersonId
                    .computeIfAbsent(
                            row.awardPersonId(),
                            ignored -> new ArrayList<>()
                    )
                    .add(new AwardCreditSplitResponse(
                            row.invCreditTypeCode(),
                            row.credit()
                    ));
        }

        return personRows.stream()
                .map(row -> new AwardPersonDetailResponse(
                        row.awardPersonId(),
                        row.personId(),
                        row.fullName(),
                        row.contactRoleCode(),
                        row.keyPersonProjectRole(),
                        "PI".equalsIgnoreCase(
                                row.contactRoleCode() == null
                                        ? ""
                                        : row.contactRoleCode().trim()
                        ),
                        row.academicYearEffort(),
                        row.calendarYearEffort(),
                        row.summerEffort(),
                        row.totalEffort(),
                        unitsByPersonId.getOrDefault(
                                row.awardPersonId(),
                                List.of()
                        ),
                        personSplitsByPersonId.getOrDefault(
                                row.awardPersonId(),
                                List.of()
                        )
                ))
                .toList();
    }

    public PageResponse<AwardAmountHistoryResponse> findAmounts(
            long awardId,
            int page,
            int size
    ) {
        String awardNumber = requireAwardNumberForId(awardId);

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        long totalElements = repository.countAmountHistory(awardNumber);

        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(
                        safePage,
                        safeSize,
                        totalElements
                );

        int offset = safePage * safeSize;

        List<AwardAmountHistoryResponse> content =
                repository.findAmountHistory(awardNumber, safeSize, offset);

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
     * --- Time and Money (see docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md) ---
     *
     * Summary is scoped to this exact awardId (the version being
     * viewed) - consistent with findSummary, not family-wide.
     * Actions/History are family-wide, matching findAmounts's own
     * precedent.
     */

    public TimeAndMoneySummaryResponse findTimeAndMoneySummary(long awardId) {
        return repository.findTimeAndMoneySummary(awardId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Award not found: " + awardId
                ));
    }

    public PageResponse<TimeAndMoneyActionResponse> findTimeAndMoneyActions(
            long awardId,
            int page,
            int size
    ) {
        String awardNumber = requireAwardNumberForId(awardId);

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        long totalElements = repository.countTimeAndMoneyActions(awardNumber);

        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(safePage, safeSize, totalElements);

        int offset = safePage * safeSize;

        List<TimeAndMoneyActionResponse> content =
                repository.findTimeAndMoneyActions(
                        awardNumber,
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

    public PageResponse<TimeAndMoneyHistoryEntryResponse> findTimeAndMoneyHistory(
            long awardId,
            int page,
            int size
    ) {
        String awardNumber = requireAwardNumberForId(awardId);

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        // Same ledger findAmounts already counts (archive.award_amount_info
        // joined to archive.award_version by award_number) - reused
        // rather than duplicated.
        long totalElements = repository.countAmountHistory(awardNumber);

        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(safePage, safeSize, totalElements);

        int offset = safePage * safeSize;

        List<TimeAndMoneyHistoryEntryResponse> content =
                repository.findTimeAndMoneyHistory(
                        awardNumber,
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

    public TimeAndMoneyTransactionResponse findTimeAndMoneyTransaction(
            long awardId,
            long pendingTransactionId
    ) {
        requireAwardNumberForId(awardId);

        Optional<TimeAndMoneyTransactionHeaderRow> header =
                repository.findTimeAndMoneyTransactionHeader(
                        pendingTransactionId
                );
        List<TimeAndMoneyTransactionDetailResponse> details =
                repository.findTimeAndMoneyTransactionDetails(
                        pendingTransactionId
                );

        if (header.isEmpty() && details.isEmpty()) {
            throw new NoSuchElementException(
                    "Time and Money transaction not found: "
                            + pendingTransactionId
            );
        }

        // pending_transaction may no longer exist for an old,
        // already-processed transaction (see
        // TimeAndMoneyTransactionResponse's own Javadoc) - fall back to
        // transaction_detail's own timeAndMoneyDocumentNumber, which is
        // NOT NULL on every detail row, so the document number is
        // always available even when the header is not.
        String timeAndMoneyDocumentNumber = header
                .map(TimeAndMoneyTransactionHeaderRow::timeAndMoneyDocumentNumber)
                .orElseGet(() -> details.isEmpty()
                        ? null
                        : details.get(0).timeAndMoneyDocumentNumber());

        return new TimeAndMoneyTransactionResponse(
                pendingTransactionId,
                timeAndMoneyDocumentNumber,
                header.map(TimeAndMoneyTransactionHeaderRow::sourceAwardNumber)
                        .orElse(null),
                header.map(TimeAndMoneyTransactionHeaderRow::destinationAwardNumber)
                        .orElse(null),
                header.map(TimeAndMoneyTransactionHeaderRow::obligatedAmount)
                        .orElse(null),
                header.map(TimeAndMoneyTransactionHeaderRow::obligatedDirectAmount)
                        .orElse(null),
                header.map(TimeAndMoneyTransactionHeaderRow::obligatedIndirectAmount)
                        .orElse(null),
                header.map(TimeAndMoneyTransactionHeaderRow::anticipatedAmount)
                        .orElse(null),
                header.map(TimeAndMoneyTransactionHeaderRow::anticipatedDirectAmount)
                        .orElse(null),
                header.map(TimeAndMoneyTransactionHeaderRow::anticipatedIndirectAmount)
                        .orElse(null),
                header.map(TimeAndMoneyTransactionHeaderRow::comments)
                        .orElse(null),
                header.map(TimeAndMoneyTransactionHeaderRow::processedFlag)
                        .orElse(null),
                header.map(TimeAndMoneyTransactionHeaderRow::fandaDistributionPeriod)
                        .orElse(null),
                details
        );
    }

    public TimeAndMoneyDocumentResponse findTimeAndMoneyDocument(
            long awardId,
            String timeAndMoneyDocumentNumber
    ) {
        requireAwardNumberForId(awardId);

        return repository.findTimeAndMoneyDocument(timeAndMoneyDocumentNumber)
                .orElseThrow(() -> new NoSuchElementException(
                        "Time and Money document not found: "
                                + timeAndMoneyDocumentNumber
                ));
    }

    public AwardTermsResponse findTerms(long awardId) {
        requireAwardNumberForId(awardId);

        List<AwardSponsorTermResponse> sponsorTerms =
                repository.findSponsorTerms(awardId);
        List<AwardReportTermRow> reportTermRows =
                repository.findReportTermRows(awardId);
        List<AwardReportTermRecipientRow> recipientRows =
                repository.findReportTermRecipientRows(awardId);

        Map<Long, List<AwardReportTermRecipientResponse>>
                recipientsByReportTermId = new LinkedHashMap<>();
        for (AwardReportTermRecipientRow row : recipientRows) {
            recipientsByReportTermId
                    .computeIfAbsent(
                            row.awardReportTermId(),
                            ignored -> new ArrayList<>()
                    )
                    .add(new AwardReportTermRecipientResponse(
                            row.awardReportTermRecipientId(),
                            row.contactId(),
                            row.contactTypeCode(),
                            row.rolodexId(),
                            row.numberOfCopies()
                    ));
        }

        List<AwardReportTermResponse> reportTerms = reportTermRows.stream()
                .map(row -> new AwardReportTermResponse(
                        row.awardReportTermId(),
                        row.reportClassCode(),
                        row.reportCode(),
                        row.frequencyCode(),
                        row.frequencyBaseCode(),
                        row.ospDistributionCode(),
                        row.dueDate(),
                        recipientsByReportTermId.getOrDefault(
                                row.awardReportTermId(),
                                List.of()
                        )
                ))
                .toList();

        return new AwardTermsResponse(sponsorTerms, reportTerms);
    }

    public AwardCommentsResponse findComments(long awardId) {
        String awardNumber = requireAwardNumberForId(awardId);

        List<AwardCommentRow> rows = repository.findComments(awardNumber);
        List<AwardNotepadEntryResponse> notepadEntries =
                repository.findNotepadEntries(awardNumber);

        return new AwardCommentsResponse(
                groupCommentsByType(rows), notepadEntries
        );
    }

    /*
     * Groups the flat, comment-type-then-newest-to-oldest rows from
     * AwardArchiveRepository.findComments into one category per
     * screen_flag='Y' comment type. A type with zero real comments for
     * this Award family arrives as a single all-null-except-type row
     * (from findComments' LEFT JOIN) - filtered out here so its
     * category renders as "no comment recorded" (current=null,
     * history=empty) rather than a fake entry.
     */
    private static List<AwardCommentCategoryResponse> groupCommentsByType(
            List<AwardCommentRow> rows
    ) {
        Map<String, List<AwardCommentRow>> rowsByType = new LinkedHashMap<>();
        for (AwardCommentRow row : rows) {
            rowsByType
                    .computeIfAbsent(row.commentTypeCode(), key -> new ArrayList<>())
                    .add(row);
        }

        List<AwardCommentCategoryResponse> categories = new ArrayList<>();
        for (Map.Entry<String, List<AwardCommentRow>> entry : rowsByType.entrySet()) {
            List<AwardCommentRow> typeRows = entry.getValue();
            String description = typeRows.get(0).commentTypeDescription();

            List<AwardCommentRow> actualComments = typeRows.stream()
                    .filter(row -> row.awardCommentId() != null)
                    .toList();
            List<AwardCommentEntryResponse> history =
                    collapseConsecutiveIdenticalText(actualComments);
            AwardCommentEntryResponse current =
                    history.isEmpty() ? null : history.get(0);

            categories.add(new AwardCommentCategoryResponse(
                    entry.getKey(), description, current, history
            ));
        }
        return categories;
    }

    /*
     * Reproduces Kuali's own AwardCommentServiceImpl.filterAwardComment():
     * that method walks its BusinessObjectService.findMatching() results
     * in their natural (oldest-to-newest / insertion) order and, for each
     * run of consecutive identical comment text, keeps only the FIRST
     * (oldest) row - i.e. the version where that text was actually
     * introduced - silently dropping every later version that just
     * carried the same text forward unchanged. Confirmed against real
     * Award 100330-00001 data: General Comments' "*Converted Record"
     * text is copy-forward-identical on award_ids 877063 (2014-03-11)
     * through 3038231 (2021-09-20) - Kuali's real UI attributes that
     * text to its EARLIEST occurrence (877063/2014-03-11), not the
     * latest copy, which is why this method walks rowsNewestFirst in
     * reverse (oldest-to-newest) before collapsing, then reverses the
     * result back to newest-first for presentation (current = index 0).
     * A later row with merely similar (not identical) text, or an
     * identical text that recurs non-consecutively, is preserved as its
     * own distinct entry. No prefix/partial-text matching.
     */
    private static List<AwardCommentEntryResponse> collapseConsecutiveIdenticalText(
            List<AwardCommentRow> rowsNewestFirst
    ) {
        List<AwardCommentRow> rowsOldestFirst = new ArrayList<>(rowsNewestFirst);
        Collections.reverse(rowsOldestFirst);

        List<AwardCommentEntryResponse> historyOldestFirst = new ArrayList<>();
        String previousNormalizedText = null;
        boolean isFirst = true;

        for (AwardCommentRow row : rowsOldestFirst) {
            String normalizedText = row.comments() == null
                    ? null
                    : row.comments().trim();

            if (!isFirst && Objects.equals(normalizedText, previousNormalizedText)) {
                continue;
            }

            historyOldestFirst.add(new AwardCommentEntryResponse(
                    row.awardCommentId(),
                    row.awardId(),
                    row.sequenceNumber(),
                    row.workflowDocumentNumber(),
                    row.comments(),
                    row.sourceUpdateTimestamp(),
                    row.sourceUpdateUser()
            ));
            previousNormalizedText = normalizedText;
            isFirst = false;
        }
        Collections.reverse(historyOldestFirst);
        return historyOldestFirst;
    }

    public PageResponse<AwardSapTransmissionResponse> findSapTransmissions(
            long awardId,
            int page,
            int size
    ) {
        requireAwardNumberForId(awardId);

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        long totalElements = repository.countTransmissions(awardId);

        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(
                        safePage,
                        safeSize,
                        totalElements
                );

        int offset = safePage * safeSize;

        List<AwardSapTransmissionRow> rows =
                repository.findTransmissionRows(awardId, safeSize, offset);

        List<Long> transmissionIds = rows.stream()
                .map(AwardSapTransmissionRow::transmissionId)
                .toList();

        List<AwardSapTransmissionChildRow> childRows =
                repository.findTransmissionChildRows(transmissionIds);

        Map<Long, List<AwardSapTransmissionChildResponse>>
                childrenByTransmissionId = new LinkedHashMap<>();
        for (AwardSapTransmissionChildRow row : childRows) {
            childrenByTransmissionId
                    .computeIfAbsent(
                            row.transmissionId(),
                            ignored -> new ArrayList<>()
                    )
                    .add(new AwardSapTransmissionChildResponse(
                            row.transmissionChildId(),
                            row.awardNumber(),
                            row.sequenceNumber(),
                            row.parentDocumentNumber(),
                            row.childDocumentNumber(),
                            row.leadUnitNumber(),
                            row.childType(),
                            row.overheadKey(),
                            row.baseCode(),
                            row.offCampus()
                    ));
        }

        List<AwardSapTransmissionResponse> content = rows.stream()
                .map(row -> new AwardSapTransmissionResponse(
                        row.transmissionId(),
                        row.awardNumber(),
                        row.sequenceNumber(),
                        row.initiatorId(),
                        row.transmitterId(),
                        row.successIndicator(),
                        isAffirmative(row.successIndicator()),
                        row.transmissionDate(),
                        row.basisOfPaymentCode(),
                        row.accountTypeCode(),
                        row.sponsorCode(),
                        row.methodOfPaymentCode(),
                        row.documentNumber(),
                        row.sentData(),
                        row.returnedData(),
                        childrenByTransmissionId.getOrDefault(
                                row.transmissionId(),
                                List.of()
                        )
                ))
                .toList();

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

    public PageResponse<AwardAttachmentResponse> findAttachments(
            long awardId,
            int page,
            int size
    ) {
        requireAwardNumberForId(awardId);

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        long totalElements = repository.countAttachments(awardId);

        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(
                        safePage,
                        safeSize,
                        totalElements
                );

        int offset = safePage * safeSize;

        List<AwardAttachmentResponse> content =
                repository.findAttachments(awardId, safeSize, offset);

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

    public AwardAttachmentDownload downloadAttachment(
            long awardId,
            long attachmentId
    ) {
        requireAwardNumberForId(awardId);

        if (attachmentId <= 0) {
            throw new IllegalArgumentException(
                    "Attachment ID must be positive"
            );
        }

        long owner = repository.findAttachmentAwardId(attachmentId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Award attachment not found"
                ));

        if (owner != awardId) {
            throw new NoSuchElementException(
                    "Award attachment not found"
            );
        }

        AwardArchivedAttachment archived =
                repository.findArchivedAttachment(awardId, attachmentId)
                        .filter(row ->
                                "UPLOADED".equals(row.uploadStatus())
                                        && row.s3Bucket() != null
                                        && !row.s3Bucket().isBlank()
                                        && row.s3Key() != null
                                        && !row.s3Key().isBlank()
                        )
                        .orElseThrow(() -> new NoSuchElementException(
                                "Archived attachment not found"
                        ));

        AwardAttachmentStorage.StoredObject object =
                attachmentStorage.open(archived);

        return new AwardAttachmentDownload(
                safeFileName(archived.fileName(), attachmentId),
                archived.contentType() == null
                        || archived.contentType().isBlank()
                        ? "application/octet-stream"
                        : archived.contentType(),
                object.contentLength(),
                object.stream()
        );
    }

    private String safeFileName(String fileName, long attachmentId) {
        String candidate = fileName == null ? "" : fileName
                .replace('\\', '/')
                .replaceAll("[\\r\\n\\p{Cntrl}]", "");
        candidate = candidate.substring(candidate.lastIndexOf('/') + 1)
                .trim();
        return candidate.isEmpty()
                ? "attachment-" + attachmentId + ".bin"
                : candidate;
    }

    private boolean isAffirmative(String flag) {
        if (flag == null) {
            return false;
        }

        return switch (flag.trim().toUpperCase()) {
            case "Y", "YES", "TRUE", "1" -> true;
            default -> false;
        };
    }

    private String requireAwardNumberForId(long awardId) {
        return repository.findAwardNumberForId(awardId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Award not found: " + awardId
                ));
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

    /*
     * --- Budget (see docs/kuali-business-rules/Budget.md) -----------------
     *
     * Every Budget query here is scoped by awardNumber, bounded to
     * sequences <= the viewed Award's own sequence_number - proven
     * live against real Oracle/archive data that budget_version_number
     * is a family-wide monotonic counter, not per-award_id, mirroring
     * how Award.getBudgets()/AwardBudgetServiceImpl.getAllBudgetsForAward
     * actually resolve Budget history in real Kuali. Never scope a
     * Budget query to the exact awardId alone - that would hide most
     * of a family's real Budget history (see the design doc's own
     * incident: batch7-style silent under-scoping).
     */

    public AwardBudgetSummaryResponse findBudgetSummary(long awardId) {
        AwardFamilyPositionRow position = requireFamilyPosition(awardId);

        List<AwardBudgetRow> budgetsInScope = repository.findBudgetsInScope(
                position.awardNumber(), position.sequenceNumber()
        );
        AwardBudgetRow selected = selectArchiveBudget(budgetsInScope);

        if (selected == null) {
            return new AwardBudgetSummaryResponse(
                    awardId,
                    position.awardNumber(),
                    position.sequenceNumber(),
                    null, null, null, null, null, null, null, null, null, null, null, null
            );
        }

        return new AwardBudgetSummaryResponse(
                awardId,
                position.awardNumber(),
                position.sequenceNumber(),
                selected.budgetId(),
                selected.budgetVersionNumber(),
                selected.statusCode(),
                selected.statusDescription(),
                selected.workflowDocumentNumber(),
                selected.startDate(),
                selected.endDate(),
                selected.totalDirectCost(),
                selected.totalIndirectCost(),
                selected.totalCost(),
                selected.awardBudgetTotalCostLimit(),
                selected.budgetChangeTotalCostLimit()
        );
    }

    public PageResponse<AwardBudgetVersionResponse> findBudgetVersions(
            long awardId,
            int page,
            int size
    ) {
        AwardFamilyPositionRow position = requireFamilyPosition(awardId);

        List<AwardBudgetRow> budgetsInScope = repository.findBudgetsInScope(
                position.awardNumber(), position.sequenceNumber()
        );
        Long selectedBudgetId = Optional.ofNullable(
                selectArchiveBudget(budgetsInScope)
        ).map(AwardBudgetRow::budgetId).orElse(null);

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        List<AwardBudgetVersionResponse> allVersions = budgetsInScope.stream()
                .map(row -> new AwardBudgetVersionResponse(
                        row.budgetId(),
                        row.budgetVersionNumber(),
                        row.owningAwardId(),
                        row.owningAwardSequenceNumber(),
                        row.workflowDocumentNumber(),
                        row.statusCode(),
                        row.statusDescription(),
                        row.startDate(),
                        row.endDate(),
                        row.totalDirectCost(),
                        row.totalIndirectCost(),
                        row.totalCost(),
                        row.awardBudgetTotalCostLimit(),
                        row.budgetChangeTotalCostLimit(),
                        row.budgetId().equals(selectedBudgetId)
                ))
                .toList();

        long totalElements = allVersions.size();
        int fromIndex = Math.min(safePage * safeSize, allVersions.size());
        int toIndex = Math.min(fromIndex + safeSize, allVersions.size());

        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(safePage, safeSize, totalElements);

        return new PageResponse<>(
                allVersions.subList(fromIndex, toIndex),
                safePage,
                safeSize,
                totalElements,
                pageMetadata.totalPages(),
                pageMetadata.first(),
                pageMetadata.last()
        );
    }

    public List<AwardBudgetPeriodResponse> findBudgetPeriods(long awardId) {
        Long selectedBudgetId = requireSelectedBudgetId(awardId);

        if (selectedBudgetId == null) {
            return List.of();
        }

        return repository.findBudgetPeriods(selectedBudgetId);
    }

    public PageResponse<AwardBudgetLineItemResponse> findBudgetLineItems(
            long awardId,
            int page,
            int size
    ) {
        Long selectedBudgetId = requireSelectedBudgetId(awardId);

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        if (selectedBudgetId == null) {
            PaginationSupport.PageMetadata emptyMetadata =
                    PaginationSupport.metadata(safePage, safeSize, 0);
            return new PageResponse<>(
                    List.of(), safePage, safeSize, 0,
                    emptyMetadata.totalPages(), emptyMetadata.first(),
                    emptyMetadata.last()
            );
        }

        long totalElements = repository.countBudgetLineItems(selectedBudgetId);
        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(safePage, safeSize, totalElements);
        int offset = safePage * safeSize;

        List<AwardBudgetLineItemResponse> content = repository.findBudgetLineItems(
                selectedBudgetId, safeSize, offset
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

    public PageResponse<AwardBudgetPersonnelResponse> findBudgetPersonnel(
            long awardId,
            int page,
            int size
    ) {
        Long selectedBudgetId = requireSelectedBudgetId(awardId);

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        if (selectedBudgetId == null) {
            PaginationSupport.PageMetadata emptyMetadata =
                    PaginationSupport.metadata(safePage, safeSize, 0);
            return new PageResponse<>(
                    List.of(), safePage, safeSize, 0,
                    emptyMetadata.totalPages(), emptyMetadata.first(),
                    emptyMetadata.last()
            );
        }

        long totalElements = repository.countBudgetPersonnel(selectedBudgetId);
        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(safePage, safeSize, totalElements);
        int offset = safePage * safeSize;

        List<AwardBudgetPersonnelResponse> content = repository.findBudgetPersonnel(
                selectedBudgetId, safeSize, offset
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

    private Long requireSelectedBudgetId(long awardId) {
        AwardFamilyPositionRow position = requireFamilyPosition(awardId);
        List<AwardBudgetRow> budgetsInScope = repository.findBudgetsInScope(
                position.awardNumber(), position.sequenceNumber()
        );
        AwardBudgetRow selected = selectArchiveBudget(budgetsInScope);
        return selected == null ? null : selected.budgetId();
    }

    private AwardFamilyPositionRow requireFamilyPosition(long awardId) {
        return repository.findFamilyPositionForId(awardId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Award not found: " + awardId
                ));
    }

    /*
     * The archive-facing "selectedArchiveBudget" rule (see
     * docs/kuali-business-rules/Budget.md - deliberately NOT Kuali's
     * own live getCurrentBudget(), which targets transient in-progress
     * statuses that essentially never survive to a closed archive):
     * the highest budget_version_number with Posted status ('9');
     * if none, the highest budget_version_number that is not
     * Cancelled ('14'). rowsNewestFirst is already ordered by
     * budget_version_number DESC (see findBudgetsInScope), so the
     * first match within each filter is already the highest version
     * in that tier. Returns null when nothing qualifies (e.g. every
     * budget in scope is Cancelled) - a real, valid "no current
     * budget" state, not an error.
     */
    private static AwardBudgetRow selectArchiveBudget(
            List<AwardBudgetRow> rowsNewestFirst
    ) {
        return rowsNewestFirst.stream()
                .filter(row -> "9".equals(row.statusCode()))
                .findFirst()
                .or(() -> rowsNewestFirst.stream()
                        .filter(row -> !"14".equals(row.statusCode()))
                        .findFirst())
                .orElse(null);
    }
}
