package edu.bu.archive.application.proposal;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.PaginationSupport;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAssociatedUnitResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAttachmentGroupResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAttachmentResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalAttachmentsResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCommentCategoryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCommentEntryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCommentRow;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalCommentsResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFundedAwardResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalPersonResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalUnitContactResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalUnitsResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalVersionSummaryResponse;
import edu.bu.archive.adapter.out.persistence.ProposalArchivedAttachment;
import edu.bu.archive.adapter.out.persistence.ProposalAttachmentStorage;
import edu.bu.archive.adapter.out.persistence.ProposalV1Repository;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/*
 * Backs the versioned /api/v1/proposals API - keyed by the exact
 * surrogate proposalId, mirroring AwardArchiveService's own
 * conventions (NoSuchElementException for 404s, PaginationSupport for
 * paging, the same comment-history collapsing behavior). proposalId,
 * proposalNumber, sequenceNumber, and workflowDocumentNumber are kept
 * as four distinct identifiers throughout - never inferred one from
 * another.
 */
@Service
public class ProposalArchiveV1Service {

    private final ProposalV1Repository repository;
    private final ProposalAttachmentStorage attachmentStorage;

    public ProposalArchiveV1Service(
            ProposalV1Repository repository,
            ProposalAttachmentStorage attachmentStorage
    ) {
        this.repository = repository;
        this.attachmentStorage = attachmentStorage;
    }

    public ProposalSummaryResponse findSummary(long proposalId) {
        return repository.findSummary(proposalId)
                .orElseThrow(() -> proposalNotFound(proposalId));
    }

