import { beforeEach, describe, expect, test, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";

import { renderWithProviders, serverSays } from "../../test/securityAdminHarness";
import { AdminRouteGuard } from "./AdminRouteGuard";

const getCapabilities = vi.fn();
vi.mock("../../api/client", () => ({
  getCapabilities: () => getCapabilities(),
}));

/*
 * Every fetch the tree issues, so "zero administrator API calls" can be
 * asserted against what actually left the component rather than against a
 * flag it set. getCapabilities is mocked above, so any fetch recorded here
 * is one a rendered child issued on its own.
 */
let fetches: string[] = [];
beforeEach(() => {
  fetches = [];
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
    fetches.push(String(input instanceof Request ? input.url : input));
    return Promise.resolve(new Response("{}", {
      status: 200,
      headers: { "content-type": "application/json" },
    }));
  }));
});

const adminCalls = () => fetches.filter((url) => url.includes("/api/v1/admin"));

function Child() {
  return <div data-testid="admin-child">real administration screen</div>;
}

describe("AdminRouteGuard", () => {
  // ---- requirement 3: each identity state ----
  test("an active administrator with enforcement ON renders the real screen", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorEnforcementOn);
    renderWithProviders(<AdminRouteGuard><Child /></AdminRouteGuard>, { route: "/admin/overview" });
    expect(await screen.findByTestId("admin-child")).toBeTruthy();
  });

  // ---- requirement 5: enforcement OFF ----
  test("an active administrator with enforcement OFF sees the unavailable screen", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorEnforcementOff);
    renderWithProviders(<AdminRouteGuard><Child /></AdminRouteGuard>, { route: "/admin/overview" });

    expect(await screen.findByText("Security Administration is unavailable")).toBeTruthy();
    // The decisive part: the child never mounted, so it cannot have called.
    expect(screen.queryByTestId("admin-child")).toBeNull();
  });

  test("enforcement OFF issues ZERO administrator API calls", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorEnforcementOff);
    renderWithProviders(<AdminRouteGuard><Child /></AdminRouteGuard>, { route: "/admin/overview" });
    await screen.findByText("Security Administration is unavailable");

    // Settle anything the tree might still issue before asserting absence.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(adminCalls()).toEqual([]);
  });

  test.each([
    ["a non-administrator", serverSays.denied],
    ["a suspended identity", serverSays.denied],
    ["a revoked membership", serverSays.denied],
  ])("%s is denied and calls no administrator endpoint", async (_name, response) => {
    getCapabilities.mockResolvedValue(response);
    renderWithProviders(<AdminRouteGuard><Child /></AdminRouteGuard>, { route: "/admin/overview" });

    expect(await screen.findByText(/Administrator access required/i)).toBeTruthy();
    expect(screen.queryByTestId("admin-child")).toBeNull();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(adminCalls()).toEqual([]);
  });

  // ---- requirement 3: expired session ----
  test("an expired session (401 -> null) is denied, not crashed", async () => {
    // getCapabilities turns 401, 404, a non-JSON body and a network failure
    // all into null; readCapabilities fails closed on it.
    getCapabilities.mockResolvedValue(null);
    renderWithProviders(<AdminRouteGuard><Child /></AdminRouteGuard>, { route: "/admin/overview" });

    expect(await screen.findByText(/Administrator access required/i)).toBeTruthy();
    expect(adminCalls()).toEqual([]);
  });

  test("a failed capability request is denied, never assumed", async () => {
    getCapabilities.mockRejectedValue(new Error("network down"));
    renderWithProviders(<AdminRouteGuard><Child /></AdminRouteGuard>, { route: "/admin/overview" });

    expect(await screen.findByText(/Administrator access required/i)).toBeTruthy();
    expect(screen.queryByTestId("admin-child")).toBeNull();
  });

  test("the child never mounts before capabilities resolve", async () => {
    let resolve: (value: unknown) => void = () => {};
    getCapabilities.mockReturnValue(new Promise((r) => { resolve = r; }));
    renderWithProviders(<AdminRouteGuard><Child /></AdminRouteGuard>, { route: "/admin/overview" });

    // In flight: fail closed, show nothing administrative.
    expect(screen.queryByTestId("admin-child")).toBeNull();
    expect(adminCalls()).toEqual([]);

    resolve(serverSays.activeAdministratorEnforcementOn);
    await waitFor(() => expect(screen.queryByTestId("admin-child")).not.toBeNull());
  });
});
