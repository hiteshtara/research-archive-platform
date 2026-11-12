import { SecurityAdminOverviewPage } from "./SecurityAdminOverviewPage";
import { useCapabilities } from "../../features/securityAdmin/useCapabilities";

/*
 * Route wrapper. Reads capabilities once and hands them to the presentation
 * component, which stays free of data fetching so it can be rendered in the
 * design preview and in tests without a server.
 *
 * No activeGrants is passed: there is no endpoint that returns a grant total,
 * so the count renders as Unavailable rather than as an invented number.
 */
export function SecurityAdminOverviewRoute() {
  return <SecurityAdminOverviewPage capabilities={useCapabilities()} />;
}
