// Origin-aware OAuth redirect resolution.
//
// The deployed UI is reachable at more than one approved origin (the Amplify
// *.amplifyapp.com URL and the BU custom domain), and local dev runs at
// http://localhost:5173. A single build-time VITE_COGNITO_REDIRECT_URL sends
// every user back to whichever origin was baked into the bundle, so a login
// started at one address finishes at another. These helpers pick the redirect
// that matches the origin the user is actually on.
//
// Security model:
//
//   * The redirect origin is NEVER taken from a URL query parameter, hash,
//     referrer, or any other attacker-influenced input. The only candidate is
//     window.location.origin - the origin already executing this code.
//   * That candidate is used only when it appears in an explicit allow-list
//     built from configuration that mirrors the Cognito app client's callback
//     URLs. An origin that is not on the list falls back to the build-time
//     value, so an unapproved host cannot nominate itself as a redirect
//     target.
//   * Cognito independently rejects any redirect_uri outside its own callback
//     list (redirect_mismatch), so this is defence in depth rather than the
//     only control.

/**
 * Origin of a URL, or null when it cannot be parsed.
 *
 * @param {string | undefined | null} value
 * @returns {string | null}
 */
export function originOf(value) {
  if (!value) {
    return null;
  }

  try {
    return new URL(value).origin;
  } catch {
    return null;
  }
}

/**
 * Parse the configured allow-list. Accepts full URLs or bare origins,
 * separated by commas and/or whitespace, and ignores anything unparseable
 * rather than throwing - a malformed entry must not take authentication down.
 *
 * @param {string | undefined | null} raw
 * @returns {string[]}
 */
export function parseAllowedOrigins(raw) {
  if (!raw) {
    return [];
  }

  const origins = [];

  for (const entry of String(raw).split(/[,\s]+/)) {
    const origin = originOf(entry.trim());

    if (origin !== null && !origins.includes(origin)) {
      origins.push(origin);
    }
  }

  return origins;
}

/**
 * Decide which sign-in and sign-out URLs to hand to Amplify.
 *
 * @param {{
 *   currentOrigin?: string | null,
 *   allowedOriginsRaw?: string | null,
 *   configuredSignInUrl: string,
 *   configuredSignOutUrl: string,
 *   signOutPath?: string,
 * }} input
 * @returns {{
 *   signIn: string,
 *   signOut: string,
 *   allowedOrigins: string[],
 *   originAware: boolean,
 * }}
 */
export function resolveOauthRedirects({
  currentOrigin,
  allowedOriginsRaw,
  configuredSignInUrl,
  configuredSignOutUrl,
  signOutPath = "/",
}) {
  // The build-time sign-in origin is always approved: it is, by definition,
  // already a Cognito callback URL. The sign-out origin is deliberately NOT
  // added - it may legitimately point at an external identity-provider logout
  // page (BU Shibboleth's /idp/logout.jsp), which is an allowed Cognito
  // logout target but never an origin this application is served from.
  const allowedOrigins = parseAllowedOrigins(allowedOriginsRaw);
  const configuredSignInOrigin = originOf(configuredSignInUrl);

  if (configuredSignInOrigin !== null && !allowedOrigins.includes(configuredSignInOrigin)) {
    allowedOrigins.push(configuredSignInOrigin);
  }

  const originAware =
    typeof currentOrigin === "string" &&
    currentOrigin.length > 0 &&
    allowedOrigins.includes(currentOrigin);

  // Sign-out only follows the current origin when the configured sign-out
  // target is one of this application's own origins. When it points somewhere
  // else - an identity provider's logout page - that is a deliberate choice
  // about ending the IdP session, and overriding it would silently re-sign the
  // user straight back in.
  const configuredSignOutOrigin = originOf(configuredSignOutUrl);
  const signOutIsOwnOrigin =
    configuredSignOutOrigin !== null && allowedOrigins.includes(configuredSignOutOrigin);

  // signOutPath is where Cognito drops the browser after ending its session.
  // It defaults to "/" - the historical behaviour - but the application sends
  // users to a public post-logout page instead. Landing back on "/" is what
  // made sign-out appear to do nothing: that route is behind the auth gate, so
  // it immediately started a fresh BU redirect and BU's still-valid SSO
  // session signed the user straight back in. The path is applied only to our
  // own origins, never to an external identity-provider logout URL.
  if (!originAware) {
    return {
      signIn: configuredSignInUrl,
      signOut: signOutIsOwnOrigin
        ? `${configuredSignOutOrigin}${signOutPath}`
        : configuredSignOutUrl,
      allowedOrigins,
      originAware: false,
    };
  }

  return {
    signIn: `${currentOrigin}/`,
    signOut: signOutIsOwnOrigin ? `${currentOrigin}${signOutPath}` : configuredSignOutUrl,
    allowedOrigins,
    originAware: true,
  };
}
