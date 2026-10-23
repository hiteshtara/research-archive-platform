import { Amplify } from "aws-amplify";
import {
  fetchAuthSession,
  getCurrentUser,
  signInWithRedirect,
  signOut,
} from "aws-amplify/auth";

import { resolveOauthRedirects } from "./features/auth/authRedirects.mjs";

// Every value here is environment-specific (dev/test/prod each have their
// own Cognito User Pool and app client) and is injected at build time via
// Terraform's Amplify environment_variables - see terraform/environments/
// */main.tf. Nothing below should ever be a literal pool ID, client ID,
// domain, or URL: that couples the built UI to one specific AWS account
// and silently breaks whenever a different environment's build runs.
const awsRegion = import.meta.env.VITE_AWS_REGION;
const userPoolId = import.meta.env.VITE_COGNITO_USER_POOL_ID;
const userPoolClientId = import.meta.env.VITE_COGNITO_CLIENT_ID;
const cognitoDomain = import.meta.env.VITE_COGNITO_DOMAIN;
const redirectSignInUrl = import.meta.env.VITE_COGNITO_REDIRECT_URL;
const redirectSignOutUrl = import.meta.env.VITE_COGNITO_LOGOUT_URL;
// Optional. Approved origins this UI may redirect back to, mirroring the
// Cognito app client's callback URLs. Needed because one build is served at
// more than one approved origin; when unset, behaviour is exactly as before
// (the build-time redirect URL is used unconditionally).
const allowedOriginsRaw = import.meta.env.VITE_COGNITO_ALLOWED_ORIGINS;
// Optional. Name of a Cognito identity provider (e.g. "BUShibboleth") to offer
// as an explicit "Sign in with BU" option. Not a secret - just an identifier.
// Empty or unset means the sign-in screen behaves exactly as before: it
// redirects straight to the Cognito Hosted UI without asking.
const identityProviderName = import.meta.env.VITE_COGNITO_IDENTITY_PROVIDER;

for (const [name, value] of Object.entries({
  VITE_AWS_REGION: awsRegion,
  VITE_COGNITO_USER_POOL_ID: userPoolId,
  VITE_COGNITO_CLIENT_ID: userPoolClientId,
  VITE_COGNITO_DOMAIN: cognitoDomain,
  VITE_COGNITO_REDIRECT_URL: redirectSignInUrl,
  VITE_COGNITO_LOGOUT_URL: redirectSignOutUrl,
})) {
  if (!value) {
    throw new Error(`${name} is not configured.`);
  }
}

if (!userPoolId.startsWith(`${awsRegion}_`)) {
  throw new Error(
    `VITE_COGNITO_USER_POOL_ID (${userPoolId}) does not look like it belongs ` +
      `to VITE_AWS_REGION (${awsRegion}) - check the two values were not swapped.`,
  );
}

// Resolved at module load, from window.location.origin only - never from a
// query parameter, hash, or referrer - and only honoured when that origin is
// on the approved list. See features/auth/authRedirects.mjs.
const redirects = resolveOauthRedirects({
  currentOrigin: typeof window === "undefined" ? null : window.location.origin,
  allowedOriginsRaw,
  configuredSignInUrl: redirectSignInUrl,
  configuredSignOutUrl: redirectSignOutUrl,
});

Amplify.configure({
  Auth: {
    Cognito: {
      userPoolId,
      userPoolClientId,
      loginWith: {
        oauth: {
          domain: cognitoDomain,
          scopes: ["openid", "email", "profile"],
          redirectSignIn: [redirects.signIn],
          redirectSignOut: [redirects.signOut],
          responseType: "code",
        },
      },
    },
  },
});

// Name of the identity provider the "Sign in with BU" option uses, or null
// when BU login is not configured for this build (Terraform renders the
// variable as "" when unset, so the falsy check covers both). Exported so the
// sign-in screen can decide whether to offer a choice at all - when this is
// null the UI must behave exactly as it did before BU login existed.
export const buIdentityProvider: string | null = identityProviderName || null;

/**
 * Sign in through BU Web Login (Shibboleth).
 *
 * The redirect target still comes from the origin-aware resolver via
 * Amplify.configure above, so a user who starts at either approved origin
 * returns to that same origin after authenticating at the IdP.
 */
export async function loginWithBu(): Promise<void> {
  if (!buIdentityProvider) {
    throw new Error(
      "BU login is not configured for this build - " +
        "VITE_COGNITO_IDENTITY_PROVIDER is unset.",
    );
  }

  await signInWithRedirect({ provider: { custom: buIdentityProvider } });
}

