package edu.bu.archive.adapter.in.web;

import edu.bu.archive.application.ai.AiProviderException;
import edu.bu.archive.application.ai.AiSummaryExecutionException;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class AiExceptionHandlerTest {

    private final AiExceptionHandler handler =
            new AiExceptionHandler();

    @Test
    void executionFailureReturnsSafeServiceUnavailableResponse() {
        UUID correlationId = UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
        );

        ResponseEntity<Map<String, Object>> response =
                handler.handleExecutionFailure(
                        new AiSummaryExecutionException(
                                correlationId,
                                new AiProviderException(
                                        "Provider detail"
                                )
                        )
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .containsEntry("status", 503)
                .containsEntry(
                        "message",
                        "AI summary is temporarily unavailable"
                )
                .containsEntry(
                        "correlationId",
                        correlationId.toString()
                )
                .doesNotContainValue("Provider detail");
    }

    @Test
    void directProviderFailureReturnsSafeServiceUnavailableResponse() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleProviderFailure(
                        new AiProviderException(
                                "Provider detail"
                        )
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .containsEntry("status", 503)
                .containsEntry(
                        "message",
                        "AI summary is temporarily unavailable"
                )
                .doesNotContainKey("correlationId")
                .doesNotContainValue("Provider detail");
    }

    @Test
    void executionFailurePreservesNotFoundResponse() {
        UUID correlationId = UUID.fromString(
                "22222222-2222-2222-2222-222222222222"
        );

        ResponseEntity<Map<String, Object>> response =
                handler.handleExecutionFailure(
                        new AiSummaryExecutionException(
                                correlationId,
                                new NoSuchElementException(
                                        "Award not found"
                                )
                        )
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .containsEntry("status", 404)
                .containsEntry("message", "Award not found")
                .containsEntry(
                        "correlationId",
                        correlationId.toString()
                );
    }

    @Test
    void executionFailurePreservesBadRequestResponse() {
        UUID correlationId = UUID.fromString(
                "33333333-3333-3333-3333-333333333333"
        );

        ResponseEntity<Map<String, Object>> response =
                handler.handleExecutionFailure(
                        new AiSummaryExecutionException(
                                correlationId,
                                new IllegalArgumentException(
                                        "Award number is required"
                                )
                        )
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("status", 400)
                .containsEntry(
                        "message",
                        "Award number is required"
                )
                .containsEntry(
                        "correlationId",
                        correlationId.toString()
                );
    }
}
