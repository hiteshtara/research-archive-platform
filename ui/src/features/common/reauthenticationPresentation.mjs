// When the API refuses a request because the person's BU sign-in is too old.
//
// With record authorization enforced, the API answers 401 REAUTHENTICATION_REQUIRED once the
// token's auth_time is older than app.authorization.max-sign-in-age. A Cognito refresh keeps the
// original auth_time, so refreshing tokens cannot fix it: the person must sign in at BU again.
// The UI then sends them back through login with prompt=login, at most once per cooldown, so a
// sign-in that is still refused shows an error instead of looping through redirects.

export const REAUTHENTICATION_REQUIRED = "REAUTHENTICATION_REQUIRED";
export const REAUTHENTICATION_COOLDOWN_MS = 2 * 60 * 1000;
export const REAUTHENTICATION_MESSAGE =
  "Your sign-in has expired. Please sign in again to continue.";

export function isReauthenticationRequired(status, code) {
  return status === 401 && code === REAUTHENTICATION_REQUIRED;
}

// lastAttemptMs: when the UI last redirected for this reason (null if never or unreadable).
export function shouldRedirectToSignIn(lastAttemptMs, nowMs) {
  if (typeof lastAttemptMs !== "number" || !Number.isFinite(lastAttemptMs)) {
    return true;
  }
  return nowMs - lastAttemptMs >= REAUTHENTICATION_COOLDOWN_MS || nowMs < lastAttemptMs;
}
