import { Amplify } from "aws-amplify";
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
