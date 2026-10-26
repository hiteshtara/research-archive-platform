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
    private final edu.bu.archive.application.authorization.ProposalRecordAccess recordAccess;

    public ProposalArchiveService(
            ProposalArchiveRepository repository,
            org.springframework.beans.factory.ObjectProvider<
                    edu.bu.archive.application.authorization.ProposalRecordAccess> recordAccessProvider,
            org.springframework.beans.factory.ObjectProvider<
                    edu.bu.archive.application.authorization.AuthorizationProperties>
                    authorizationProperties
    ) {
        edu.bu.archive.application.authorization.ProposalRecordAccess resolvedAccess =
                recordAccessProvider == null ? null : recordAccessProvider.getIfAvailable();
        this.recordAccess = resolvedAccess != null
                ? resolvedAccess
                : edu.bu.archive.application.authorization.ProposalRecordAccess.PERMIT_ALL;
        edu.bu.archive.application.authorization.ProposalRecordAccess
                .requireEnforceableWiring(this.recordAccess, authorizationProperties);
        this.repository = repository;
    }

    /*
     * Paged Proposal family search: free text and structured filters are
     * ANDed in SQL, so the page and totalElements always describe the
     * complete filtered result set rather than a capped first slice.
     */
    /**
     * Family list for the Proposal browse screen. Lives here rather than in
     * the controller so the scope predicate is applied: this endpoint
     * previously called the repository directly and bypassed every gate.
     */
    public List<ProposalFamilySummaryResponse> findFamilies(String query, int limit) {
        recordAccess.requireProvisioned();
        return repository.findFamilies(query, recordAccess.proposalScopeSql("ranked"), limit);
    }

    public PageResponse<ProposalFamilySummaryResponse> findFamilyPage(
            String query,
            ProposalSearchFilters filters,
            int page,
            int size
    ) {
        // DECISION 1: no provisioned access means an explicit problem, not a
        // silently empty list that reads as an empty archive.
        recordAccess.requireProvisioned();

        /*
         * Resolved ONCE and used by the count and the page, in the same
         * WHERE as the filters. Filtering the page in Java afterwards would
         * leak out-of-scope Proposals through totalElements and short pages.
         */
        edu.bu.archive.application.authorization.SqlFragment scope =
                recordAccess.proposalScopeSql("ranked");

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);
        long totalElements = repository.countFamilyPage(query, filters, scope);
        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(safePage, safeSize, totalElements);

        List<ProposalFamilySummaryResponse> content =
                repository.findFamilyPage(
                        query, filters, scope, safeSize, safePage * safeSize
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
        recordAccess.requireProposalNumber(proposalNumber);
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
        /*
         * Spans every version of the family, while requireProposalNumber
         * authorises only the latest. A caller cleared for the latest but
         * not an earlier one would otherwise read it here.
         */
        recordAccess.requireEveryProposalVersion(proposalNumber);
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
        recordAccess.requireProposalNumber(proposalNumber);
        String normalizedProposalNumber =
                requireExistingProposal(proposalNumber);

        return repository.findAwards(
                normalizedProposalNumber
        );
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
