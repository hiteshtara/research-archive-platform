package edu.bu.archive.domain.model.ai;

public record AiRequest(
        String systemPrompt,
        AwardAiContext awardContext
) {
}
