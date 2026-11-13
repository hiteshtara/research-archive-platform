import { describe, expect, test, vi, beforeEach } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import { RouterProvider, createMemoryRouter } from "react-router-dom";

import { serverSays } from "../../test/securityAdminHarness";

/*
 * Routing tests against the REAL App route table, not a hand-built imitation
 * of it - App exports a bare <Routes>, so a MemoryRouter can drive it. A test
 * that rebuilt the routes would keep passing if App.tsx's wiring regressed,
 * which is the regression most worth catching here.
 */

const getCapabilities = vi.fn();
let fetches: string[] = [];

vi.mock("../../api/client", async () => {
  const actual = await vi.importActual<Record<string, unknown>>("../../api/client");
  return { ...actual, getCapabilities: () => getCapabilities() };
});

// AppLayout reads the signed-in user and attachment access; neither is under
// test here and both would reach the network.
vi.mock("../../hooks/useAttachmentAccess", () => ({
  useAttachmentAccess: () => true,
}));
// Mocked at our own boundary rather than at aws-amplify's, so the test
// does not depend on which Amplify functions auth.ts happens to call.
vi.mock("../../auth", () => ({
  SIGNED_OUT_PATH: "/signed-out",
  buIdentityProvider: null,
  markSignedOut: () => {},
  clearSignedOutMarker: () => {},
  wasSignedOut: () => false,
  loginWithBu: () => Promise.resolve(),
  loginWithCognito: () => Promise.resolve(),
  login: () => Promise.resolve(),
  logout: () => Promise.resolve(),
  currentUser: () => Promise.resolve({ username: "tester" }),
  accessToken: () => Promise.resolve("test-token"),
  hasAttachmentAccess: () => Promise.resolve(true),
}));

beforeEach(() => {
  fetches = [];
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
    fetches.push(String(input instanceof Request ? input.url : input));
    // A dashboard-shaped body for the archive page, so a route under test
    // renders its real interface instead of crashing on absent counts.
    const body = JSON.stringify({
      awards: 1, awardHistoryRecords: 1, proposals: 1, proposalHistoryRecords: 1,
      negotiations: 1, subawards: 1, documents: 1,
    });
    return Promise.resolve(new Response(body, {
      status: 200, headers: { "content-type": "application/json" },
    }));
  }));
});

const adminCalls = () => fetches.filter((u) => u.includes("/api/v1/admin"));

async function renderApp(entries: string[] = ["/"], index = entries.length - 1) {
  const { default: App } = await import("../../App");
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  });
  // createMemoryRouter (rather than <MemoryRouter>) because it exposes a
  // real history stack: router.navigate(-1) is the browser Back button,
  // which <MemoryRouter> + window.history cannot simulate.
  const router = createMemoryRouter(
    [{ path: "*", element: <App /> }],
    { initialEntries: entries, initialIndex: index },
  );
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

describe("Security Administration routing", () => {
  // ---- requirement 6: direct URL access ----
  test("a non-administrator typing /admin/overview directly is denied", async () => {
    getCapabilities.mockResolvedValue(serverSays.denied);
    await renderApp(["/admin/overview"]);

    expect(await screen.findByText(/Administrator access required/i)).toBeTruthy();
    await new Promise((r) => setTimeout(r, 50));
    expect(adminCalls()).toEqual([]);
  });

  test("a non-administrator typing a deeper admin URL is denied the same way", async () => {
    getCapabilities.mockResolvedValue(serverSays.denied);
    await renderApp(["/admin/identities"]);

    expect(await screen.findByText(/Administrator access required/i)).toBeTruthy();
    expect(adminCalls()).toEqual([]);
  });

  test("an administrator with enforcement OFF reaches the unavailable screen by direct URL", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorEnforcementOff);
    await renderApp(["/admin/overview"]);

    expect(await screen.findByText("Security Administration is unavailable")).toBeTruthy();
    await new Promise((r) => setTimeout(r, 50));
    expect(adminCalls()).toEqual([]);
  });

  test("/admin redirects to /admin/overview and is still guarded", async () => {
    getCapabilities.mockResolvedValue(serverSays.denied);
    await renderApp(["/admin"]);
    // The redirect must not land anywhere unguarded.
    expect(await screen.findByText(/Administrator access required/i)).toBeTruthy();
  });

  // ---- requirement 6: the archive interface is preserved ----
  test("a non-administrator sees the ordinary archive header, with no toggle", async () => {
    getCapabilities.mockResolvedValue(serverSays.denied);
    await renderApp(["/"]);

    // The archive itself still renders.
    await waitFor(() =>
      expect(screen.queryByRole("button", { name: "Security Admin" })).toBeNull(),
    );
    expect(screen.queryByRole("group", { name: "Application mode" })).toBeNull();
  });

  test("an administrator on an archive route still sees the archive, plus the toggle", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorEnforcementOff);
    await renderApp(["/"]);

    expect(await screen.findByRole("button", { name: "Security Admin" })).toBeTruthy();
    // Being an administrator must not change the archive side of the app.
    expect(screen.getByRole("button", { name: "Research Archive" }).getAttribute("aria-pressed"))
      .toBe("true");
  });

  // ---- requirement 6: back / forward ----
  test("browser back from a denied admin URL returns to the archive", async () => {
    getCapabilities.mockResolvedValue(serverSays.denied);
    const router = await renderApp(["/", "/admin/overview"], 1);

    expect(await screen.findByText(/Administrator access required/i)).toBeTruthy();

    await router.navigate(-1);
    await waitFor(() =>
      expect(screen.queryByText(/Administrator access required/i)).toBeNull(),
    );
    expect(router.state.location.pathname).toBe("/");
    expect(adminCalls()).toEqual([]);
  });

  test("forward back into admin re-evaluates and denies again", async () => {
    getCapabilities.mockResolvedValue(serverSays.denied);
    const router = await renderApp(["/", "/admin/overview"], 1);
    await screen.findByText(/Administrator access required/i);

    await router.navigate(-1);
    await waitFor(() =>
      expect(screen.queryByText(/Administrator access required/i)).toBeNull(),
    );

    // The guard must not be bypassable by navigating forward into it.
    await router.navigate(1);
    await waitFor(() =>
      expect(screen.queryByText(/Administrator access required/i)).not.toBeNull(),
    );
    expect(router.state.location.pathname).toBe("/admin/overview");
    expect(adminCalls()).toEqual([]);
  });

  test("an administrator returning from the unavailable screen keeps the archive intact", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorEnforcementOff);
    const router = await renderApp(["/", "/admin/overview"], 1);
    await screen.findByText("Security Administration is unavailable");

    await router.navigate(-1);
    await waitFor(() =>
      expect(screen.queryByText("Security Administration is unavailable")).toBeNull(),
    );
    // Back on the archive side, with the toggle still offered.
    expect(await screen.findByRole("button", { name: "Security Admin" })).toBeTruthy();
    expect(adminCalls()).toEqual([]);
  });
});
