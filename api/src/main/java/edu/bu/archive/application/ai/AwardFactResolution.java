package edu.bu.archive.application.ai;

import edu.bu.archive.domain.model.ai.AiCitation;

import java.util.List;

record AwardFactResolution(
        String answer,
        List<AiCitation> citations,
        boolean sufficient
) {
    AwardFactResolution {
        citations = List.copyOf(citations);
    }
}
