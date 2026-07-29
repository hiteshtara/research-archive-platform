package edu.bu.archive.application.ai;

record AwardQuestionRoute(
        AwardQuestionIntent intent,
        Integer firstSequence,
        Integer secondSequence
) {
    static AwardQuestionRoute intent(
            AwardQuestionIntent intent
    ) {
        return new AwardQuestionRoute(intent, null, null);
    }
}
