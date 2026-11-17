/**
 * The reason rule for every Security Administration write.
 *
 * Every grant, revoke, suspension, restore and administrator change writes an
 * audit row, and the reason is the only part of that row a later reviewer can
 * read as intent. "test", "n/a" or "asdf" satisfies a NOT NULL column and
 * explains nothing, so the UI refuses them before a request is built.
 *
 * This mirrors the server rule; it does not replace it. The server stays the
 * authority, and this exists so a user hears the rule from the form rather
 * than from a refused request.
 *
 * Plain JavaScript (with a .d.mts beside it) so node:test can exercise it the
 * same way as the other presentation helpers.
 */

export const REASON_MIN_LENGTH = 10;
export const REASON_MAX_LENGTH = 500;

/**
 * Placeholder phrases, already in the normalised form isPlaceholderReason
 * compares against: lower-case, punctuation removed, single-spaced. "n/a"
 * becomes "na"; "n / a" becomes "n a", so both are listed.
 */
export const PLACEHOLDER_PHRASES = [
  "test",
  "testing",
  "na",
  "n a",
  "none",
  "null",
  "reason",
  "no reason",
  "asdf",
  "qwerty",
  "xxx",
  "tbd",
  "todo",
  "placeholder",
  "see above",
  "as discussed",
  "per request",
  "lorem ipsum",
];

const PLACEHOLDER_TOKENS = PLACEHOLDER_PHRASES.map((phrase) => phrase.split(" "));

/** Trim and collapse every run of whitespace to one space. This is what is sent. */
export function normalizeReason(raw) {
  return String(raw ?? "").replace(/\s+/g, " ").trim();
}

/** Lower-case words with punctuation removed, for comparisons only. */
function comparableTokens(reason) {
  return normalizeReason(reason)
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s]/gu, "")
    .split(/\s+/)
    .filter((token) => token.length > 0);
}

/**
 * True when the reason is made up entirely of placeholder phrases, alone,
 * repeated or combined ("test", "N/A.", "test testing", "tbd - todo").
 * Punctuation and case are ignored.
 */
export function isPlaceholderReason(raw) {
  const tokens = comparableTokens(raw);
  if (tokens.length === 0) return false;
  // reachable[i]: the first i tokens can be covered entirely by placeholders.
  const reachable = new Array(tokens.length + 1).fill(false);
  reachable[0] = true;
  for (let i = 0; i < tokens.length; i += 1) {
    if (!reachable[i]) continue;
    for (const phrase of PLACEHOLDER_TOKENS) {
      const end = i + phrase.length;
      if (end <= tokens.length && phrase.every((word, k) => tokens[i + k] === word)) {
        reachable[end] = true;
      }
    }
  }
  return reachable[tokens.length];
}

/**
 * The single problem with a reason, or null when it is acceptable. The
 * message is written to be shown inline under the field.
 */
export function reasonProblem(raw) {
  const reason = normalizeReason(raw);
  if (reason.length === 0) {
    return "A reason is required.";
  }
  if (isPlaceholderReason(reason)) {
    return "Describe the actual reason; placeholder text is not accepted.";
  }
  if (reason.length < REASON_MIN_LENGTH) {
    return `A reason must be at least ${REASON_MIN_LENGTH} characters.`;
  }
  if (reason.length > REASON_MAX_LENGTH) {
    return `A reason must be at most ${REASON_MAX_LENGTH} characters.`;
  }
  const compact = reason.toLowerCase().replace(/[^\p{L}\p{N}]/gu, "");
  if (/^(.)\1*$/u.test(compact)) {
    return "A reason cannot be one character repeated.";
  }
  const tokens = comparableTokens(reason);
  if (tokens.length > 1 && tokens.every((token) => token === tokens[0])) {
    return "A reason cannot be one word repeated.";
  }
  const words = reason.split(" ").filter((word) => /\p{L}/u.test(word));
  if (words.length < 2) {
    return "A reason must be at least two words.";
  }
  return null;
}

export function isValidReason(raw) {
  return reasonProblem(raw) === null;
}
