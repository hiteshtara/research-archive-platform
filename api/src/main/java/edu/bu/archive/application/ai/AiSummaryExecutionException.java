package edu.bu.archive.application.ai;

import java.util.UUID;

public class AiSummaryExecutionException extends RuntimeException {

    private final UUID correlationId;

    public AiSummaryExecutionException(
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
