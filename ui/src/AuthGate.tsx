import {
  Alert,
  Button,
  CircularProgress,
  Stack,
  Typography,
} from "@mui/material";
import { useEffect, useState } from "react";

import { accessToken, buIdentityProvider, login, wasSignedOut } from "./auth";
import { SignedOutPage } from "./SignedOutPage";

export function AuthGate({
  children,
}: {
  children: React.ReactNode;
}) {
  const [authenticated, setAuthenticated] = useState(false);
  const [failed, setFailed] = useState(false);
  const [signedOut, setSignedOut] = useState(false);

  useEffect(() => {
    let active = true;

    async function checkAuthentication() {
      // Checked BEFORE asking Amplify anything, and that order is load-bearing.
      //
      // Ending the Cognito session does not end BU's, so any route into the
      // identity provider after sign-out is answered with no prompt and an
      // authenticated page reappears. Testing showed the gate's own redirect
      // is not the only such route: with the marker set and no tokens, merely
      // calling the session API re-populated tokens and navigated, while the
      // marker stayed untouched - so it was Amplify re-authenticating, not
      // this component. Checking afterwards was therefore too late. When this
      // tab has signed out we ask Amplify nothing at all.
      if (wasSignedOut()) {
        setSignedOut(true);
        return;
      }

      const token = await accessToken();

      if (!active) {
        return;
      }

      if (token) {
        setAuthenticated(true);
        return;
      }

      // Otherwise, straight to the configured identity provider - BU Web
      // Login in deployed environments. No provider chooser and no native
      // sign-in screen: Cognito stays the gateway that issues tokens, but
      // users only ever see BU. Native accounts still exist in the pool for
      // emergency rollback and are reached by clearing
      // VITE_COGNITO_IDENTITY_PROVIDER and rebuilding, not from here.
      try {
        await login();
      } catch {
        if (active) {
          // Without this a failed redirect leaves a spinner forever, with no
          // indication of what went wrong or any way to retry.
          setFailed(true);
        }
      }
    }

    void checkAuthentication();

    // A page restored from the back/forward cache does not re-run the effect
    // above, so a cached authenticated view could reappear after sign-out with
    // no network request at all. Reload in that case and let the check run
    // again; the marker then produces the signed-out page.
    function onPageShow(event: PageTransitionEvent) {
      if (event.persisted && wasSignedOut()) {
        window.location.reload();
      }
    }

    window.addEventListener("pageshow", onPageShow);

    return () => {
      active = false;
      window.removeEventListener("pageshow", onPageShow);
    };
  }, []);

  if (signedOut) {
    return <SignedOutPage />;
  }

  if (authenticated) {
    return <>{children}</>;
  }

  if (failed) {
    return (
      <Stack
        spacing={2}
        sx={{
          minHeight: "100vh",
          alignItems: "center",
          justifyContent: "center",
          p: 2,
        }}
      >
        <Alert severity="error" sx={{ maxWidth: 440 }}>
          Sign-in could not be started.
          {buIdentityProvider
            ? " If this continues, BU Web Login may be unavailable — please contact the Research Archive team."
            : " Please try again, or contact the Research Archive team if this continues."}
        </Alert>
        <Button variant="contained" onClick={() => window.location.reload()}>
          Try again
        </Button>
      </Stack>
    );
  }

  return (
    <Stack
      spacing={2}
      sx={{
        minHeight: "100vh",
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <CircularProgress />
      <Typography color="text.secondary">
        {buIdentityProvider
          ? "Redirecting to BU Web Login…"
          : "Redirecting to secure sign-in…"}
      </Typography>
    </Stack>
  );
}
