import {
  Alert,
  Button,
  CircularProgress,
  Divider,
  Paper,
  Stack,
  Typography,
} from "@mui/material";
import { useEffect, useState } from "react";

import {
  accessToken,
  buIdentityProvider,
  login,
  loginWithBu,
  loginWithCognito,
} from "./auth";

export function AuthGate({
  children,
}: {
  children: React.ReactNode;
}) {
  const [authenticated, setAuthenticated] = useState(false);
  const [checking, setChecking] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;

    async function checkAuthentication() {
      const token = await accessToken();

      if (!active) {
        return;
      }

      if (token) {
        setAuthenticated(true);
        setChecking(false);
        return;
      }

      // With BU login configured, offer the choice rather than picking for the
      // user: sending everyone straight to BU would take native archive
      // accounts off the screen entirely, and they are the fallback if BU
      // federation is misconfigured or BU's IdP is down. With it unconfigured
      // this redirects immediately, exactly as it always has.
      if (buIdentityProvider) {
        setChecking(false);
        return;
      }

      await login();
    }

    void checkAuthentication();

    return () => {
      active = false;
    };
  }, []);

  if (authenticated) {
    return <>{children}</>;
  }

  if (checking) {
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
          Redirecting to secure sign-in…
        </Typography>
      </Stack>
    );
  }

  async function choose(signIn: () => Promise<void>) {
    setError(null);
    try {
      await signIn();
    } catch {
      // A failed redirect otherwise leaves the user on a dead screen with no
      // way to try the other option.
      setError("Sign-in could not be started. Please try again.");
    }
  }

  return (
    <Stack
      sx={{
        minHeight: "100vh",
        alignItems: "center",
        justifyContent: "center",
        p: 2,
      }}
    >
      <Paper sx={{ p: 4, maxWidth: 420, width: "100%" }}>
        <Stack spacing={3}>
          <Stack spacing={1}>
            <Typography variant="h6" component="h1">
              Research Archive
            </Typography>
            <Typography color="text.secondary" variant="body2">
              Sign in to continue.
            </Typography>
          </Stack>

          {error ? <Alert severity="error">{error}</Alert> : null}

          <Stack spacing={1.5}>
            <Button
              variant="contained"
              size="large"
              fullWidth
              onClick={() => void choose(loginWithBu)}
            >
              Sign in with BU
            </Button>

            <Divider>or</Divider>

            <Button
              variant="outlined"
              size="large"
              fullWidth
              onClick={() => void choose(loginWithCognito)}
            >
              Sign in with Cognito
            </Button>
          </Stack>

          <Typography color="text.secondary" variant="caption">
            Use BU if you have a BU login. Use Cognito for an archive account
            issued directly by the project.
          </Typography>
        </Stack>
      </Paper>
    </Stack>
  );
}
