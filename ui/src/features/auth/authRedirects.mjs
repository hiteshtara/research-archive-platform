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
 * The identity-provider logout endpoints this application will hand the
 * browser to on sign-out. An exhaustive allow-list, deliberately held in
 * source rather than in configuration.
 *
 * Configuration cannot be its own safeguard: if the approved list lived in
 * tfvars beside the value it validates, a typo or an unreviewed edit would
 * defeat both at once. Here, changing where sign-out sends users requires a
 * code change and a review.
 *
 * Only /idp/profile/Logout is listed. It is the IdP's real logout flow, and a
 * clean-browser test on 2026-10-23 confirmed it deletes
 * __Host-shib_idp_session and forces a fresh password challenge.
 *
 * /idp/logout.jsp is deliberately NOT listed, even though it answers 200 with
 * "You have successfully logged out". The same test showed it leaves
 * __Host-shib_idp_session intact and the user is silently signed back in: it
 * emits deletion cookies for shib_idp_session, shib_idp_session_ss,
 * _idp_session and JSESSIONID at Path=/idp, which are not the names the IdP
 * actually sets. It is a legacy page predating the __Host- prefix.
 *
 * shib.bu.edu is listed so a production rollout needs no code change, but it
 * is UNVERIFIED - run the same clean-browser test against it before relying
 * on it in production.
 */
export const APPROVED_IDP_LOGOUT_URLS = [
  "https://shib-test.bu.edu/idp/profile/Logout",
  "https://shib.bu.edu/idp/profile/Logout",
];

/**
 * Compare two URLs as URLs rather than as strings, so a trailing slash or
 * host capitalisation does not silently defeat the allow-list. Returns null
 * for anything unparseable, which never matches.
 *
 * @param {string | undefined | null} value
 * @returns {string | null}
 */
function canonicalUrl(value) {
  if (!value) {
    return null;
  }

  try {
    const url = new URL(value);
    return `${url.origin}${url.pathname.replace(/\/$/, "")}${url.search}`;
  } catch {
    return null;
  }
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
 *   approvedIdpLogoutUrls?: string[],
 * }} input
 * @returns {{
 *   signIn: string,
 *   signOut: string,
 *   allowedOrigins: string[],
 *   originAware: boolean,
 *   signOutUsesIdpLogout: boolean,
 * }}
 */
export function resolveOauthRedirects({
  currentOrigin,
  allowedOriginsRaw,
  configuredSignInUrl,
  configuredSignOutUrl,
  signOutPath = "/",
  approvedIdpLogoutUrls = APPROVED_IDP_LOGOUT_URLS,
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

  // Where sign-out sends the browser, decided in three cases.
  //
  //   1. One of our own origins   -> rewrite to signOutPath (/signed-out).
  //   2. An APPROVED IdP logout   -> use verbatim; this is what ends the
  //                                  IdP session.
  //   3. Anything else            -> FAIL CLOSED to case 1.
  //
  // Case 3 matters. "Not one of our origins" is not the same as "an endpoint
  // that logs the user out of BU" - /idp/logout.jsp is external, returns 200,
  // says "You have successfully logged out", and leaves the session intact.
  // Treating any external URL as an IdP logout would also let a mistaken or
  // hostile configuration value become a redirect target on sign-out. So only
  // the reviewed allow-list above is trusted, and everything else degrades to
  // the behaviour we already know is safe.
  const configuredSignOutOrigin = originOf(configuredSignOutUrl);
  const signOutIsOwnOrigin =
    configuredSignOutOrigin !== null && allowedOrigins.includes(configuredSignOutOrigin);

  const approved = (approvedIdpLogoutUrls || []).map(canonicalUrl).filter((u) => u !== null);
  const candidate = canonicalUrl(configuredSignOutUrl);

  // True only when sign-out hands the browser to a reviewed IdP logout
  // endpoint. Named for what it is - which endpoint we are USING - not for
  // what we hope BU does with it. Whether the IdP session actually ends is a
  // fact about BU, established by testing, and no value in this process can
  // assert it.
  const signOutUsesIdpLogout =
    !signOutIsOwnOrigin && candidate !== null && approved.includes(candidate);

  // The origin to fall back to for cases 1 and 3.
  const ownOrigin = originAware ? currentOrigin : configuredSignOutOrigin ?? configuredSignInOrigin;

  const signOut = signOutUsesIdpLogout
    ? configuredSignOutUrl
    : ownOrigin !== null && ownOrigin !== undefined
      ? `${ownOrigin}${signOutPath}`
      : configuredSignOutUrl;

  return {
    signIn: originAware ? `${currentOrigin}/` : configuredSignInUrl,
    signOut,
    allowedOrigins,
    originAware,
    signOutUsesIdpLogout,
  };
}
