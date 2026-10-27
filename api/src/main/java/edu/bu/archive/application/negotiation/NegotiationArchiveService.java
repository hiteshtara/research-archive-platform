package edu.bu.archive.application.negotiation;

import edu.bu.archive.adapter.out.persistence.NegotiationSemanticSummaryRow;

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
    private final edu.bu.archive.application.authorization.NegotiationRecordAccess recordAccess;

    /*
     * Pre-authorization signature, kept so existing unit tests construct
     * this service unchanged. Resolves to PERMIT_ALL - exactly what every
     * caller got before. Never the constructor Spring uses.
     */
    public NegotiationArchiveService(
            NegotiationArchiveRepository repository,
            NegotiationAttachmentStorage attachmentStorage
    ) {
        this(repository, attachmentStorage,
                edu.bu.archive.application.authorization.NegotiationRecordAccess.PERMIT_ALL);
    }

    /** Direct-injection form, for tests that already hold the port. */
    public NegotiationArchiveService(
            NegotiationArchiveRepository repository,
            NegotiationAttachmentStorage attachmentStorage,
            edu.bu.archive.application.authorization.NegotiationRecordAccess recordAccess
    ) {
        this.repository = repository;
        this.attachmentStorage = attachmentStorage;
        this.recordAccess = recordAccess == null
                ? edu.bu.archive.application.authorization.NegotiationRecordAccess.PERMIT_ALL
                : recordAccess;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NegotiationArchiveService(
            NegotiationArchiveRepository repository,
            NegotiationAttachmentStorage attachmentStorage,
            org.springframework.beans.factory.ObjectProvider<
                    edu.bu.archive.application.authorization.NegotiationRecordAccess>
                    recordAccessProvider,
            org.springframework.beans.factory.ObjectProvider<
                    edu.bu.archive.application.authorization.AuthorizationProperties>
                    authorizationProperties
    ) {
        edu.bu.archive.application.authorization.NegotiationRecordAccess resolved =
                recordAccessProvider == null ? null : recordAccessProvider.getIfAvailable();
        this.recordAccess = resolved != null
                ? resolved
                : edu.bu.archive.application.authorization.NegotiationRecordAccess.PERMIT_ALL;
        edu.bu.archive.application.authorization.NegotiationRecordAccess
                .requireEnforceableWiring(this.recordAccess, authorizationProperties);
        this.repository = repository;
        this.attachmentStorage = attachmentStorage;
    }

    /**
     * Free-text-only search, kept for callers that have no structured
     * filters to apply (Global Search).
     */
    public PageResponse<NegotiationSummaryResponse> findPage(
            String query,
            int page,
            int size
    ) {
        return findPage(NegotiationSearchFilters.ofQuery(query), page, size);
    }

    /**
     * Filtered search. Every supplied filter is ANDed, in PostgreSQL, so
     * the total count and the returned page always describe the same
     * result set - see NegotiationSearchFilters.
     */
    public PageResponse<NegotiationSummaryResponse> findPage(
            NegotiationSearchFilters filters,
            int page,
            int size
    ) {
        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        /*
         * Resolved ONCE and given to both statements, in the SAME WHERE as
         * the filters. Filtering the fetched page in Java instead would
         * publish the out-of-scope Negotiations through totalElements and
         * return short pages that look like the end of the result.
         */
        recordAccess.requireProvisioned();

        edu.bu.archive.application.authorization.SqlFragment scope =
                recordAccess.negotiationScopeSql("n");

        long totalElements = repository.countNegotiations(filters, scope);
        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(
                        safePage,
                        safeSize,
                        totalElements
                );
        int offset = safePage * safeSize;

        List<NegotiationSummaryResponse> content =
                repository.findNegotiations(
                        filters,
                        scope,
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
        recordAccess.requireNegotiation(negotiationId);
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
        recordAccess.requireNegotiation(negotiationId);
        requireNegotiation(negotiationId);
        return repository.findActivities(negotiationId);
    }

    public List<NegotiationCustomDataResponse> findCustomData(
            long negotiationId
    ) {
        recordAccess.requireNegotiation(negotiationId);
        requireNegotiation(negotiationId);
        return repository.findCustomData(negotiationId);
    }

    public List<NegotiationNotificationResponse> findNotifications(
            long negotiationId
    ) {
        recordAccess.requireNegotiation(negotiationId);
        requireNegotiation(negotiationId);
        return repository.findNotifications(negotiationId);
    }

    public List<NegotiationUnassociatedDetailResponse>
            findUnassociatedDetails(
                    long negotiationId
            ) {
        recordAccess.requireNegotiation(negotiationId);
        requireNegotiation(negotiationId);
        return repository.findUnassociatedDetails(negotiationId);
    }

    public List<NegotiationAttachmentResponse> findAttachments(
            long negotiationId
    ) {
        recordAccess.requireNegotiation(negotiationId);
        requireNegotiation(negotiationId);
        return repository.findAttachments(negotiationId);
    }

    public NegotiationAttachmentDownload downloadAttachment(
            long negotiationId,
            long attachmentId
    ) {
        recordAccess.requireNegotiation(negotiationId);
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
        recordAccess.requireNegotiation(negotiationId);
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

        /*
         * D7 applied to the associated record. Reaching a Negotiation
         * through its LEAD UNIT does not authorize the Award, Proposal or
         * Subaward it points at - those keep their own gates. Without this,
         * a department user who can open a Negotiation would be handed the
         * associated record's identifier, its resolved id and a navigable
         * link to a record they may not read.
         *
         * Omitted entirely, never stubbed and never counted: the response
         * degrades to exactly the shape an unassociated Negotiation
         * produces, so a restricted association is indistinguishable from
         * no association at all. A "you may not see this one" marker would
         * itself be the existence disclosure D7 forbids.
         *
         * Gated on !unrestricted(): with enforcement off, or for Central,
         * the response is byte-for-byte what it always was.
         */
        if (!recordAccess.unrestricted() && !associatedRecordVisible(code, documentId)) {
            return new NegotiationAssociatedRecordResponse(
                    code, description, null, "NONE", null, false
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

    /*
     * Display fields for a set of Negotiations, keyed by document
     * number - the identifier a semantic result carries (QA TC-041).
     * One query for the whole set; see the repository for why.
     */
    /**
     * Whether the caller may see the record this Negotiation points at.
     *
     * <p>The association code is the gate, exactly as in the authorization
     * predicate: "AWD" resolves against award_number and "IP" against
     * proposal_number, and nothing else is resolved by string match.
     *
     * <p>"SWD" (16 rows archive-wide) returns FALSE for a scoped caller.
     * No reliable Subaward key exists for these - the identifier resolves
     * ambiguously - so the honest answer is to disclose nothing rather than
     * guess. They remain fully visible to Central and with enforcement off.
     */
    private boolean associatedRecordVisible(String code, String documentId) {
        return switch (code) {
            case "AWD" -> recordAccess.canSeeAwardNumber(documentId);
            case "IP" -> recordAccess.canSeeProposalNumber(documentId);
            case "NO" -> true;
            default -> false;
        };
    }

    /**
     * Display fields for the Negotiation semantic results on one Global
     * Search page.
     *
     * <p>Scoped in SQL, not afterwards. Global Search treats an absent
     * summary as a row to drop, so a document number the caller may not
     * open must produce no row - returning it unenriched would still
     * publish the number and its link.
     */
    public List<NegotiationSemanticSummaryRow> findSummariesForDocumentNumbers(
            List<String> documentNumbers
    ) {
        recordAccess.requireProvisioned();
        return repository.findSummariesForDocumentNumbers(
                documentNumbers, recordAccess.negotiationScopeSql("n"));
    }

}
