// TEMPORARY preview harness - renders the INTEGRATED interface for design
// review without a Cognito session, mirroring the existing qaPreview.tsx
// pattern. Capabilities are injected as plain props; this bypasses no
// authorization, since the real app reads them from the server and every
// administrator endpoint enforces authorization independently.
// Not referenced by the application; delete with the design review.
import { Box, CssBaseline, Stack, ThemeProvider, Typography } from "@mui/material";
import { MemoryRouter } from "react-router-dom";
import React from "react";
import ReactDOM from "react-dom/client";

import { SecurityAdminUnavailable } from "./features/securityAdmin/SecurityAdminUnavailable";
import { SecurityAdminOverviewPage } from "./pages/securityAdmin/SecurityAdminOverviewPage";
import { ModeToggle } from "./features/securityAdmin/ModeToggle";
import { readCapabilities } from "./features/securityAdmin/securityAdminPresentation.mjs";
import "./index.css";
import { theme } from "./theme/theme";

const adminOn = readCapabilities({ administrator: true, enforcementEnabled: true, administrationAvailable: true });
const adminOff = readCapabilities({ administrator: true, enforcementEnabled: false, administrationAvailable: false });
const nonAdmin = readCapabilities({ administrator: false, enforcementEnabled: true, administrationAvailable: false });

function Header({ caps, label }: { caps: ReturnType<typeof readCapabilities>; label: string }) {
  return (
    <Box>
      <Typography variant="overline" color="text.secondary">{label}</Typography>
      <Box sx={{ mt: 1, border: "1px solid #e7e9ee", borderRadius: 2, backgroundColor: "#fff",
                 px: 2, py: 1.25, display: "flex", alignItems: "center", gap: 1.5 }}>
        <Box sx={{ width: 34, height: 34, borderRadius: 1.5, display: "grid", placeItems: "center",
                   backgroundColor: "primary.main", color: "#fff", fontWeight: 900, fontSize: 13 }}>BU</Box>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography variant="subtitle1" noWrap sx={{ fontWeight: 600, lineHeight: 1.2 }}>
            Boston University Research Data Hub
          </Typography>
          <Typography variant="caption" color="text.secondary">Legacy research administration archive</Typography>
        </Box>
        <Box sx={{ border: "1px solid #d7dbe3", borderRadius: 4, px: 1, py: 0.25, fontSize: 12 }}>Development</Box>
        <Typography variant="body2" color="text.secondary" noWrap>BUShibboleth_mukadder@bu.edu</Typography>
        <ModeToggle capabilities={caps} />
        <Box sx={{ border: "1px solid #c43c3c", color: "#c43c3c", borderRadius: 1, px: 1.25, py: 0.4,
                   fontSize: 13, fontWeight: 600 }}>Sign Out</Box>
      </Box>
    </Box>
  );
}

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <MemoryRouter initialEntries={["/admin/overview"]}>
        <Box sx={{ p: 4, backgroundColor: "#fbfcfd", minHeight: "100vh" }}>
          <Stack sx={{ gap: 4, maxWidth: 1240, mx: "auto" }}>
            <Header caps={adminOn} label="Header — administrator, enforcement ON (toggle shown, Security Admin selected)" />
            <Header caps={nonAdmin} label="Header — NON-administrator (no toggle, header identical to today)" />
            <Box>
              <Typography variant="overline" color="text.secondary">Security Administration — enforcement ON</Typography>
              <Box sx={{ mt: 1.5 }}><SecurityAdminOverviewPage capabilities={adminOn} activeGrants={2} /></Box>
            </Box>
            <Box>
              <Typography variant="overline" color="text.secondary">Enforcement OFF — administrator sees this instead</Typography>
              <Box sx={{ mt: 1.5 }}><SecurityAdminUnavailable capabilities={adminOff} /></Box>
            </Box>
          </Stack>
        </Box>
      </MemoryRouter>
    </ThemeProvider>
  </React.StrictMode>,
);
