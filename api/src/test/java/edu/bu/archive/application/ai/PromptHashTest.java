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
                        "b7d14da6ebeb234c37a1720ddca5f848"
                                + "641c856bb3544f7d40c4feeb88249ccf"
                );
    }

    @Test
    void changesWhenPromptTextChanges() {
        assertThat(PromptHash.sha256("prompt"))
                .isNotEqualTo(PromptHash.sha256("prompt "));
    }
}
