import { Amplify } from "aws-amplify";
import {
  fetchAuthSession,
  getCurrentUser,
  signInWithRedirect,
  signOut,
} from "aws-amplify/auth";

const userPoolClientId = "4svvnli76o8j2qtekkvasq7agc";
const cognitoDomain =
  "rap-dev-589744711110-1784159433.auth.us-east-1.amazoncognito.com";

const productionUrl =
  "https://main.d33qc0afy3ltcj.amplifyapp.com/";
const localUrl = "http://localhost:5173/";

Amplify.configure({
  Auth: {
    Cognito: {
      userPoolId: "us-east-1_KnifXAgWm",
      userPoolClientId,
      loginWith: {
        oauth: {
          domain: cognitoDomain,
          scopes: ["openid", "email", "profile"],
          redirectSignIn: [productionUrl, localUrl],
          redirectSignOut: [productionUrl, localUrl],
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

  const logoutUri = window.location.hostname === "localhost"
    ? localUrl
    : productionUrl;

  const logoutUrl =
    `https://${cognitoDomain}/logout` +
    `?client_id=${encodeURIComponent(userPoolClientId)}` +
    `&logout_uri=${encodeURIComponent(logoutUri)}`;

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
