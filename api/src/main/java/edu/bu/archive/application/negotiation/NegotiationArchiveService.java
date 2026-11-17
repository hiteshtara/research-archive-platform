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

    /**
     * B4/H2: what a restricted caller receives for a Negotiation whose
     * associated record they cannot see, and for a genuinely unassociated
     * one - identical by construction, so the two cannot be told apart.
     * "NO"/"None" is the archive's own association type for an unassociated
     * Negotiation (8,533 of 10,775 rows - see V080's migration header).
     */
    static final NegotiationAssociatedRecordResponse NO_ASSOCIATION =
            new NegotiationAssociatedRecordResponse("NO", "None", null, "NONE", null, false);

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
        // B4/H2: null when unrestricted, so the statement is unchanged.
        edu.bu.archive.application.authorization.SqlFragment associationVisible =
                associationVisibilitySql();

        long totalElements = repository.countNegotiations(filters, scope, associationVisible);
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
                        associationVisible,
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
                withoutHiddenAssociation(current)
        );
    }

    /**
     * B4/H2: the workspace row with everything that came from an associated
     * record the caller cannot see removed - the identifier, and the
     * resolved attributes when the V080 rebuild took them from that Award
     * ({@code attributeSource = 'AWARD'}). Mirrors, for one row, what
     * {@link NegotiationArchiveRepository#findNegotiations} does in SQL for
     * the list, so the workspace and the list agree.
     *
     * <p>The association TYPE stays: it is the Negotiation's own column,
     * shown and filterable on every row, and names no record.
     *
     * <p>Unchanged when unrestricted (enforcement off, or Central).
     */
    private NegotiationRowResponse withoutHiddenAssociation(NegotiationRowResponse row) {
        if (recordAccess.unrestricted()
                || associationVisible(row.negotiationAssociationTypeCode(), row.associatedDocumentId())) {
            return row;
        }
        boolean fromAward = "AWARD".equals(row.attributeSource());
        return new NegotiationRowResponse(
                row.negotiationId(),
                row.documentNumber(),
                row.negotiationStatusId(),
                row.negotiationStatusCode(),
                row.negotiationStatusDescription(),
                row.negotiationAgreementTypeId(),
                row.negotiationAgreementTypeCode(),
                row.negotiationAgreementTypeDescription(),
                row.negotiationAssociationTypeId(),
                row.negotiationAssociationTypeCode(),
                row.negotiationAssociationTypeDescription(),
                row.negotiatorPersonId(),
                row.negotiatorFullName(),
                row.negotiationStartDate(),
                row.negotiationEndDate(),
                row.anticipatedAwardDate(),
                row.documentFolder(),
                null,
                row.sourceUpdateTimestamp(),
                row.sourceUpdateUser(),
                row.sourceVersionNumber(),
                row.sourceObjectId(),
                row.documentSourceUpdateTimestamp(),
                row.documentSourceUpdateUser(),
                row.documentSourceVersionNumber(),
                row.documentSourceObjectId(),
                fromAward ? null : row.title(),
                fromAward ? null : row.principalInvestigatorName(),
                fromAward ? null : row.sponsorCode(),
                fromAward ? null : row.sponsorName(),
                fromAward ? null : row.primeSponsorCode(),
                fromAward ? null : row.primeSponsorName(),
                fromAward ? null : row.leadUnitNumber(),
                fromAward ? null : row.leadUnitName(),
                fromAward ? null : row.sponsorAwardNumber(),
                fromAward ? null : row.attributeSource()
        );
    }

    /**
     * B4/H2: a boolean SQL expression over Negotiation alias {@code n},
     * true when the caller may see the record the Negotiation is associated
     * with - the SQL form of {@link #associationVisible}, so the list and
     * the per-record decision agree.
     *
     * <p>No new rule: the Award and Proposal branches are the related
     * modules' OWN predicates ({@code relatedScopeSql}, defect B1), applied
     * to the version {@code canSeeAwardNumber} / {@code canSeeProposalNumber}
     * decide - the primary-current Award version, and the latest Proposal
     * version ordered exactly as {@code latestProposalVersionKey} orders it.
     * The identifier is matched exactly, as those methods match it.
     *
     * <p>{@code null} when unrestricted, which leaves every statement
     * byte-for-byte unchanged.
     */
    private edu.bu.archive.application.authorization.SqlFragment associationVisibilitySql() {
        if (recordAccess.unrestricted()) {
            return null;
        }
        edu.bu.archive.application.authorization.SqlFragment award =
                recordAccess.relatedScopeSql(
                        edu.bu.archive.application.authorization.RecordModule.AWARD, "az_xa");
        edu.bu.archive.application.authorization.SqlFragment proposal =
                recordAccess.relatedScopeSql(
                        edu.bu.archive.application.authorization.RecordModule.PROPOSAL, "az_xp");
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.putAll(award.params());
        params.putAll(proposal.params());
        String sql = "n.associated_document_id IS NULL"
                + " OR TRIM(n.associated_document_id) = ''"
                + " OR n.negotiation_association_type_code IS NULL"
                + " OR n.negotiation_association_type_code = 'NO'"
                + " OR (n.negotiation_association_type_code = 'AWD'"
                + " AND EXISTS (SELECT 1 FROM archive.award_version az_xa"
                + " WHERE az_xa.award_number = n.associated_document_id"
                + " AND az_xa.is_primary_current = TRUE" + award.sql() + "))"
                + " OR (n.negotiation_association_type_code = 'IP'"
                + " AND EXISTS (SELECT 1 FROM archive.proposal_version az_xp"
                + " WHERE az_xp.proposal_number = n.associated_document_id"
                + " AND az_xp.proposal_id = (SELECT az_xpl.proposal_id"
                + " FROM archive.proposal_version az_xpl"
                + " WHERE az_xpl.proposal_number = az_xp.proposal_number"
                + " ORDER BY az_xpl.version_number DESC,"
                + " az_xpl.source_update_timestamp DESC NULLS LAST,"
                + " az_xpl.proposal_id DESC LIMIT 1)" + proposal.sql() + "))";
        return new edu.bu.archive.application.authorization.SqlFragment(sql, params);
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
         * Omitted entirely, never stubbed and never counted. B4/H2
         * (2026-11-17): the earlier form of this redaction nulled the
         * identifier but still answered with the association's own code and
         * description ('AWD'/'Award'), so a restricted association remained
         * distinguishable from none - the existence disclosure D7 forbids.
         * For a restricted caller a hidden association and a genuine "NO"
         * association now produce the SAME response, NO_ASSOCIATION. A
         * genuine "NO" therefore also drops its identifier for them - it is
         * only the Negotiation's own document number, already on the row -
         * because a "NO" answer carrying an identifier while a hidden one
         * did not would tell the two apart again.
         *
         * Gated on !unrestricted(): with enforcement off, or for Central,
         * the response is byte-for-byte what it always was.
         */
        if (!recordAccess.unrestricted()
                && ("NO".equals(code) || !associationVisible(code, documentId))) {
            return NO_ASSOCIATION;
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
    private boolean associationVisible(String code, String documentId) {
        // Nothing is associated, so nothing can be hidden - the same cases
        // findAssociatedRecord answers before any check, and the SQL form
        // (associationVisibilitySql) treats as visible.
        if (code == null || documentId == null || documentId.isBlank()) {
            return true;
        }
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
