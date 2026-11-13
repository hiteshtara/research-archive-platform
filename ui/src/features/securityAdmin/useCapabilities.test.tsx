import { describe, expect, test, vi } from "vitest";
import { screen } from "@testing-library/react";

import { renderWithProviders, serverSays } from "../../test/securityAdminHarness";
import { useCapabilities } from "./useCapabilities";

const getCapabilities = vi.fn();
vi.mock("../../api/client", () => ({
  getCapabilities: () => getCapabilities(),
}));

/** Renders the hook's result so it can be asserted without a renderHook helper. */
function Probe() {
  const c = useCapabilities();
  return (
    <div
      data-testid="caps"
      data-administrator={String(c.administrator)}
      data-enforcement={String(c.enforcementEnabled)}
      data-available={String(c.administrationAvailable)}
      data-verified={String(c.verified)}
    />
  );
}

async function read() {
  const node = await screen.findByTestId("caps");
  return {
    administrator: node.getAttribute("data-administrator"),
    enforcementEnabled: node.getAttribute("data-enforcement"),
    administrationAvailable: node.getAttribute("data-available"),
    verified: node.getAttribute("data-verified"),
  };
}

const ALL_FALSE = {
  administrator: "false",
  enforcementEnabled: "false",
  administrationAvailable: "false",
  verified: "false",
};

describe("useCapabilities", () => {
  test("an active administrator with enforcement ON is all true", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorEnforcementOn);
    renderWithProviders(<Probe />);
    await vi.waitFor(async () => expect(await read()).toEqual({
      administrator: "true", enforcementEnabled: "true",
      administrationAvailable: "true", verified: "true",
    }));
  });

  test("an active administrator with enforcement OFF is administrator-only", async () => {
    getCapabilities.mockResolvedValue(serverSays.activeAdministratorEnforcementOff);
    renderWithProviders(<Probe />);
    await vi.waitFor(async () => expect(await read()).toEqual({
      administrator: "true", enforcementEnabled: "false",
      administrationAvailable: "false", verified: "true",
    }));
  });

  test("a denied caller is verified but holds nothing", async () => {
    // Non-administrator, suspended, revoked, unprovisioned and ambiguous
    // are one response on the wire by design - the endpoint does not say
    // which, because that would disclose membership state.
    getCapabilities.mockResolvedValue(serverSays.denied);
    renderWithProviders(<Probe />);
    await vi.waitFor(async () => expect(await read()).toEqual({
      administrator: "false", enforcementEnabled: "false",
      administrationAvailable: "false", verified: "true",
    }));
  });

  test("an expired session (null) is unverified and fails closed", async () => {
    getCapabilities.mockResolvedValue(null);
    renderWithProviders(<Probe />);
    await vi.waitFor(async () => expect(await read()).toEqual(ALL_FALSE));
  });

  test("a rejected request is unverified, not a crash", async () => {
    getCapabilities.mockRejectedValue(new Error("network down"));
    renderWithProviders(<Probe />);
    await vi.waitFor(async () => expect(await read()).toEqual(ALL_FALSE));
  });

  test("a malformed body cannot grant anything", async () => {
    for (const body of ["yes", 1, [], { administrator: "true" }, { administrator: 1 }]) {
      getCapabilities.mockResolvedValue(body);
      const { unmount } = renderWithProviders(<Probe />);
      await vi.waitFor(async () => {
        const c = await read();
        expect(c.administrator).toBe("false");
        expect(c.administrationAvailable).toBe("false");
      });
      unmount();
    }
  });

  test("availability is taken from the server, never recomputed", async () => {
    // A server that says administrator+enforcement but withholds
    // availability must not have availability inferred for it.
    getCapabilities.mockResolvedValue({
      administrator: true, enforcementEnabled: true, administrationAvailable: false,
    });
    renderWithProviders(<Probe />);
    await vi.waitFor(async () => {
      const c = await read();
      expect(c.administrator).toBe("true");
      expect(c.enforcementEnabled).toBe("true");
      expect(c.administrationAvailable).toBe("false");
    });
  });

  test("while in flight it reports nothing, rather than guessing", async () => {
    let resolve: (value: unknown) => void = () => {};
    getCapabilities.mockReturnValue(new Promise((r) => { resolve = r; }));
    renderWithProviders(<Probe />);
    expect(await read()).toEqual(ALL_FALSE);

    resolve(serverSays.activeAdministratorEnforcementOn);
    await vi.waitFor(async () => expect((await read()).administrator).toBe("true"));
  });
});
