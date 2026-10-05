// TEMPORARY preview harness - mounts QaStatusPage on its own, without
// AuthGate, so the page can be driven in a browser during QA without a
// Cognito session. Not referenced by the application and not part of the
// production build entry; delete with the QA status page.
import { CssBaseline, ThemeProvider } from "@mui/material";
import React from "react";
import ReactDOM from "react-dom/client";

import { QaStatusPage } from "./pages/QaStatusPage";
import "./index.css";
import { theme } from "./theme/theme";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <div style={{ padding: 24 }}>
        <QaStatusPage />
      </div>
    </ThemeProvider>
  </React.StrictMode>,
);
