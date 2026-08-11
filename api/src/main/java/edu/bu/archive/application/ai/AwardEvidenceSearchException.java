package edu.bu.archive.application.ai;

import java.util.UUID;

/*
 * Mirrors AiSummaryExecutionException exactly (correlationId + cause) -
 * a distinct class rather than reusing AiSummaryExecutionException
 * itself, since AiExceptionHandler's existing handler for that type
 * hardcodes AI-summary-specific wording ("AI summary is temporarily
 * unavailable") that would be misleading for an evidence-search
 * failure. See AiExceptionHandler's new handleEvidenceSearchFailure().
 */
public class AwardEvidenceSearchException extends RuntimeException {

    private final UUID correlationId;

    public AwardEvidenceSearchException(
            UUID correlationId,
            RuntimeException cause
    ) {
        super(cause.getMessage(), cause);
        this.correlationId = correlationId;
    }

    public UUID correlationId() {
        return correlationId;
    }
}
