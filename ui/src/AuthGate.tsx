import {
  Alert,
  Button,
  CircularProgress,
  Stack,
  Typography,
} from "@mui/material";
import { useEffect, useState } from "react";

import { accessToken, buIdentityProvider, login } from "./auth";

export function AuthGate({
  children,
}: {
  children: React.ReactNode;
}) {
  const [authenticated, setAuthenticated] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;

    async function checkAuthentication() {
      const token = await accessToken();

      if (!active) {
        return;
      }

      if (token) {
        setAuthenticated(true);
        return;
      }

      // Straight to the configured identity provider - BU Web Login in
      // deployed environments. No provider chooser and no native sign-in
      // screen: Cognito stays the gateway that issues tokens, but users only
      // ever see BU. Native accounts still exist in the pool for emergency
      // rollback and are reached by clearing
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

    return () => {
      active = false;
    };
  }, []);

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
