import { beforeEach, describe, expect, test, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { renderWithProviders, serverSays } from "../../test/securityAdminHarness";
import { AdministratorsPage } from "./AdministratorsPage";

const getCapabilities = vi.fn();
vi.mock("../../api/client", async () => {
  const actual = await vi.importActual<Record<string, unknown>>("../../api/client");
  return { ...actual, getCapabilities: () => getCapabilities() };
});

const listAdministrators = vi.fn();
const assignAdministrator = vi.fn();
const revokeAdministrator = vi.fn();
vi.mock("../../api/adminClient", () => ({
  listAdministrators: () => listAdministrators(),
  assignAdministrator: (...a: unknown[]) => assignAdministrator(...a),
  revokeAdministrator: (...a: unknown[]) => revokeAdministrator(...a),
}));

const ROSTER = [{
  adminMemberId: 1,
  institutionalIdentifier: "ARP-TEST-ADMIN",
  grantedAt: "2026-10-29T17:21:22Z",
  grantedBy: "BOOTSTRAP",
  reason: "first administrator",
}];

beforeEach(() => {
  listAdministrators.mockResolvedValue(ROSTER);
  assignAdministrator.mockResolvedValue({});
  revokeAdministrator.mockResolvedValue(undefined);
  // A prompt would block the page; a disabled control must never open one.
  vi.stubGlobal("prompt", vi.fn(() => "a reason"));
});

describe("AdministratorsPage in read-only mode", () => {
  test("announces Read-Only Mode", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorReadOnly);
    renderWithProviders(<AdministratorsPage />);

    const banner = await screen.findByTestId("read-only-banner");
    expect(within(banner).getByText("Read-Only Mode")).toBeTruthy();
    expect(banner.textContent).toMatch(/review only/i);
  });

  test("the roster still loads - reads are unaffected", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorReadOnly);
    renderWithProviders(<AdministratorsPage />);

    expect(await screen.findByText("ARP-TEST-ADMIN")).toBeTruthy();
  });

  test("Appoint stays disabled even with the form filled", async () => {
    // The defect this covers: typing an identifier and a reason used to
    // enable Appoint, and clicking it produced a 409 from the server.
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorReadOnly);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderWithProviders(<AdministratorsPage />);
    await screen.findByTestId("read-only-banner");

    const appoint = screen.getByRole("button", { name: "Appoint" });
    expect((appoint as HTMLButtonElement).disabled).toBe(true);

    // The inputs are disabled too, so there is nothing to fill in.
    const identifier = screen.getByLabelText(/institutional identifier/i);
    expect((identifier as HTMLInputElement).disabled).toBe(true);

    await user.click(appoint);
    expect(assignAdministrator).not.toHaveBeenCalled();
  });

  test("Remove is disabled and opens no prompt", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorReadOnly);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderWithProviders(<AdministratorsPage />);
    await screen.findByText("ARP-TEST-ADMIN");

    const remove = screen.getByRole("button", { name: "Remove" });
    expect((remove as HTMLButtonElement).disabled).toBe(true);

    await user.click(remove);
    expect(window.prompt).not.toHaveBeenCalled();
    expect(revokeAdministrator).not.toHaveBeenCalled();
  });

  test("no write request is issued for any interaction", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorReadOnly);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { container } = renderWithProviders(<AdministratorsPage />);
    await screen.findByTestId("read-only-banner");

    for (const button of container.querySelectorAll("button")) {
      await user.click(button);
    }
    expect(assignAdministrator).not.toHaveBeenCalled();
    expect(revokeAdministrator).not.toHaveBeenCalled();
  });
});

describe("AdministratorsPage with writes enabled", () => {
  test("no banner, and Appoint enables once the form is valid", async () => {
    // Non-vacuity: the gating above must be the read-only flag doing the
    // work, not the controls being permanently dead.
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorWritesOn);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderWithProviders(<AdministratorsPage />);
    await screen.findByText("ARP-TEST-ADMIN");

    expect(screen.queryByTestId("read-only-banner")).toBeNull();

    const appoint = screen.getByRole("button", { name: "Appoint" });
    expect((appoint as HTMLButtonElement).disabled).toBe(true); // empty form

    await user.type(screen.getByLabelText(/institutional identifier/i), "ARP-NEW");
    await user.type(screen.getByLabelText(/^reason$/i), "because");
    await waitFor(() =>
      expect((screen.getByRole("button", { name: "Appoint" }) as HTMLButtonElement).disabled)
        .toBe(false));
  });
});
