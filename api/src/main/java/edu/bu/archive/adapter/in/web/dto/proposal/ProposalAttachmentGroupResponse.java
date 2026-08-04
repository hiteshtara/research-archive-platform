package edu.bu.archive.adapter.in.web.dto.proposal;

import java.util.List;

public record ProposalAttachmentGroupResponse(
        Integer attachmentTypeCode,
        String attachmentTypeDescription,
        List<ProposalAttachmentResponse> attachments
) {
}
