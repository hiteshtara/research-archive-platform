import { describe, expect, test } from "vitest";
import { screen, within } from "@testing-library/react";

import { renderWithProviders, serverSays } from "../../test/securityAdminHarness";
import { readCapabilities } from "./securityAdminPresentation.mjs";
import { SecurityAdminUnavailable } from "./SecurityAdminUnavailable";

const caps = (raw: unknown) => readCapabilities(raw);

describe("SecurityAdminUnavailable", () => {
  test("explains the state without calling it an error", () => {
    renderWithProviders(
      <SecurityAdminUnavailable capabilities={caps(serverSays.activeAdministratorEnforcementOff)} />,
    );
    expect(screen.getByText("Security Administration is unavailable")).toBeTruthy();
    expect(screen.getByText(/configuration state, not a fault/i)).toBeTruthy();

    // Enforcement being off is not a 404 and not a permission problem;
    // saying either to an administrator would be wrong.
    expect(screen.queryByText(/not found/i)).toBeNull();
    expect(screen.queryByText(/denied/i)).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  // ---- requirement 5: no dead controls ----
  test("renders no interactive control of any kind", () => {
    const { container } = renderWithProviders(
      <SecurityAdminUnavailable capabilities={caps(serverSays.activeAdministratorEnforcementOff)} />,
    );
    // The failure this prevents, recorded from live DEV: /admin/identities
    // rendered a working search box while every admin endpoint answered 404,
    // so a tester could type a query and press Search into a dead end.
    expect(screen.queryAllByRole("button")).toEqual([]);
    expect(screen.queryAllByRole("textbox")).toEqual([]);
    expect(screen.queryAllByRole("searchbox")).toEqual([]);
    expect(screen.queryAllByRole("link")).toEqual([]);
    expect(container.querySelectorAll("form, input, select, textarea")).toHaveLength(0);
  });

  test("reports the enforcement state it was given, and invents no figure", () => {
    const { unmount } = renderWithProviders(
      <SecurityAdminUnavailable capabilities={caps(serverSays.activeAdministratorEnforcementOff)} />,
    );
    const state = screen.getByText("Current state").parentElement as HTMLElement;
    expect(within(state).getByText("OFF")).toBeTruthy();
    expect(within(state).queryByText("ON")).toBeNull();
    unmount();

    renderWithProviders(
      <SecurityAdminUnavailable capabilities={caps(serverSays.activeAdministratorEnforcementOn)} />,
    );
    const onState = screen.getByText("Current state").parentElement as HTMLElement;
    expect(within(onState).getByText("ON")).toBeTruthy();
  });

  test("an unverified capability reads as OFF, never as ON", () => {
    // Fails closed: an unknown enforcement state must not be displayed as
    // enabled, which would suggest the tools should be working.
    renderWithProviders(<SecurityAdminUnavailable capabilities={caps(null)} />);
    const state = screen.getByText("Current state").parentElement as HTMLElement;
    expect(within(state).getByText("OFF")).toBeTruthy();
  });

  test("has a single top-level heading for the screen", () => {
    renderWithProviders(
      <SecurityAdminUnavailable capabilities={caps(serverSays.activeAdministratorEnforcementOff)} />,
    );
    const headings = screen.getAllByRole("heading", { level: 1 });
    expect(headings).toHaveLength(1);
    expect(headings[0].textContent).toBe("Security Administration is unavailable");
  });
});
