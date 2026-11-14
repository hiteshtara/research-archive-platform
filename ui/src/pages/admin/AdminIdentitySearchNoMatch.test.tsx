import { beforeEach, describe, expect, test, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { renderWithProviders, serverSays } from "../../test/securityAdminHarness";
import { AdminIdentitySearchPage } from "./AdminIdentitySearchPage";

const getCapabilities = vi.fn();
vi.mock("../../api/client", async () => {
  const actual = await vi.importActual<Record<string, unknown>>("../../api/client");
  return { ...actual, getCapabilities: () => getCapabilities() };
});

const searchAdminIdentities = vi.fn();
vi.mock("../../api/adminClient", () => ({
  searchAdminIdentities: (...a: unknown[]) => searchAdminIdentities(...a),
}));

beforeEach(() => {
  getCapabilities.mockResolvedValue(serverSays.activeAdministratorReadOnly);
});

describe("identity search: the No match explanation", () => {
  /*
   * The real case this came from: `antcast` has a working BU login and a
   * Cognito account, but no identity link - so the login name is not
   * searchable anywhere, and the old copy did not say why.
   */
  test("names what was searched, and what was NOT", async () => {
    searchAdminIdentities.mockResolvedValue([]);
    const user = userEvent.setup();
    renderWithProviders(<AdminIdentitySearchPage />);

    await user.type(screen.getByLabelText(/name, login, identifier/i), "antcast");
    await user.click(screen.getByRole("button", { name: /search/i }));

    const notice = await screen.findByTestId("no-match");
    expect(within(notice).getByText("No match")).toBeTruthy();

    const text = notice.textContent ?? "";
    // what IS searched
    expect(text).toMatch(/signed in/i);
    expect(text).toMatch(/already linked/i);
    expect(text).toMatch(/historical Kuali extract/i);
    // what is NOT - the part that was missing
    expect(text).toMatch(/does not search BU/i);
    expect(text).toMatch(/never been linked/i);
    // and the actionable consequence
    expect(text).toMatch(/institutional identifier directly/i);
  });

  test("the explanation does not appear when there are results", async () => {
    searchAdminIdentities.mockResolvedValue([
      { source: "HISTORICAL_PERSON", institutionalIdentifier: null,
        kualiPersonId: "U1", displayName: "SOMEONE REAL", hasActiveLink: false },
    ]);
    const user = userEvent.setup();
    renderWithProviders(<AdminIdentitySearchPage />);

    await user.type(screen.getByLabelText(/name, login, identifier/i), "someone");
    await user.click(screen.getByRole("button", { name: /search/i }));

    expect(await screen.findByText("SOMEONE REAL")).toBeTruthy();
    expect(screen.queryByTestId("no-match")).toBeNull();
  });

  test("the error copy no longer blames record-level enforcement", async () => {
    // Administration has its own flag now; saying "record authorization is
    // not enabled" would send an administrator after the wrong setting.
    searchAdminIdentities.mockRejectedValue(new Error("boom"));
    const user = userEvent.setup();
    const { container } = renderWithProviders(<AdminIdentitySearchPage />);

    await user.type(screen.getByLabelText(/name, login, identifier/i), "x");
    await user.click(screen.getByRole("button", { name: /search/i }));

    await vi.waitFor(() => {
      const t = container.textContent ?? "";
      expect(t).toMatch(/Security Administration is not\s+enabled/i);
      expect(t).toMatch(/separate from record-level/i);
    });
  });
});
