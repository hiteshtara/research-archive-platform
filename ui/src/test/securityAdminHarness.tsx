import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import type { ReactNode } from "react";

/*
 * Shared setup for the Security Administration component tests.
 *
 * A fresh QueryClient per render, with retries off and caching disabled:
 * capability is security state, and a client shared between tests could
 * serve one test's `administrator: true` to another test's render.
 */
export function renderWithProviders(ui: ReactNode, { route = "/" } = {}) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
    },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}

/** The three booleans exactly as the server sends them. */
export const serverSays = {
  activeAdministratorEnforcementOn: {
    administrator: true,
    enforcementEnabled: true,
    administrationAvailable: true,
  },
  activeAdministratorEnforcementOff: {
    administrator: true,
    enforcementEnabled: false,
    administrationAvailable: false,
  },
  /*
   * What the server returns for a non-administrator, a suspended identity,
   * a revoked membership, an unprovisioned identity and an ambiguous link
   * alike: CapabilitiesResponse.denied(), all three false.
   *
   * They are one case on the wire by design - the response does not say
   * WHICH of those a caller is, because that would disclose membership
   * state to someone who is not entitled to it. The distinctions are
   * verified server-side in CapabilitiesMembershipIntegrationTest.
   */
  denied: {
    administrator: false,
    enforcementEnabled: false,
    administrationAvailable: false,
  },
} as const;
