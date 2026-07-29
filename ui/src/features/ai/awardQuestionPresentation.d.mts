export const suggestedAwardQuestions: readonly string[];

export function canSubmitAwardQuestion(
  question: string,
  pending: boolean,
): boolean;

export function showQuestionDevelopmentMetadata(
  development: boolean,
): boolean;
