package edu.bu.archive.adapter.in.web.dto.award;

import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * documentNumber is archive.award_version.workflow_document_number -
 * KCOEUS.AWARD.DOCUMENT_NUMBER, the real Kuali workflow document number
 * (the foreign key into KREW_DOC_HDR_T.DOC_HDR_ID; see V055's migration
 * header and docs/DECISIONS.md for the investigation that proved this,
 * not modification_number, is what "document number" means everywhere
 * else in this codebase - Subaward/Negotiation/IRB's own documentNumber
 * fields all mean the same real workflow document number). Previously
 * this field was wired to modification_number - a separate, often-NULL
 * business field with no relationship to Kuali's workflow engine - that
 * was a real bug, not a naming choice; modificationNumber below is the
 * corrected, honestly-named home for that data, never fabricated.
 */
public record AwardVersionSummaryResponse(
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        String status,
        String transactionTypeCode,
        String transactionType,
        LocalDate awardEffectiveDate,
        LocalDateTime updateTimestamp,
        String documentNumber,
        String modificationNumber,
        boolean primaryCurrent
) {
}
