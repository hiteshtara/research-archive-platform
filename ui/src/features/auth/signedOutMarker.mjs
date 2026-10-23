// Records that the user signed out deliberately, so the auth gate stops
// redirecting on its own.
//
// Needed because ending the Cognito session does not end BU's. Without this
// marker, any navigation back into a gated route after sign-out - the browser
// Back button most obviously - finds no token, redirects to the identity
// provider, and a still-valid SSO session returns a token with no prompt. The
// user is then looking at an authenticated archive page immediately after
// signing out.
//
// Session-scoped, not persistent: the marker should last exactly as long as
// the tab, because the SSO session it compensates for is also a
// browser-session thing. Closing the browser - which is what actually ends
// the identity provider's session - clears it too.
//
// Every access is guarded. Storage throws in some privacy modes, and a storage
// failure must never break sign-in or sign-out; it degrades to the previous
// behaviour, where Back may re-authenticate silently.

export const SIGNED_OUT_MARKER = "archive.signedOut";

/**
 * @param {Pick<Storage, "setItem">} storage
 */
export function markSignedOut(storage) {
  try {
    storage.setItem(SIGNED_OUT_MARKER, "1");
  } catch {
    // Ignored deliberately - see the note above.
  }
}

/**
 * @param {Pick<Storage, "removeItem">} storage
 */
export function clearSignedOutMarker(storage) {
  try {
    storage.removeItem(SIGNED_OUT_MARKER);
  } catch {
    // A marker that cannot be written cannot block sign-in either.
  }
}

/**
 * True when this tab signed out and has not deliberately signed in since.
 *
 * @param {Pick<Storage, "getItem">} storage
 * @returns {boolean}
 */
export function wasSignedOut(storage) {
  try {
    return storage.getItem(SIGNED_OUT_MARKER) === "1";
  } catch {
    // Fail open: an unreadable marker must not strand a user who is not
    // actually signed out on a signed-out page.
    return false;
  }
}
