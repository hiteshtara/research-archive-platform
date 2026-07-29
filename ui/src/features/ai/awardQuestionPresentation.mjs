export const suggestedAwardQuestions = Object.freeze([
  "What is the current status?",
  "Who is the current principal investigator?",
  "Who is the sponsor?",
  "Compare the last two sequences",
  "Summarize the Award history",
]);

export function canSubmitAwardQuestion(question, pending) {
  const length = question.trim().length;
  return !pending && length > 0 && length <= 500;
}

export function showQuestionDevelopmentMetadata(development) {
  return development;
}
