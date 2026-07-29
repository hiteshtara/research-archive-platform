package edu.bu.archive.application.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptHashTest {

    @Test
    void isStableForTheEffectiveSystemPrompt() {
        assertThat(PromptHash.sha256(
                AwardAiSummaryService.SYSTEM_PROMPT
        )).isEqualTo(AwardAiSummaryService.SYSTEM_PROMPT_HASH);
        assertThat(AwardAiSummaryService.SYSTEM_PROMPT_HASH)
                .isEqualTo(
                        "eccd966a9aafddac59517816439f5006"
                                + "db6ef4ae6f2dc7e844a5026e0fdd7b3f"
                );
    }

    @Test
    void changesWhenPromptTextChanges() {
        assertThat(PromptHash.sha256("prompt"))
                .isNotEqualTo(PromptHash.sha256("prompt "));
    }
}
