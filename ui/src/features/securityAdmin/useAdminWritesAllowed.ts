import { readOnlyNotice } from "./securityAdminPresentation.mjs";
import { useCapabilities } from "./useCapabilities";

/**
 * Whether an administration screen may offer a control that CHANGES something.
 *
 * A hook rather than a prop so a page cannot forget to thread it through, and
 * so gating a newly added control is one line.
 *
 * Presentation only. The server refuses every write with 409
 * ADMINISTRATION_WRITES_DISABLED regardless of what is rendered - this exists
 * so an administrator does not discover the configuration by being refused.
 *
 * Lives in its own file rather than beside ReadOnlyBanner: mixing a hook and
 * a component in one module breaks fast refresh, which oxlint flags.
 */
export function useAdminWritesAllowed(): boolean {
  const capabilities = useCapabilities();
  return readOnlyNotice(capabilities) === null
    && capabilities.administrationWritesAvailable === true;
}
