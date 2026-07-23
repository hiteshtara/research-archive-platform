package edu.bu.archive.application.negotiation;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationActivityResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationPageResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationRowResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationUnassociatedDetailResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationWorkspaceResponse;
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
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(
                Math.max(size, 1),
                100
        );

        long totalElements = repository.countNegotiations(query);
        int totalPages = totalElements == 0
                ? 0
                : (int) Math.ceil(
                        (double) totalElements / safeSize
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
                totalPages,
                safePage == 0,
                totalPages == 0
                        || safePage >= totalPages - 1
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
