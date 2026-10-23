import { Button, Paper, Stack, Typography } from "@mui/material";

import { login } from "./auth";

/**
 * Where Cognito drops the browser after ending its session.
 *
 * Deliberately NOT behind {@link AuthGate}. Returning to "/" after logout is
 * what made sign-out appear to do nothing: that route is gated, so it started
 * a fresh BU redirect immediately, and BU's still-valid SSO session signed the
 * user straight back in without a prompt. This page is the terminal state
 * instead - nothing here authenticates until the user asks for it.
 */
export function SignedOutPage() {
  return (
    <Stack
      sx={{
        minHeight: "100vh",
        alignItems: "center",
        justifyContent: "center",
        p: 2,
      }}
    >
      <Paper sx={{ p: 4, maxWidth: 480, width: "100%" }}>
        <Stack spacing={3}>
          <Typography variant="h6" component="h1">
            You have signed out of Research Archive. Your BU single sign-on
            session may still be active. Close your browser to end the browser
            session.
          </Typography>

          <Button
            variant="contained"
            size="large"
            onClick={() => {
              void login();
            }}
          >
            Sign in again
          </Button>
        </Stack>
      </Paper>
    </Stack>
  );
}
