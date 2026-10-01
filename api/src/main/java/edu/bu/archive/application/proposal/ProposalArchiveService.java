package edu.bu.archive.application.proposal;

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAwardResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalRowResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalWorkspaceResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.PaginationSupport;
import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ProposalArchiveService {

    private final ProposalArchiveRepository repository;

    private final edu.bu.archive.application.authorization.RecordVisibility visibility;

    public ProposalArchiveService(
            ProposalArchiveRepository repository
    ) {
        this(repository, edu.bu.archive.application.authorization.RecordVisibility.ALL);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProposalArchiveService(
            ProposalArchiveRepository repository,
            edu.bu.archive.application.authorization.RecordVisibility visibility
    ) {
        this.repository = repository;
        this.visibility = visibility;
    }

    /*
     * Paged Proposal family search: free text and structured filters are
     * ANDed in SQL, so the page and totalElements always describe the
     * complete filtered result set rather than a capped first slice.
     */
    public PageResponse<ProposalFamilySummaryResponse> findFamilyPage(
            String query,
            ProposalSearchFilters filters,
            int page,
            int size
    ) {
        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);
        long totalElements = repository.countFamilyPage(query, filters);
        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(safePage, safeSize, totalElements);

        List<ProposalFamilySummaryResponse> content =
                repository.findFamilyPage(
                        query, filters, safeSize, safePage * safeSize
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

    public ProposalWorkspaceResponse findWorkspace(
            String proposalNumber
    ) {
        String normalizedProposalNumber =
                normalizeProposalNumber(proposalNumber);

        ProposalRowResponse current =
                repository.findCurrent(normalizedProposalNumber)
                        .orElseThrow(() ->
                                new NoSuchElementException(
                                        "Proposal not found: "
                                                + normalizedProposalNumber
                                )
                        );

        return new ProposalWorkspaceResponse(
                normalizedProposalNumber,
                current
        );
    }

    public PageResponse<ProposalRowResponse> findVersionPage(
            String proposalNumber,
            int page,
            int size
    ) {
        String normalizedProposalNumber =
                normalizeProposalNumber(proposalNumber);

        if (repository.findCurrent(
                normalizedProposalNumber
        ).isEmpty()) {
            throw new NoSuchElementException(
                    "Proposal not found: "
                            + normalizedProposalNumber
            );
        }

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        if (!visibility.unrestricted()) {
            // Record authorization: only the versions the caller may open,
            // each decided on its own, with the count and paging computed
            // AFTER filtering.
            List<ProposalRowResponse> visible = repository
                    .findVersionRows(normalizedProposalNumber, Integer.MAX_VALUE, 0).stream()
                    .filter(row -> row.proposalId() != null && visibility.canSeeProposal(row.proposalId()))
                    .toList();
            return PaginationSupport.pageOf(visible, safePage, safeSize);
        }

        long totalElements = repository.countVersions(
                normalizedProposalNumber
        );

        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(
                        safePage,
                        safeSize,
                        totalElements
                );

        int offset = safePage * safeSize;

        List<ProposalRowResponse> content =
                repository.findVersionRows(
                        normalizedProposalNumber,
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

    public List<ProposalAwardResponse> findAwards(
            String proposalNumber
    ) {
        String normalizedProposalNumber =
                requireExistingProposal(proposalNumber);

        if (visibility.unrestricted()) {
            return repository.findAwards(normalizedProposalNumber).stream()
                    .filter(row -> visibility.canSeeAwardNumber(row.awardNumber()))
                    .toList();
        }
        // Record authorization: only links made on a Proposal version the
        // caller may open, and only Awards the caller may open (the exact
        // linked version and the family's current version).
        List<Long> visibleVersions = repository
                .findVersionRows(normalizedProposalNumber, Integer.MAX_VALUE, 0).stream()
                .map(ProposalRowResponse::proposalId)
                .filter(id -> id != null && visibility.canSeeProposal(id))
                .toList();
        return repository.findAwardsLinkedFromVersions(normalizedProposalNumber, visibleVersions).stream()
                .filter(row -> row.awardId() == null || visibility.canSeeAward(row.awardId()))
                .filter(row -> row.awardNumber() != null && visibility.canSeeAwardNumber(row.awardNumber()))
                .toList();
    }

    private String requireExistingProposal(
            String proposalNumber
    ) {
        String normalizedProposalNumber =
                normalizeProposalNumber(proposalNumber);

        if (repository.findCurrent(
                normalizedProposalNumber
        ).isEmpty()) {
            throw new NoSuchElementException(
                    "Proposal not found: "
                            + normalizedProposalNumber
            );
        }

        return normalizedProposalNumber;
    }

    private String normalizeProposalNumber(
            String proposalNumber
    ) {
        String normalized =
                proposalNumber == null
                        ? ""
                        : proposalNumber.trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Proposal number is required"
            );
        }

        return normalized;
    }
}
