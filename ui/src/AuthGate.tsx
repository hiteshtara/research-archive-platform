import { CircularProgress, Stack, Typography } from "@mui/material";
import { useEffect, useState } from "react";

import { accessToken, login } from "./auth";

export function AuthGate({
  children,
}: {
  children: React.ReactNode;
}) {
  const [authenticated, setAuthenticated] = useState(false);
  const [checking, setChecking] = useState(true);

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

      await login();
    }

    void checkAuthentication();

    return () => {
      active = false;
    };
  }, []);

  if (checking || !authenticated) {
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

  return <>{children}</>;
}
