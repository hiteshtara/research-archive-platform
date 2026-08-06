package edu.bu.archive.application.negotiation;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationActivityResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAssociatedRecordResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationCustomDataResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationNotificationResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationRowResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationUnassociatedDetailResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationWorkspaceResponse;
import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.PaginationSupport;
import edu.bu.archive.adapter.out.persistence.NegotiationArchiveRepository;
import edu.bu.archive.adapter.out.persistence.NegotiationArchivedAttachment;
import edu.bu.archive.adapter.out.persistence.NegotiationAttachmentStorage;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class NegotiationArchiveService {

    private final NegotiationArchiveRepository repository;
    private final NegotiationAttachmentStorage attachmentStorage;

    public NegotiationArchiveService(
            NegotiationArchiveRepository repository,
            NegotiationAttachmentStorage attachmentStorage
    ) {
        this.repository = repository;
        this.attachmentStorage = attachmentStorage;
    }

    public PageResponse<NegotiationSummaryResponse> findPage(
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

    public List<NegotiationAttachmentResponse> findAttachments(
            long negotiationId
    ) {
        requireNegotiation(negotiationId);
        return repository.findAttachments(negotiationId);
    }

    public NegotiationAttachmentDownload downloadAttachment(
            long negotiationId,
            long attachmentId
    ) {
        requireNegotiation(negotiationId);

        if (attachmentId <= 0) {
            throw new IllegalArgumentException(
                    "Attachment ID must be positive"
            );
        }

        long owner = repository.findAttachmentNegotiationId(attachmentId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Negotiation attachment not found"
                ));

        if (owner != negotiationId) {
            throw new NoSuchElementException(
                    "Negotiation attachment not found"
            );
        }

        NegotiationArchivedAttachment archived =
                repository.findArchivedAttachment(negotiationId, attachmentId)
                        .filter(row ->
                                "ARCHIVED".equals(row.archiveStatus())
                                        && row.s3Bucket() != null
                                        && !row.s3Bucket().isBlank()
                                        && row.s3Key() != null
                                        && !row.s3Key().isBlank()
                        )
                        .orElseThrow(() -> new NoSuchElementException(
                                "Archived attachment not found"
                        ));

        NegotiationAttachmentStorage.StoredObject object =
                attachmentStorage.open(archived);

        return new NegotiationAttachmentDownload(
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

    /*
     * Identifier semantics per association type - see
     * NegotiationAssociatedRecordResponse for the proof. "NO" (no
     * association - the dominant real-data case) and any code this
     * switch doesn't recognize both resolve to a non-clickable result,
     * never a guess.
     */
    public NegotiationAssociatedRecordResponse findAssociatedRecord(
            long negotiationId
    ) {
        NegotiationRowResponse current = requireNegotiation(negotiationId);
        String code = current.negotiationAssociationTypeCode();
        String description =
                current.negotiationAssociationTypeDescription();
        String documentId = current.associatedDocumentId();

        if (code == null || documentId == null || documentId.isBlank()) {
            return new NegotiationAssociatedRecordResponse(
                    code, description, documentId, "NONE", null, false
            );
        }

        return switch (code) {
            case "AWD" -> repository.resolveCurrentAwardId(documentId)
                    .map(id -> new NegotiationAssociatedRecordResponse(
                            code, description, documentId,
                            "AWARD", id, true
                    ))
                    .orElseGet(() -> new NegotiationAssociatedRecordResponse(
                            code, description, documentId,
                            "AWARD", null, false
                    ));
            case "IP" -> repository.resolveCurrentProposalId(documentId)
                    .map(id -> new NegotiationAssociatedRecordResponse(
                            code, description, documentId,
                            "PROPOSAL", id, true
                    ))
                    .orElseGet(() -> new NegotiationAssociatedRecordResponse(
                            code, description, documentId,
                            "PROPOSAL", null, false
                    ));
            case "SWD" -> {
                Long subawardId = parseLongOrNull(documentId);
                boolean exists = subawardId != null
                        && repository.subawardExists(subawardId);
                yield new NegotiationAssociatedRecordResponse(
                        code, description, documentId, "SUBAWARD",
                        exists ? subawardId : null, exists
                );
            }
            case "NO" -> new NegotiationAssociatedRecordResponse(
                    code, description, documentId, "NONE", null, false
            );
            default -> new NegotiationAssociatedRecordResponse(
                    code, description, documentId, "UNSUPPORTED",
                    null, false
            );
        };
    }

    private Long parseLongOrNull(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
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
