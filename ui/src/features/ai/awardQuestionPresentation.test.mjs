import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  canSubmitAwardQuestion,
  showQuestionDevelopmentMetadata,
  suggestedAwardQuestions,
} from "./awardQuestionPresentation.mjs";

test("provides the approved suggested Award questions", () => {
  assert.deepEqual([...suggestedAwardQuestions], [
    "What is the current status?",
    "Who is the current principal investigator?",
    "Who is the sponsor?",
    "Compare the last two sequences",
    "Summarize the Award history",
  ]);
});

test("only enables submission for a valid question", () => {
  assert.equal(canSubmitAwardQuestion("", false), false);
  assert.equal(canSubmitAwardQuestion("  ", false), false);
  assert.equal(canSubmitAwardQuestion("x".repeat(501), false), false);
  assert.equal(canSubmitAwardQuestion("Status?", true), false);
  assert.equal(canSubmitAwardQuestion("Status?", false), true);
});

test("keeps provider details development-only", () => {
  assert.equal(showQuestionDevelopmentMetadata(false), false);
  assert.equal(showQuestionDevelopmentMetadata(true), true);
});

test("renders one answer and collapsed sources without chat history", () => {
  const component = readFileSync(
    new URL("./AwardAiQuestionPanel.tsx", import.meta.url),
    "utf8",
  );

  assert.match(component, /Ask a Question/);
  assert.match(component, /component="details"/);
  assert.doesNotMatch(component, /component="details"\s+open/);
  assert.match(component, /Sources \(\{orderedCitations\.length\}\)/);
  assert.match(component, /maxLength: 500/);
  assert.doesNotMatch(component, /transcript/i);
});
