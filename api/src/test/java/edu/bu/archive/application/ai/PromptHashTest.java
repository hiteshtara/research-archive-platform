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
                        "c3834892e8606e33bc26cdfdf0a7dd2f"
                                + "c8417d664b55df7a46c8fb2772ad893a"
                );
    }

    @Test
    void changesWhenPromptTextChanges() {
        assertThat(PromptHash.sha256("prompt"))
                .isNotEqualTo(PromptHash.sha256("prompt "));
    }
}
