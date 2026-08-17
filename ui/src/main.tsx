import { CssBaseline, ThemeProvider } from "@mui/material";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";

import App from "./App";
import { AuthGate } from "./AuthGate";
import { ApiRequestError } from "./api/client";
import "./index.css";
import { theme } from "./theme/theme";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        if (
          error instanceof ApiRequestError &&
          [401, 403].includes(error.status)
        ) {
          return false;
        }

        return failureCount < 1;
      },
      // A read-only, preserved historical archive - see CLAUDE.md - is
      // never mutated by user activity and is refreshed only by
      // periodic/manual ETL loads, not live traffic, so a short
      // staleTime buys no real freshness and just forces a redundant
      // ~1-2s network round-trip on every revisit to the same query
      // within a session (e.g. navigating away from a search and back).
      staleTime: 5 * 60_000,
      refetchOnWindowFocus: false,
    },
  },
});

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <AuthGate>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </AuthGate>
      </ThemeProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
