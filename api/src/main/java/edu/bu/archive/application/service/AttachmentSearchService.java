package edu.bu.archive.application.service;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.PaginationSupport;
import edu.bu.archive.adapter.in.web.dto.attachment.AttachmentSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.attachment.AttachmentSearchRow;
import edu.bu.archive.adapter.in.web.dto.attachment.MixedAttachmentSearchRow;
import edu.bu.archive.adapter.out.persistence.AttachmentSearchRepository;
import edu.bu.archive.application.award.AwardArchiveService;

import org.springframework.stereotype.Service;

import java.util.List;

/*
 * Archived File Finder Phase 2: recordType dispatch across Award and
 * Proposal, plus the mixed (recordType=ALL) union search. AWARD is
 * delegated verbatim to AwardArchiveService.searchAttachments - the
 * exact, unmodified Phase 1 method - so every existing Award behavior,
 * validation message, and test is preserved byte-for-byte. This class
 * only adds the two things Phase 1 never needed: a second domain
 * (PROPOSAL) and a query that spans both (ALL).
 *
 * recordType defaults to AWARD when omitted entirely - not ALL - so
 * every Phase 1 bookmarked URL (none of which ever set recordType)
 * keeps behaving exactly as before. recordNumber/recordId are the new
 * canonical parameter names; awardNumber/awardId are accepted as
 * temporary aliases (first non-blank of the pair wins, canonical
 * preferred) so old query strings keep working without the caller
 * needing to know the new names exist.
 */
@Service
public class AttachmentSearchService {

    private static final String RECORD_TYPE_ALL = "ALL";
    private static final String RECORD_TYPE_AWARD = "AWARD";
    private static final String RECORD_TYPE_PROPOSAL = "PROPOSAL";

    private static final String ATTACHMENT_SEARCH_SORT =
            "ORDER BY parent_number, sequence_number, attachment_id\n";
    private static final String MIXED_ATTACHMENT_SEARCH_SORT =
            "ORDER BY parent_number, sequence_number, record_type, attachment_id\n";

    private final AwardArchiveService awardArchiveService;
    private final AttachmentSearchRepository repository;

    public AttachmentSearchService(
            AwardArchiveService awardArchiveService,
            AttachmentSearchRepository repository
    ) {
        this.awardArchiveService = awardArchiveService;
        this.repository = repository;
    }

    public PageResponse<AttachmentSearchResultResponse> searchAttachments(
            String recordType,
            String recordNumber,
            String awardNumberAlias,
            String documentNumber,
            String recordId,
            String awardIdAlias,
            String attachmentId,
            String fileId,
            String versionFilter,
            int page,
            int size
    ) {
        String safeRecordType = normalizeRecordType(recordType);
        String safeRecordNumber = firstNonBlank(recordNumber, awardNumberAlias);
        String safeRecordId = firstNonBlank(recordId, awardIdAlias);
        String safeDocumentNumber = documentNumber == null ? "" : documentNumber.trim();
        String safeFileId = fileId == null ? "" : fileId.trim();

        if (RECORD_TYPE_PROPOSAL.equals(safeRecordType) && !safeFileId.isEmpty()) {
            throw new IllegalArgumentException(
                    "fileId is Award-specific and cannot be used with recordType=PROPOSAL"
            );
        }

        if (RECORD_TYPE_ALL.equals(safeRecordType)) {
            boolean hasRecordId = safeRecordId != null && !safeRecordId.isBlank();
            boolean hasAttachmentId = attachmentId != null && !attachmentId.isBlank();
            boolean hasFileId = !safeFileId.isEmpty();
            if (hasRecordId || hasAttachmentId || hasFileId) {
                throw new IllegalArgumentException(
                        "recordId, attachmentId, and fileId are ambiguous across "
                                + "domains with recordType=ALL - supply an explicit "
                                + "recordType of AWARD or PROPOSAL to use them"
                );
            }
        }

        return switch (safeRecordType) {
            case RECORD_TYPE_PROPOSAL -> searchProposal(
                    safeRecordNumber, safeDocumentNumber, safeRecordId,
                    attachmentId, versionFilter, page, size
            );
            case RECORD_TYPE_ALL -> searchAll(
                    safeRecordNumber, safeDocumentNumber, versionFilter, page, size
            );
            default -> awardArchiveService.searchAttachments(
                    safeRecordNumber, safeDocumentNumber, safeRecordId,
                    attachmentId, safeFileId, versionFilter, page, size
            );
        };
    }

    private PageResponse<AttachmentSearchResultResponse> searchProposal(
            String recordNumber,
            String documentNumber,
            String recordId,
            String attachmentId,
            String versionFilter,
            int page,
            int size
    ) {
        Long safeRecordId = parseIdentifier(recordId, "Proposal ID");
        Long safeAttachmentId = parseIdentifier(attachmentId, "Attachment ID");
        String safeVersionFilter = normalizeVersionFilter(versionFilter);

        if (recordNumber.isEmpty()
                && documentNumber.isEmpty()
                && safeRecordId == null
                && safeAttachmentId == null) {
            throw new IllegalArgumentException(
                    "At least one identifier (recordNumber, documentNumber, "
                            + "recordId, or attachmentId) must be supplied"
            );
        }

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        long totalElements = repository.countSearchProposalAttachments(
                recordNumber, documentNumber, safeRecordId, safeAttachmentId,
                safeVersionFilter
        );

        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(safePage, safeSize, totalElements);

        int offset = safePage * safeSize;

        List<AttachmentSearchResultResponse> content =
                repository.searchProposalAttachments(
                        recordNumber, documentNumber, safeRecordId, safeAttachmentId,
                        safeVersionFilter, ATTACHMENT_SEARCH_SORT, safeSize, offset
                ).stream()
                        .map(row -> toResult(RECORD_TYPE_PROPOSAL, row))
                        .toList();

        return new PageResponse<>(
                content, safePage, safeSize, totalElements,
                pageMetadata.totalPages(), pageMetadata.first(), pageMetadata.last()
        );
    }

