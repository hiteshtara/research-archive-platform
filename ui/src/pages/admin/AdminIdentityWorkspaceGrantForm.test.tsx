import { beforeEach, describe, expect, test, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";

import { renderWithProviders, serverSays } from "../../test/securityAdminHarness";
import { AdminIdentityWorkspacePage } from "./AdminIdentityWorkspacePage";

const getCapabilities = vi.fn();
vi.mock("../../api/client", async () => {
  const actual = await vi.importActual<Record<string, unknown>>("../../api/client");
  return { ...actual, getCapabilities: () => getCapabilities() };
});

const getAdminIdentity = vi.fn();
const createGrant = vi.fn();
const revokeGrant = vi.fn();
const setSuspension = vi.fn();
vi.mock("../../api/adminClient", () => ({
  getAdminIdentity: (...a: unknown[]) => getAdminIdentity(...a),
  createGrant: (...a: unknown[]) => createGrant(...a),
  revokeGrant: (...a: unknown[]) => revokeGrant(...a),
  setSuspension: (...a: unknown[]) => setSuspension(...a),
}));

const IDENTITY = {
  institutionalIdentifier: "ARP-TEST-USER",
  suspended: false,
  administrator: false,
  noArchiveAccess: false,
  accessNote: null,
  links: [{
    linkId: 1,
    cognitoIssuer: "issuer",
    cognitoSubject: "sub",
    kualiPersonId: null,
    loginName: "arptest",
    status: "ACTIVE",
    verifiedAt: null,
  }],
  grants: [{
    grantId: 7,
    type: "UNIT",
    label: "Unit 1234",
    unitNumber: "1234",
    includeDescendants: false,
    ioValue: null,
    validFrom: null,
    expiresAt: null,
    revokedAt: null,
    active: true,
    scopeNote: null,
  }],
  recentAudit: [],
};

const VALID_REASON = "Department chair approved access for the audit";

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/admin/identities/:identifier" element={<AdminIdentityWorkspacePage />} />
    </Routes>,
    { route: "/admin/identities/ARP-TEST-USER" },
  );
}

function grantButton() {
  return screen.getByRole("button", { name: "Grant access" }) as HTMLButtonElement;
}

async function chooseType(user: ReturnType<typeof userEvent.setup>, label: string) {
  await user.click(screen.getByRole("combobox", { name: /type/i }));
  await user.click(await screen.findByRole("option", { name: label }));
}

beforeEach(() => {
  // Call history must not leak between tests: a write made by a writes-on
  // test would otherwise satisfy or fail a later "never called" assertion.
  vi.clearAllMocks();
  getAdminIdentity.mockResolvedValue(IDENTITY);
  createGrant.mockResolvedValue({});
  revokeGrant.mockResolvedValue(undefined);
  setSuspension.mockResolvedValue(undefined);
  vi.stubGlobal("prompt", vi.fn(() => "a reason typed into a prompt"));
});

describe("AdminIdentityWorkspacePage grant form", () => {
  test("renders with no access type selected", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorWritesOn);
    renderPage();
    await screen.findByText("Unit 1234");

    const type = screen.getByRole("combobox", { name: /type/i });
    expect(type.textContent).toContain("Choose access type…");
    expect(type.textContent).not.toContain("Central");
    expect(screen.queryByTestId("central-grant-warning")).toBeNull();
    expect(grantButton().disabled).toBe(true);
  });

  test("choosing Central shows the every-record warning", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorWritesOn);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderPage();
    await screen.findByText("Unit 1234");

    await chooseType(user, "Central (all records)");
    const warning = screen.getByTestId("central-grant-warning");
    expect(warning.textContent).toMatch(/every record in every unit/);
    expect(warning.textContent).toMatch(/second administrator/);
  });

  test("Grant access stays disabled while the reason is invalid", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorWritesOn);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderPage();
    await screen.findByText("Unit 1234");

    await chooseType(user, "Central (all records)");
    expect(grantButton().disabled).toBe(true); // no reason yet

    const reason = screen.getByLabelText(/^reason/i);
    for (const bad of ["test", "short one", "N/A n/a", "Reorganisation"]) {
      await user.clear(reason);
      await user.type(reason, bad);
      expect(grantButton().disabled).toBe(true);
    }
    expect(screen.getByText("A reason must be at least two words.")).toBeTruthy();
    await user.click(grantButton());
    expect(createGrant).not.toHaveBeenCalled();

    // Non-vacuity: a valid reason is what enables it, and what is sent.
    await user.clear(reason);
    await user.type(reason, `  ${VALID_REASON}  `);
    await waitFor(() => expect(grantButton().disabled).toBe(false));
    await user.click(grantButton());
    await waitFor(() => expect(createGrant).toHaveBeenCalledTimes(1));
    expect(createGrant.mock.calls[0][1]).toMatchObject({ type: "CENTRAL", reason: VALID_REASON });
  });

  test("Grant access stays disabled in read-only mode even with a valid form", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorReadOnly);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderPage();
    await screen.findByTestId("read-only-banner");
    await screen.findByText("Unit 1234");

    await chooseType(user, "Central (all records)");
    await user.type(screen.getByLabelText(/^reason/i), VALID_REASON);
    expect(grantButton().disabled).toBe(true);
    await user.click(grantButton());
    expect(createGrant).not.toHaveBeenCalled();
  });
});

describe("AdminIdentityWorkspacePage destructive actions", () => {
  test("no window.prompt is opened for revoke or suspend in read-only mode", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorReadOnly);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { container } = renderPage();
    await screen.findByTestId("read-only-banner");
    await screen.findByText("Unit 1234");

    for (const button of container.querySelectorAll("button")) {
      await user.click(button);
    }
    expect(window.prompt).not.toHaveBeenCalled();
    expect(screen.queryByRole("group")).toBeNull();
    expect(revokeGrant).not.toHaveBeenCalled();
    expect(setSuspension).not.toHaveBeenCalled();
  });

  test("revoke takes its reason in-page and enforces the rule", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorWritesOn);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderPage();
    await screen.findByText("Unit 1234");

    await user.click(screen.getByRole("button", { name: "Revoke" }));
    const panel = screen.getByRole("group", { name: "Revoke this grant" });
    const confirm = within(panel).getByRole("button", { name: "Confirm revoke" });
    expect((confirm as HTMLButtonElement).disabled).toBe(true);

    await user.type(within(panel).getByLabelText(/^reason/i), "testing testing");
    expect((confirm as HTMLButtonElement).disabled).toBe(true);

    await user.clear(within(panel).getByLabelText(/^reason/i));
    await user.type(within(panel).getByLabelText(/^reason/i), "Moved to another department");
    expect((confirm as HTMLButtonElement).disabled).toBe(false);
    await user.click(confirm);

    await waitFor(() =>
      expect(revokeGrant).toHaveBeenCalledWith("ARP-TEST-USER", 7, "Moved to another department"));
    expect(window.prompt).not.toHaveBeenCalled();
  });

  test("suspend takes its reason in-page, never through window.prompt", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorWritesOn);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderPage();
    await screen.findByText("Unit 1234");

    await user.click(screen.getByRole("button", { name: "Suspend access" }));
    const panel = screen.getByRole("group", { name: "Suspend access" });
    await user.type(within(panel).getByLabelText(/^reason/i), "Account under security review");
    await user.click(within(panel).getByRole("button", { name: "Confirm suspension" }));

    await waitFor(() =>
      expect(setSuspension).toHaveBeenCalledWith(
        "ARP-TEST-USER",
        true,
        "Account under security review",
      ));
    expect(window.prompt).not.toHaveBeenCalled();
  });
});
