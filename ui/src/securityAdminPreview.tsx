// TEMPORARY preview harness - renders the Security Administration screens on
// their own for design review, without AuthGate or a Cognito session. Mirrors
// the existing qaPreview.tsx pattern. Capabilities are passed as plain props,
// so this bypasses no authorization: the real app reads them from the server
// and every administrator API enforces authorization independently.
// Not referenced by the application; delete with the design review.
import { Box, CssBaseline, Stack, ThemeProvider, Typography } from "@mui/material";
import { MemoryRouter } from "react-router-dom";
import React from "react";
import ReactDOM from "react-dom/client";

import { SecurityAdminUnavailable } from "./features/securityAdmin/SecurityAdminUnavailable";
import { SecurityAdminOverviewPage } from "./pages/securityAdmin/SecurityAdminOverviewPage";
import { readCapabilities } from "./features/securityAdmin/securityAdminPresentation.mjs";
import "./index.css";
import { theme } from "./theme/theme";

const adminEnforcementOn = readCapabilities({
  administrator: true, enforcementEnabled: true, administrationAvailable: true,
});
const adminEnforcementOff = readCapabilities({
  administrator: true, enforcementEnabled: false, administrationAvailable: false,
});

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <MemoryRouter>
        <Box sx={{ p: 4, backgroundColor: "#fbfcfd", minHeight: "100vh" }}>
          <Stack sx={{ gap: 5, maxWidth: 1180, mx: "auto" }}>
            <Box>
              <Typography variant="overline" color="text.secondary">
                Enforcement ON — administration available
              </Typography>
              <Box sx={{ mt: 1.5 }}>
                <SecurityAdminOverviewPage capabilities={adminEnforcementOn} activeGrants={2} />
              </Box>
            </Box>

            <Box>
              <Typography variant="overline" color="text.secondary">
                Enforcement ON — grant count not yet loaded (never defaulted to a number)
              </Typography>
              <Box sx={{ mt: 1.5 }}>
                <SecurityAdminOverviewPage capabilities={adminEnforcementOn} />
              </Box>
            </Box>

            <Box>
              <Typography variant="overline" color="text.secondary">
                Enforcement OFF — administrator sees the unavailable screen
              </Typography>
              <Box sx={{ mt: 1.5 }}>
                <SecurityAdminUnavailable capabilities={adminEnforcementOff} />
              </Box>
            </Box>
          </Stack>
        </Box>
      </MemoryRouter>
    </ThemeProvider>
  </React.StrictMode>,
);
