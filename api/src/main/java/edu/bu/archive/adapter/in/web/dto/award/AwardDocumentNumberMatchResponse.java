package edu.bu.archive.adapter.in.web.dto.award;

/*
 * An exact match against archive.award_version.workflow_document_number
 * (KCOEUS.AWARD.DOCUMENT_NUMBER, the real Kuali workflow document number
 * - see V055's migration header and docs/DECISIONS.md) - searched across
 * every archived version of every Award, not only is_primary_current, so
 * a document number that belongs to a superseded (non-current) sequence
 * can still be found. documentType is a fixed "Award" literal for this
 * phase (Award is the only module this search covers so far) rather
 * than a join to KREW_DOC_TYP_T.DOC_TYP_NM - see AwardArchiveRepository.
 * findExactWorkflowDocumentMatch's own comment for why that join is
 * deliberately deferred, not fabricated.
 */
public record AwardDocumentNumberMatchResponse(
        Long awardId,
        String awardNumber,
        Integer sequenceNumber,
        String workflowDocumentNumber,
        String documentType,
        String title,
        String status
) {
}
