import { CssBaseline, ThemeProvider } from "@mui/material";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter, Route, Routes } from "react-router-dom";

import { Suspense, lazy } from "react";

import App from "./App";
import { AuthGate } from "./AuthGate";
import { SignedOutPage } from "./SignedOutPage";
import { ApiRequestError } from "./api/client";
import { SIGNED_OUT_PATH } from "./auth";
import { resolveCanonicalRedirect } from "./features/auth/canonicalOrigin.mjs";
import "./index.css";
import { theme } from "./theme/theme";

// Converge on the one advertised URL before anything else runs - in
// particular before ./auth configures Amplify and AuthGate starts a sign-in
// redirect, so a visitor to the retired Amplify URL authenticates against the
// canonical origin rather than being sent back to the old one afterwards.
//
// Unset VITE_CANONICAL_ORIGIN disables this completely, which is the rollback
// if the custom domain ever becomes unavailable. Returns null for the
// canonical origin itself and for localhost, so there is no loop and no effect
// on local development. OAuth parameters are stripped rather than forwarded -
// see features/auth/canonicalOrigin.mjs.
const canonicalRedirect = resolveCanonicalRedirect({
  canonicalOrigin: import.meta.env.VITE_CANONICAL_ORIGIN,
  currentHref: typeof window === "undefined" ? null : window.location.href,
});

if (canonicalRedirect) {
  // replace(), not assign(): the retired URL should not sit in history as a
  // back-button target that bounces straight forward again.
  window.location.replace(canonicalRedirect);
}

/*
 * View Access As: a TEMPORARY QA route, present in development builds only.
 *
 * The dynamic import sits inside an `import.meta.env.DEV` branch on purpose.
 * Vite replaces that expression with a literal `false` when building for
 * production, so the branch is dead code and Rollup never emits the chunk -
 * the page, its presentation module and the harness URL are all absent from
 * dist/. That is the requirement: excluded from the production build, not
 * merely hidden from navigation. A static `import` at the top of this file
 * would ship the code and only hide the link.
 *
 * It is mounted OUTSIDE AuthGate, beside the signed-out page, for the same
 * reason that route is: the page reads nothing from the production API. Every
 * decision it shows comes from a test-scope harness on loopback, so a Cognito
 * session would authorise nothing and its absence hides nothing. Gating it
 * would only stop a developer opening it. This is not an auth bypass for the
 * archive: the route renders one simulator page, reaches no archive data, and
 * does not exist at all in a production build.
 *
 * Deliberately NOT in navigationPresentation.mjs either: a nav entry would put
 * the label and path into the production bundle even though the route is gone.
 *
 * Verified by `npm run verify:dev-only-routes` against a production build, and
 * by viewAccessAsPresentation.test.mjs, which asserts this guard is intact.
 * Removal checklist: docs/VIEW_ACCESS_AS_PAGE.md.
 */
const ViewAccessAsPage = import.meta.env.DEV
  ? lazy(() => import("./pages/ViewAccessAsPage"))
  : null;

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

// Nothing is mounted while leaving for the canonical origin.
// window.location.replace() does not halt this script, so without the guard
// the application would mount and AuthGate could start a sign-in redirect
// against the retired origin in the moment before the browser navigates.
if (!canonicalRedirect) {
  ReactDOM.createRoot(document.getElementById("root")!).render(
    <React.StrictMode>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider theme={theme}>
          <CssBaseline />
          {/*
            The router is OUTSIDE the auth gate so one public route can exist
            beside the gated application. AuthGate used to wrap the router,
            which meant every path - including the post-logout landing page -
            triggered a sign-in redirect before any route matched.
          */}
          <BrowserRouter>
            <Routes>
              <Route path={SIGNED_OUT_PATH} element={<SignedOutPage />} />
              {import.meta.env.DEV && ViewAccessAsPage ? (
                <Route
                  path="/view-access-as"
                  element={
                    <Suspense fallback={null}>
                      <ViewAccessAsPage />
                    </Suspense>
                  }
                />
              ) : null}
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
}
