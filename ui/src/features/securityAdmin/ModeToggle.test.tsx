import { describe, expect, test, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { renderWithProviders, serverSays } from "../../test/securityAdminHarness";
import { readCapabilities } from "./securityAdminPresentation.mjs";
import { ModeToggle } from "./ModeToggle";

const navigate = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>("react-router-dom");
  return { ...actual, useNavigate: () => navigate };
});

const caps = (raw: unknown, options = {}) => readCapabilities(raw, options);

describe("ModeToggle", () => {
  // ---- requirement 4: non-administrators never see the toggle ----
  test("a non-administrator renders nothing at all", () => {
    const { container } = renderWithProviders(
      <ModeToggle capabilities={caps(serverSays.denied)} />,
    );
    expect(screen.queryByRole("group", { name: "Application mode" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Security Admin" })).toBeNull();
    // Absent, not merely hidden or disabled: there must be no hint the mode exists.
    expect(container.innerHTML).toBe("");
  });

  test("an unverified caller renders nothing (loading, error, 401, non-JSON)", () => {
    const unverifiable = [
      caps(null, { isLoading: true }),
      caps(null, { isError: true }),
      caps(null),
      caps(undefined),
      caps("not an object"),
      // A hostile or broken body must not be able to conjure the toggle.
      caps({ administrator: "true" }),
      caps({ administrator: 1 }),
    ];
    for (const capabilities of unverifiable) {
      const { container, unmount } = renderWithProviders(
        <ModeToggle capabilities={capabilities} />,
      );
      expect(container.innerHTML).toBe("");
      unmount();
    }
  });

  // ---- requirement 3: enforcement ON and OFF ----
  test("an active administrator sees the toggle with enforcement ON", () => {
    renderWithProviders(
      <ModeToggle capabilities={caps(serverSays.activeAdministratorEnforcementOn)} />,
    );
    expect(screen.getByRole("button", { name: "Security Admin" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Research Archive" })).toBeTruthy();
  });

  test("an active administrator KEEPS the toggle with enforcement OFF", () => {
    renderWithProviders(
      <ModeToggle capabilities={caps(serverSays.activeAdministratorEnforcementOff)} />,
    );
    // The requirement that motivated the design: DEV runs with enforcement
    // off, and the surface must not be invisible there.
    expect(screen.getByRole("button", { name: "Security Admin" })).toBeTruthy();
  });

  // ---- which side reads as selected ----
  test("selection follows the route, not the click", () => {
    const { unmount } = renderWithProviders(
      <ModeToggle capabilities={caps(serverSays.activeAdministratorEnforcementOn)} />,
      { route: "/awards/search" },
    );
    expect(screen.getByRole("button", { name: "Research Archive" }).getAttribute("aria-pressed"))
      .toBe("true");
    unmount();

    renderWithProviders(
      <ModeToggle capabilities={caps(serverSays.activeAdministratorEnforcementOn)} />,
      { route: "/admin/overview" },
    );
    expect(screen.getByRole("button", { name: "Security Admin" }).getAttribute("aria-pressed"))
      .toBe("true");
  });

  test("choosing a mode navigates to that mode's entry point", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ModeToggle capabilities={caps(serverSays.activeAdministratorEnforcementOn)} />,
      { route: "/" },
    );
    await user.click(screen.getByRole("button", { name: "Security Admin" }));
    expect(navigate).toHaveBeenCalledWith("/admin/overview");

    navigate.mockClear();
    await user.click(screen.getByRole("button", { name: "Research Archive" }));
    // Already on the archive side, so the group reports no change and
    // nothing is navigated - deselecting must not strand the user.
    expect(navigate).not.toHaveBeenCalled();
  });
});
