/*
 * Telling "you have not been given access" apart from "the archive is empty",
 * without telling anyone which records exist.
 *
 * The API already separates three situations. The UI did not: `ui/src`
 * contained zero references to ACCESS_NOT_PROVISIONED, so a user in that state
 * got whatever the generic error path renders. That distinction is exactly
 * what the pilot's No-Grant Control exists to test, and it was being discarded
 * one layer before the user saw it.
 *
 *   403 ACCESS_NOT_PROVISIONED  no identity link - nobody has enrolled you
 *   403 ACCESS_DENIED           linked, but ambiguous, revoked or suspended
 *   404                         either out of scope OR genuinely absent
 *
 * The third one is the delicate one. RecordNotAccessibleException extends
 * NoSuchElementException and GlobalExceptionHandler declines to handle it on
 * purpose, so an out-of-scope record answers exactly as a record that does not
 * exist. That is what stops a scoped caller probing for records they cannot
 * see, and this module must not undo it: a 404 is rendered as "not found" and
 * never as "you do not have access to this", however tempting the latter is as
 * a help message.
 *
 * Getting that wrong would turn a deliberate ambiguity into a disclosure
 * channel while looking like an improvement in error messaging.
 */

/** What the user is actually in. */
export const ACCESS_STATES = {
  NOT_PROVISIONED: "NOT_PROVISIONED",
  IDENTITY_DENIED: "IDENTITY_DENIED",
  REAUTHENTICATION_REQUIRED: "REAUTHENTICATION_REQUIRED",
  NOT_FOUND: "NOT_FOUND",
  OTHER: "OTHER",
};

/**
 * Classify a failed request.
 *
 * Reads the API's own `code` where there is one, and falls back to the status.
 * Never guesses: an unrecognised 403 is IDENTITY_DENIED, not NOT_PROVISIONED,
 * because telling someone "nobody has enrolled you" when the real reason was a
 * suspension would send them to the wrong person.
 */
export function classifyAccessError(error) {
  if (!error || typeof error.status !== "number") {
    return ACCESS_STATES.OTHER;
  }
  if (error.status === 403) {
    return error.code === "ACCESS_NOT_PROVISIONED"
      ? ACCESS_STATES.NOT_PROVISIONED
      : ACCESS_STATES.IDENTITY_DENIED;
  }
  if (error.status === 401) {
    return ACCESS_STATES.REAUTHENTICATION_REQUIRED;
  }
  if (error.status === 404) {
    return ACCESS_STATES.NOT_FOUND;
  }
  return ACCESS_STATES.OTHER;
}

/**
 * What to show.
 *
 * Each state gets a title, a body and whether the page should offer a retry.
 * NOT_FOUND deliberately says nothing about permissions - see the module
 * comment.
 */
export function accessNotice(error, { recordLabel = "record" } = {}) {
  const state = classifyAccessError(error);

  switch (state) {
    case ACCESS_STATES.NOT_PROVISIONED:
      return {
        state,
        severity: "info",
        title: "Your access has not been set up yet",
        body:
          "You are signed in, but no archive access has been provisioned for " +
          "your account. This is not a fault, and the archive is not empty - " +
          "an administrator needs to grant you access before any records " +
          "appear.",
        action: "Contact the Research Archive administrators to request access.",
        retryable: false,
      };

    case ACCESS_STATES.IDENTITY_DENIED:
      return {
        state,
        severity: "warning",
        title: "Your account cannot be used to read the archive",
        body:
          "Your sign-in was accepted, but the archive cannot use it - the " +
          "account may have been withdrawn, suspended, or linked to more than " +
          "one person.",
        action: "Contact the Research Archive administrators, quoting the reference below.",
        retryable: false,
      };

    case ACCESS_STATES.REAUTHENTICATION_REQUIRED:
      return {
        state,
        severity: "info",
        title: "Please sign in again",
        body: "Your BU sign-in is too old for the archive to keep using it.",
        action: "Sign in again to continue.",
        retryable: true,
      };

    case ACCESS_STATES.NOT_FOUND:
      /*
       * Says "not found" and stops. An out-of-scope record and a record that
       * never existed answer identically from the API, and adding "or you may
       * not have permission to see it" would hand a caller the one bit the
       * 404 exists to withhold - that something is there.
       */
      return {
        state,
        severity: "info",
        title: `This ${recordLabel} was not found`,
        body: "Check the number you used. The archive has no such record to show.",
        action: null,
        retryable: false,
      };

    default:
      return {
        state,
        severity: "error",
        title: "Something went wrong",
        body: "The archive could not complete that request.",
        action: "Try again. If it keeps happening, report it with the reference below.",
        retryable: true,
      };
  }
}

/**
 * Is this the state where an empty screen would be misread as a broken site?
 *
 * Used to decide whether a page shows its empty state or this notice. A
 * dashboard reading zero because nobody granted you anything looks identical
 * to a dashboard reading zero because the load failed, and the difference
 * matters more than either number.
 */
export function shouldReplaceEmptyState(error) {
  const state = classifyAccessError(error);
  return state === ACCESS_STATES.NOT_PROVISIONED || state === ACCESS_STATES.IDENTITY_DENIED;
}

/**
 * Never show a correlation id for a 404.
 *
 * It is a support reference, and offering one invites the user to ask "why
 * can't I see this?" about a record that may not exist. For the access states
 * it is genuinely useful, because there the answer is "an administrator must
 * do something".
 */
export function supportReferenceFor(error) {
  const state = classifyAccessError(error);
  if (state === ACCESS_STATES.NOT_FOUND) {
    return null;
  }
  return error?.correlationId ?? null;
}
