import { Amplify } from "aws-amplify";

import { attachmentControlsAvailable } from "./features/common/attachmentAccessPresentation.mjs";
import { shouldRedirectToSignIn } from "./features/common/reauthenticationPresentation.mjs";
import {
  fetchAuthSession,
  getCurrentUser,
  signInWithRedirect,
  signOut,
} from "aws-amplify/auth";

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

Amplify.configure({
  Auth: {
    Cognito: {
      userPoolId,
      userPoolClientId,
      loginWith: {
        oauth: {
          domain: cognitoDomain,
          scopes: ["openid", "email", "profile"],
          redirectSignIn: [redirectSignInUrl],
          redirectSignOut: [redirectSignOutUrl],
          responseType: "code",
        },
      },
    },
  },
});

export async function login(): Promise<void> {
  await signInWithRedirect();
}

const REAUTHENTICATION_ATTEMPT_KEY = "archive.reauthentication.lastAttempt";

// The API refused the sign-in as too old (401 REAUTHENTICATION_REQUIRED). Refreshing tokens
// keeps the old auth_time, so send the person through BU login again (prompt=login), at most
// once per cooldown: a sign-in the API still refuses then shows an error rather than looping.
export async function reauthenticate(): Promise<boolean> {
  let lastAttempt: number | null = null;
  try {
    const stored = window.sessionStorage.getItem(REAUTHENTICATION_ATTEMPT_KEY);
    lastAttempt = stored === null ? null : Number(stored);
  } catch {
    lastAttempt = null;
  }
  const now = Date.now();
  if (!shouldRedirectToSignIn(lastAttempt, now)) {
    return false;
  }
  try {
    window.sessionStorage.setItem(REAUTHENTICATION_ATTEMPT_KEY, String(now));
  } catch {
    // Without storage the cooldown cannot be kept; still redirect once.
  }
  await signInWithRedirect({ options: { prompt: "LOGIN" } });
  return true;
}

export async function logout(): Promise<void> {
  await signOut({ global: true });

  const logoutUrl =
    `https://${cognitoDomain}/logout` +
    `?client_id=${encodeURIComponent(userPoolClientId)}` +
    `&logout_uri=${encodeURIComponent(redirectSignOutUrl)}`;

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

// Frontend-side convenience only, mirroring what AttachmentAuthorizationService
// enforces server-side; never the access-control boundary (every attachment and
// report endpoint re-checks on every request). With record authorization enforced
// (approved 2026-10-01) a record's own files follow the record, so the controls are
// offered without ArchiveAttachmentViewer; with enforcement off the group is still
// required. Any failure resolving either input is treated as "no access".
export async function hasAttachmentAccess(): Promise<boolean> {
  try {
    const session = await fetchAuthSession();
    const groups = session.tokens?.accessToken?.payload?.["cognito:groups"];
    const token = session.tokens?.accessToken?.toString();
    let status: { mode?: string; problem?: string | null } | null = null;
    if (token) {
      const response = await fetch(`${import.meta.env.VITE_API_BASE_URL}/api/v1/me/access`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      status = response.ok ? await response.json() : null;
    }
    return attachmentControlsAvailable(status, groups);
  } catch {
    return false;
  }
}
