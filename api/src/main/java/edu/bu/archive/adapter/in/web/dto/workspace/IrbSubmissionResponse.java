package edu.bu.archive.adapter.in.web.dto.workspace;

public record IrbSubmissionResponse(
        Integer sequenceNumber,
        Integer submissionNumber,
        String submissionType,
        String submissionStatus,
        String eventType,
        String reviewType
) {
}
