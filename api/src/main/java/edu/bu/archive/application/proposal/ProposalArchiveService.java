package edu.bu.archive.application.proposal;

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAwardResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalPersonResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalRowResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalVersionPageResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalWorkspaceResponse;
import edu.bu.archive.adapter.in.web.dto.PaginationSupport;
import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ProposalArchiveService {

    private final ProposalArchiveRepository repository;

    public ProposalArchiveService(
            ProposalArchiveRepository repository
    ) {
        this.repository = repository;
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

    public ProposalVersionPageResponse findVersionPage(
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

        return new ProposalVersionPageResponse(
                content,
                safePage,
                safeSize,
                totalElements,
                pageMetadata.totalPages(),
                pageMetadata.first(),
                pageMetadata.last()
        );
    }

    public List<ProposalPersonResponse> findCurrentPeople(
            String proposalNumber
    ) {
        String normalizedProposalNumber =
                requireExistingProposal(proposalNumber);

        return repository.findCurrentPeople(
                normalizedProposalNumber
        );
    }

    public List<ProposalAwardResponse> findAwards(
            String proposalNumber
    ) {
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