    public PageResponse<ProposalVersionSummaryResponse> findVersions(
            long proposalId,
            int page,
            int size
    ) {
        String proposalNumber = requireProposalNumberForId(proposalId);

        int safePage = PaginationSupport.clampPage(page);
        int safeSize = PaginationSupport.clampSize(size);

        long totalElements = repository.countVersions(proposalNumber);

        PaginationSupport.PageMetadata pageMetadata =
                PaginationSupport.metadata(safePage, safeSize, totalElements);

        int offset = safePage * safeSize;

        List<ProposalVersionSummaryResponse> content =
                repository.findVersionRows(proposalNumber, safeSize, offset);

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

    public List<ProposalPersonResponse> findPeople(long proposalId) {
        requireProposalNumberForId(proposalId);
        return repository.findPersonRows(proposalId);
    }

    public ProposalUnitsResponse findUnits(long proposalId) {
        requireProposalNumberForId(proposalId);

        List<ProposalAssociatedUnitResponse> associatedUnits =
                repository.findAssociatedUnitRows(proposalId);
        List<ProposalUnitContactResponse> unitContacts =
                repository.findUnitContactRows(proposalId);

        return new ProposalUnitsResponse(associatedUnits, unitContacts);
    }

    public ProposalAttachmentsResponse findAttachments(long proposalId) {
        requireProposalNumberForId(proposalId);

        List<ProposalAttachmentResponse> attachments =
                repository.findAttachmentRows(proposalId);

        Map<Integer, List<ProposalAttachmentResponse>> byType =
                new LinkedHashMap<>();
        Map<Integer, String> descriptionByType = new LinkedHashMap<>();
        for (ProposalAttachmentResponse attachment : attachments) {
            byType.computeIfAbsent(
                    attachment.attachmentTypeCode(),
                    key -> new ArrayList<>()
            ).add(attachment);
            descriptionByType.putIfAbsent(
                    attachment.attachmentTypeCode(),
                    attachment.attachmentTypeDescription()
            );
        }

        List<ProposalAttachmentGroupResponse> groups = new ArrayList<>();
        for (Map.Entry<Integer, List<ProposalAttachmentResponse>> entry
                : byType.entrySet()) {
            groups.add(new ProposalAttachmentGroupResponse(
                    entry.getKey(),
                    descriptionByType.get(entry.getKey()),
                    entry.getValue()
            ));
        }

        return new ProposalAttachmentsResponse(groups);
    }

    public ProposalAttachmentDownload downloadAttachment(
            long proposalId,
            long attachmentId
    ) {
        requireProposalNumberForId(proposalId);

        if (attachmentId <= 0) {
            throw new IllegalArgumentException(
                    "Attachment ID must be positive"
            );
        }

        long owner = repository.findAttachmentProposalId(attachmentId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Proposal attachment not found"
                ));

        if (owner != proposalId) {
            throw new NoSuchElementException(
                    "Proposal attachment not found"
            );
        }

        ProposalArchivedAttachment archived =
                repository.findArchivedAttachment(proposalId, attachmentId)
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

        ProposalAttachmentStorage.StoredObject object =
                attachmentStorage.open(archived);

        return new ProposalAttachmentDownload(
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

    public ProposalCommentsResponse findComments(long proposalId) {
        String proposalNumber = requireProposalNumberForId(proposalId);

        List<ProposalCommentRow> rows =
                repository.findCommentRows(proposalNumber);

        return new ProposalCommentsResponse(groupCommentsByType(rows));
    }

    /*
     * Mirrors AwardArchiveService.groupCommentsByType exactly: a
     * category with zero real comments for this family arrives as a
     * single all-null-except-type row (the LEFT JOIN) - filtered out
     * here so it renders as "no comment recorded" rather than a fake
     * entry.
     */
    private static List<ProposalCommentCategoryResponse> groupCommentsByType(
            List<ProposalCommentRow> rows
    ) {
        Map<String, List<ProposalCommentRow>> rowsByType = new LinkedHashMap<>();
        for (ProposalCommentRow row : rows) {
            rowsByType
                    .computeIfAbsent(row.commentTypeCode(), key -> new ArrayList<>())
                    .add(row);
        }

        List<ProposalCommentCategoryResponse> categories = new ArrayList<>();
        for (Map.Entry<String, List<ProposalCommentRow>> entry
                : rowsByType.entrySet()) {
            List<ProposalCommentRow> typeRows = entry.getValue();
            String description = typeRows.get(0).commentTypeDescription();

            List<ProposalCommentRow> actualComments = typeRows.stream()
                    .filter(row -> row.proposalCommentId() != null)
                    .toList();
            List<ProposalCommentEntryResponse> history =
                    collapseConsecutiveIdenticalText(actualComments);
            ProposalCommentEntryResponse current =
                    history.isEmpty() ? null : history.get(0);

            categories.add(new ProposalCommentCategoryResponse(
                    entry.getKey(), description, current, history
            ));
        }
        return categories;
    }

    /*
     * Mirrors AwardArchiveService.collapseConsecutiveIdenticalText:
     * "history behavior matching Award where applicable" - walks
     * oldest-to-newest, keeps only the first (oldest) row of each run
     * of consecutive identical comment text, then reverses back to
     * newest-first for presentation (current = index 0).
     */
    private static List<ProposalCommentEntryResponse> collapseConsecutiveIdenticalText(
            List<ProposalCommentRow> rowsNewestFirst
    ) {
        List<ProposalCommentRow> rowsOldestFirst =
                new ArrayList<>(rowsNewestFirst);
        Collections.reverse(rowsOldestFirst);

        List<ProposalCommentEntryResponse> historyOldestFirst = new ArrayList<>();
        String previousNormalizedText = null;
        boolean isFirst = true;

        for (ProposalCommentRow row : rowsOldestFirst) {
            String normalizedText = row.comments() == null
                    ? null
                    : row.comments().trim();

            if (!isFirst && Objects.equals(normalizedText, previousNormalizedText)) {
                continue;
            }

            historyOldestFirst.add(new ProposalCommentEntryResponse(
                    row.proposalCommentId(),
                    row.proposalId(),
                    row.sequenceNumber(),
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

    public List<ProposalFundedAwardResponse> findFundedAwards(long proposalId) {
        String proposalNumber = requireProposalNumberForId(proposalId);
        return repository.findFundedAwardRows(proposalNumber);
    }

    private String requireProposalNumberForId(long proposalId) {
        return repository.findProposalNumber(proposalId)
                .orElseThrow(() -> proposalNotFound(proposalId));
    }

    private NoSuchElementException proposalNotFound(long proposalId) {
        return new NoSuchElementException(
                "Proposal not found: " + proposalId
        );
    }
}