/**
 * Sign in through Cognito's own Hosted UI, where native archive accounts live.
 *
 * Not reachable from the application interface when BU login is configured -
 * that is deliberate. It stays in the code as the emergency rollback path: if
 * BU federation breaks or BU's IdP is unavailable, clearing
 * VITE_COGNITO_IDENTITY_PROVIDER and rebuilding sends {@link login} here
 * again, and the native user pool accounts still work. Passing no provider is
 * what shows the Hosted UI.
 */
export async function loginWithCognito(): Promise<void> {
  await signInWithRedirect();
}

/**
 * The sign-in path the application uses.
 *
 * When BU login is configured this goes straight to BU Web Login, so users
 * never see Cognito's Hosted UI or a provider chooser. Cognito remains the
 * authentication gateway behind it - it issues the tokens and holds the user
 * pool; it simply stops being a screen anyone looks at.
 *
 * When unconfigured this falls back to the Hosted UI, which is both today's
 * behaviour and the emergency rollback path: clearing
 * VITE_COGNITO_IDENTITY_PROVIDER restores native sign-in without a code
 * change. See {@link loginWithCognito}.
 */
export async function login(): Promise<void> {
  if (buIdentityProvider) {
    await loginWithBu();
    return;
  }

  await loginWithCognito();
}

export async function logout(): Promise<void> {
  // A local sign-out, deliberately NOT { global: true }.
  //
  // global: true calls Cognito's GlobalSignOut API, which requires the
  // aws.cognito.signin.user.admin scope. This app client is scoped to
  // openid/email/profile on purpose, so that call always failed:
  //
  //   x-amz-target:        ...GlobalSignOut
  //   x-amzn-errortype:    NotAuthorizedException
  //   x-amzn-errormessage: Access Token does not have required scopes
  //
  // (captured from a real federated sign-out, 2026-10-22). Adding the scope
  // would widen this client's privileges to user administration just to log
  // out, so the call is dropped instead. Nothing is lost: the Hosted UI
  // /logout redirect below is what actually ends the Cognito session, and
  // sign-out already worked despite the failed GlobalSignOut.
  //
  // redirectUrl is passed explicitly because redirects.signOut may be on
  // another site - an identity provider's logout page, which is how the IdP
  // session is ended when federated login is enabled. Amplify throws
  // InvalidOriginException for a cross-origin sign-out URL unless it is given
  // here, and the first logout click then silently does nothing.
  // global is declared `global: boolean` (required, not optional) on
  // AuthSignOutInput, so it is passed explicitly as false rather than omitted;
  // omitting it while keeping oauth.redirectUrl does not typecheck.
  await signOut({ global: false, oauth: { redirectUrl: redirects.signOut } });

  // Ends the Cognito session and hands the browser to redirects.signOut, which
  // for a federated deployment is the IdP's own logout page. Cognito only
  // redirects to a URL on the app client's logout allow-list, so that page
  // must be listed in cognito_logout_urls.
  const logoutUrl =
    `https://${cognitoDomain}/logout` +
    `?client_id=${encodeURIComponent(userPoolClientId)}` +
    `&logout_uri=${encodeURIComponent(redirects.signOut)}`;

  window.location.assign(logoutUrl);
}

export async function currentUser() {
  return getCurrentUser();
}

export async function accessToken(): Promise<string | null> {
  try {
    const session = await fetchAuthSession();
    return session.tokens?.accessToken?.toString() ?? null;
  } catch {
    return null;
  }
}

// Frontend-side convenience only, mirroring what
// AttachmentAuthorizationService actually enforces server-side
// (ArchiveAttachmentViewer -> ROLE_ArchiveAttachmentViewer). This is
// used to hide navigation/UI affordances a user can't use anyway, never
// as the real access-control boundary - every attachment endpoint
// re-checks the real Cognito group on every request regardless of what
// this returns. A missing/malformed cognito:groups claim, or any
// failure resolving the session, is treated as "no access" (fails
// closed, never open).
const ATTACHMENT_VIEWER_GROUP = "ArchiveAttachmentViewer";

export async function hasAttachmentAccess(): Promise<boolean> {
  try {
    const session = await fetchAuthSession();
    const groups = session.tokens?.accessToken?.payload?.["cognito:groups"];
    return Array.isArray(groups) && groups.includes(ATTACHMENT_VIEWER_GROUP);
  } catch {
    return false;
  }
}