    /*
     * recordType=ALL: only recordNumber/documentNumber/versionFilter
     * ever reach here - recordId/attachmentId/fileId are already
     * rejected above. A search with every field blank would scan the
     * whole archive across two domains at once, so the same "at least
     * one identifier" rule applies.
     */
    private PageResponse<AttachmentSearchResultResponse> searchAll(
            String recordNumber,
            String documentNumber,
            String versionFilter,
            int page,
            int size
    ) {
        String safeVersionFilter = normalizeVersionFilter(versionFilter);

        if (recordNumber.isEmpty() && documentNumber.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one identifier (recordNumber or documentNumber) "
                            + "must be supplied"
            );
        }

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        long totalElements = repository.countSearchAllAttachments(
                recordNumber, documentNumber, safeVersionFilter
        );

        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(safePage, safeSize, totalElements);

        int offset = safePage * safeSize;

        List<AttachmentSearchResultResponse> content =
                repository.searchAllAttachments(
                        recordNumber, documentNumber, safeVersionFilter,
                        MIXED_ATTACHMENT_SEARCH_SORT, safeSize, offset
                ).stream()
                        .map(this::toResult)
                        .toList();

        return new PageResponse<>(
                content, safePage, safeSize, totalElements,
                pageMetadata.totalPages(), pageMetadata.first(), pageMetadata.last()
        );
    }

    private static AttachmentSearchResultResponse toResult(
            String recordType,
            AttachmentSearchRow row
    ) {
        return new AttachmentSearchResultResponse(
                recordType,
                row.parentId(),
                row.parentNumber(),
                row.title(),
                row.principalInvestigator(),
                row.sequenceNumber(),
                row.workflowDocumentNumber(),
                row.attachmentId(),
                row.fileId(),
                row.fileName(),
                row.documentType(),
                row.sourceDate(),
                row.fileSizeBytes(),
                row.contentType(),
                resolveAvailabilityStatus(recordType, row.uploadStatus(), row.downloadable()),
                row.downloadable(),
                row.currentVersion()
        );
    }

    private AttachmentSearchResultResponse toResult(MixedAttachmentSearchRow row) {
        return new AttachmentSearchResultResponse(
                row.recordType(),
                row.parentId(),
                row.parentNumber(),
                row.title(),
                row.principalInvestigator(),
                row.sequenceNumber(),
                row.workflowDocumentNumber(),
                row.attachmentId(),
                row.fileId(),
                row.fileName(),
                row.documentType(),
                row.sourceDate(),
                row.fileSizeBytes(),
                row.contentType(),
                resolveAvailabilityStatus(row.recordType(), row.uploadStatus(), row.downloadable()),
                row.downloadable(),
                row.currentVersion()
        );
    }

    /*
     * Same four human-readable labels as Award's own
     * AwardArchiveService.resolveAvailabilityStatus, but Proposal's real
     * archive.proposal_attachment.upload_status vocabulary
     * (NOT_REQUESTED/IN_PROGRESS/UPLOADED/MISSING_SOURCE/FAILED) is not
     * the same set of literal values as Award's
     * archive.attachment_object.upload_status (PENDING/UPLOADING/
     * UPLOADED/MISSING_SOURCE_CONTENT/FAILED) - so this checks exactly
     * one domain-specific "not yet requested" literal (Award's PENDING,
     * the metadata-loader-only value; Proposal's NOT_REQUESTED, its own
     * direct equivalent), mirroring Award's exact single-value-check
     * shape rather than unifying or widening either domain's existing
     * behavior. Every current Proposal attachment is UPLOADED (see the
     * 2026-08-14 load completion), so NOT_REQUESTED/IN_PROGRESS/
     * MISSING_SOURCE/FAILED are exercised only by tests today - this
     * still maps them correctly for whenever a non-uploaded state
     * exists.
     */
    private static String resolveAvailabilityStatus(
            String recordType,
            String uploadStatus,
            boolean downloadable
    ) {
        if (downloadable) {
            return "Available";
        }
        String pendingLiteral = RECORD_TYPE_PROPOSAL.equals(recordType)
                ? "NOT_REQUESTED"
                : "PENDING";
        if (pendingLiteral.equals(uploadStatus)) {
            return "Pending upload";
        }
        if ("FAILED".equals(uploadStatus)) {
            return "Failed";
        }
        return "Source file unavailable";
    }

    private static String normalizeRecordType(String recordType) {
        if (recordType == null || recordType.isBlank()) {
            return RECORD_TYPE_AWARD;
        }
        String upper = recordType.trim().toUpperCase();
        if (!RECORD_TYPE_ALL.equals(upper)
                && !RECORD_TYPE_AWARD.equals(upper)
                && !RECORD_TYPE_PROPOSAL.equals(upper)) {
            throw new IllegalArgumentException(
                    "recordType must be one of ALL, AWARD, PROPOSAL: " + recordType
            );
        }
        return upper;
    }

    private static String normalizeVersionFilter(String versionFilter) {
        String lower = versionFilter == null ? "" : versionFilter.trim().toLowerCase();
        if ("current".equals(lower) || "historical".equals(lower)) {
            return lower;
        }
        return "all";
    }

    private static String firstNonBlank(String preferred, String alias) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        if (alias != null && !alias.isBlank()) {
            return alias.trim();
        }
        return preferred == null ? "" : preferred;
    }

    private static Long parseIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    label + " must be a valid whole number: " + value
            );
        }
    }
}
