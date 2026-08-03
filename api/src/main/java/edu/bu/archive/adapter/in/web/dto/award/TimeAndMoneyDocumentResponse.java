package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDateTime;

/*
 * "Workflow Details" for one Time and Money document - archive.
 * time_and_money_document's own header row. This is a real KEW
 * workflow document (same shape as an Award's own AwardDocument), but
 * a DIFFERENT KEW document from any Award's workflowDocumentNumber -
 * timeAndMoneyDocumentNumber must never be cross-linked against
 * Award's own workflow document lookup. There is no live KEW
 * connection in this archive, so documentStatus/creationDate are the
 * only "workflow" information available - not a live routing/approval
 * trail.
 */
public record TimeAndMoneyDocumentResponse(
        String timeAndMoneyDocumentNumber,
        String rootAwardNumber,
        String documentStatus,
        LocalDateTime creationDate
) {
}
