package edu.bu.archive.application.negotiation;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationActivityResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationPageResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationRowResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationUnassociatedDetailResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationWorkspaceResponse;
import edu.bu.archive.adapter.in.web.dto.PaginationSupport;
import edu.bu.archive.adapter.out.persistence.NegotiationArchiveRepository;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class NegotiationArchiveService {

    private final NegotiationArchiveRepository repository;

    public NegotiationArchiveService(
            NegotiationArchiveRepository repository
    ) {
        this.repository = repository;
    }

    public NegotiationPageResponse findPage(
            String query,
            int page,
            int size
    ) {
        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        long totalElements = repository.countNegotiations(query);
        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(
                        safePage,
                        safeSize,
                        totalElements
                );
        int offset = safePage * safeSize;

        List<NegotiationSummaryResponse> content =
                repository.findNegotiations(
                        query,
                        safeSize,
                        offset
                );

        return new NegotiationPageResponse(
                content,
                safePage,
                safeSize,
                totalElements,
                pageMetadata.totalPages(),
                pageMetadata.first(),
                pageMetadata.last()
        );
    }

    public NegotiationWorkspaceResponse findWorkspace(
            long negotiationId
    ) {
        NegotiationRowResponse current = requireNegotiation(
                negotiationId
        );

        return new NegotiationWorkspaceResponse(
                negotiationId,
                current
        );
    }

    public List<NegotiationActivityResponse> findActivities(
            long negotiationId
    ) {
        requireNegotiation(negotiationId);
        return repository.findActivities(negotiationId);
    }

    public List<NegotiationCustomDataResponse> findCustomData(
            long negotiationId
    ) {
        requireNegotiation(negotiationId);
        return repository.findCustomData(negotiationId);
    }

    public List<NegotiationNotificationResponse> findNotifications(
            long negotiationId
    ) {
        requireNegotiation(negotiationId);
        return repository.findNotifications(negotiationId);
    }

    public List<NegotiationUnassociatedDetailResponse>
            findUnassociatedDetails(
                    long negotiationId
            ) {
        requireNegotiation(negotiationId);
        return repository.findUnassociatedDetails(negotiationId);
    }

    private NegotiationRowResponse requireNegotiation(
            long negotiationId
    ) {
        if (negotiationId <= 0) {
            throw new IllegalArgumentException(
                    "Negotiation ID must be positive"
            );
        }

        return repository.findById(negotiationId)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Negotiation not found: "
                                        + negotiationId
                        )
                );
    }
}
