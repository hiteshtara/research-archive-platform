// TEMPORARY preview harness - mounts the QA pages on their own, without
// AuthGate, so the page can be driven in a browser during QA without a
// Cognito session. Append #readiness to see the readiness page. Not
// referenced by the application and not part of the production build entry;
// delete with the QA status pages.
import { CssBaseline, ThemeProvider } from "@mui/material";
import { MemoryRouter } from "react-router-dom";
import React from "react";
import ReactDOM from "react-dom/client";

import { QaTestingPage } from "./pages/QaTestingPage";
import { SecurityReadinessPage } from "./pages/SecurityReadinessPage";
import "./index.css";
import { theme } from "./theme/theme";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      {/* Both pages link to each other, so they need a Router context. */}
      <MemoryRouter>
        <div style={{ padding: 24 }}>
          {window.location.hash === "#readiness" ? <SecurityReadinessPage /> : <QaTestingPage />}
        </div>
      </MemoryRouter>
    </ThemeProvider>
  </React.StrictMode>,
);
