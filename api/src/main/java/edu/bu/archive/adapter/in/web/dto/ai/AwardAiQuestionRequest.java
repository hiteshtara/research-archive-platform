package edu.bu.archive.adapter.in.web.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AwardAiQuestionRequest(
        @NotBlank(message = "Question is required")
        @Size(
                max = 500,
                message = "Question must not exceed 500 characters"
        )
        String question
) {
}
