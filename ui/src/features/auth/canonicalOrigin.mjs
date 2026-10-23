// Canonical-origin redirect.
//
// One Amplify build is served at more than one origin: the retired default
// *.amplifyapp.com URL and the official custom domain. Amplify Hosting cannot
// disable the default domain - the App API exposes defaultDomain as output
// only, enableBasicAuth is app/branch-wide (so it would gate the custom domain
// too), CustomRule.condition matches a country code rather than a Host, and
// the CloudFront distribution is AWS-managed and not in this account. So the
// redirect is done here, in the application.
//
// This is a courtesy redirect, NOT an access control: the bundle is public
// static content on both hosts, and anyone can read it either way. Its purpose
// is to make sure people and bookmarks converge on one advertised URL.
//
// Security model:
//
//   * The target is configuration (VITE_CANONICAL_ORIGIN), never anything
//     taken from the current URL. A page cannot nominate its own redirect
//     target.
//   * OAuth and token parameters are STRIPPED, never forwarded. An
//     authorization code is bound to the redirect_uri it was issued for, so
//     forwarding one would both fail and needlessly copy a credential onto
//     another origin. The in-flight sign-in is abandoned and restarted on the
//     canonical origin instead, which costs one silent round trip.
//   * Local development is never redirected.
//   * Redirecting to the origin already being viewed is impossible, which is
//     what prevents a loop.

/**
 * Query/fragment parameters that must never be carried across origins.
 * Authorization-code and implicit-flow artefacts, plus the CSRF state that
 * only means anything to the origin that generated it.
 */
export const SENSITIVE_AUTH_PARAMS = [
  "code",
  "state",
  "session_state",
  "nonce",
  "code_verifier",
  "code_challenge",
  "error",
  "error_description",
  "error_uri",
  "id_token",
  "access_token",
  "refresh_token",
  "token",
];

/**
 * True for a loopback host, which must never be redirected - otherwise local
 * development against a deployed-style configuration would bounce to the
 * deployed site.
 *
 * @param {string} hostname
 * @returns {boolean}
 */
export function isLoopbackHostname(hostname) {
  return (
    hostname === "localhost" ||
    hostname === "127.0.0.1" ||
    hostname === "[::1]" ||
    hostname === "::1" ||
    hostname.endsWith(".localhost")
  );
}

/**
 * Where the browser should go to reach the canonical origin, or null to stay
 * put.
 *
 * @param {{ canonicalOrigin?: string | null, currentHref?: string | null }} input
 * @returns {string | null}
 */
export function resolveCanonicalRedirect({ canonicalOrigin, currentHref }) {
  // Unset is the rollback switch: clear VITE_CANONICAL_ORIGIN, rebuild, and
  // every origin serves the application directly again.
  if (!canonicalOrigin || !currentHref) {
    return null;
  }

  let canonical;
  let here;

  try {
    canonical = new URL(canonicalOrigin).origin;
    here = new URL(currentHref);
  } catch {
    // Misconfiguration must not take the application down; serve where we are.
    return null;
  }

  // Already canonical. This is the loop guard: the only possible target is an
  // origin different from the current one.
  if (here.origin === canonical) {
    return null;
  }

  if (isLoopbackHostname(here.hostname)) {
    return null;
  }

  const params = new URLSearchParams(here.search);
  for (const name of SENSITIVE_AUTH_PARAMS) {
    params.delete(name);
  }

  const query = params.toString();

  // Implicit-flow responses arrive in the fragment. Drop the whole fragment
  // when it carries any auth artefact rather than trying to edit it - a
  // fragment is never load-bearing enough to risk leaking a token for.
  const fragment = here.hash.replace(/^#/, "");
  const fragmentIsSensitive =
    fragment.length > 0 &&
    SENSITIVE_AUTH_PARAMS.some((name) =>
      new RegExp(`(^|[&;])${name}=`).test(fragment),
    );

  return (
    canonical +
    here.pathname +
    (query ? `?${query}` : "") +
    (fragment && !fragmentIsSensitive ? `#${fragment}` : "")
  );
}
