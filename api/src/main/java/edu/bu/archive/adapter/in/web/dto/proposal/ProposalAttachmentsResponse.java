package edu.bu.archive.adapter.in.web.dto.proposal;

import java.util.List;

/*
 * Grouped by Oracle's real PROPOSAL_ATTACHMENT_TYPE taxonomy, ordered
 * by attachmentTypeCode - "Other" (code 7) is Oracle's own catch-all
 * and naturally sorts last. Never paginated: real per-proposal
 * attachment counts observed so far are small (max 10 in the
 * reference fixtures), and grouping needs the whole set at once.
 */
public record ProposalAttachmentsResponse(
        List<ProposalAttachmentGroupResponse> groups
) {
}
