import { CssBaseline, ThemeProvider } from "@mui/material";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter, Route, Routes } from "react-router-dom";

import App from "./App";
import { AuthGate } from "./AuthGate";
import { SignedOutPage } from "./SignedOutPage";
import { ApiRequestError } from "./api/client";
import { SIGNED_OUT_PATH } from "./auth";
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
        {/*
          The router is now OUTSIDE the auth gate so one public route can exist
          beside the gated application. AuthGate used to wrap the router, which
          meant every path - including the post-logout landing page - triggered
          a sign-in redirect before any route matched.
        */}
        <BrowserRouter>
          <Routes>
            <Route path={SIGNED_OUT_PATH} element={<SignedOutPage />} />
            <Route
              path="*"
              element={
                <AuthGate>
                  <App />
                </AuthGate>
              }
            />
          </Routes>
        </BrowserRouter>
      </ThemeProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
