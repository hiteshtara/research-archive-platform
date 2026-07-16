import { Amplify } from "aws-amplify";
import {
  fetchAuthSession,
  getCurrentUser,
  signInWithRedirect,
  signOut,
} from "aws-amplify/auth";

const amplifyUrl =
  "https://main.d33qc0afy3ltcj.amplifyapp.com/";

Amplify.configure({
  Auth: {
    Cognito: {
      userPoolId: "us-east-1_KnifXAgWm",
      userPoolClientId: "4svvnli76o8j2qtekkvasq7agc",
      loginWith: {
        oauth: {
          domain:
            "rap-dev-589744711110-1784159433.auth.us-east-1.amazoncognito.com",
          scopes: ["openid", "email", "profile"],
          redirectSignIn: [
            amplifyUrl,
            "http://localhost:5173/",
          ],
          redirectSignOut: [
            amplifyUrl,
            "http://localhost:5173/",
          ],
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
  await signOut();
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
