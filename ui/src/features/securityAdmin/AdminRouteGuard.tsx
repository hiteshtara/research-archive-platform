import Alert from "@mui/material/Alert";
import AlertTitle from "@mui/material/AlertTitle";
import Box from "@mui/material/Box";
import Skeleton from "@mui/material/Skeleton";
import Stack from "@mui/material/Stack";

import { SecurityAdminUnavailable } from "./SecurityAdminUnavailable";
import { adminRouteState } from "./securityAdminPresentation.mjs";
import { useCapabilities } from "./useCapabilities";

/*
 * Guards every /admin/* route.
 *
 * The decisive property: capabilities are resolved BEFORE any administrator
 * request is attempted, so when administration is unavailable the children
 * never mount and therefore never issue a call. "Zero admin API calls" is
 * structural here, not a rule someone has to remember - there is no request
 * to suppress because the component that would make it does not render.
 *
 * This is not access control. It decides what is shown; the server decides
 * what may be done, and refuses independently on all 12 endpoints.
 */
export function AdminRouteGuard({ children }: { children: React.ReactNode }) {
  const capabilities = useCapabilities();

  // Unverified -- still loading, errored, or simply not an administrator --
  // all resolve to "denied" in adminRouteState, which is the fail-closed
  // behaviour: nothing administrative is shown until the server has said so.
  const state = adminRouteState(capabilities);

  if (state === "denied") {
    return <AdminAccessDenied />;
  }

  if (state === "unavailable") {
    return <SecurityAdminUnavailable capabilities={capabilities} />;
  }

  return <>{children}</>;
}

/*
 * Shown to anyone who is not a verified administrator, including while the
 * check is in flight or has failed. It confirms nothing about the surface
 * beyond that this person cannot use it, and offers no retry that would
 * probe the administrator API.
 */
function AdminAccessDenied() {
  return (
    <Box sx={{ maxWidth: 640 }}>
      <Alert severity="warning" variant="outlined" sx={{ borderRadius: 2 }}>
        <AlertTitle sx={{ fontWeight: 700 }}>Administrator access required</AlertTitle>
        This area is limited to Research Archive administrators. If you believe you
        should have access, contact the Research Archive team.
      </Alert>
    </Box>
  );
}

/** Placeholder used while capabilities resolve, so the header does not jump. */
export function AdminLoadingSkeleton() {
  return (
    <Stack sx={{ gap: 2, maxWidth: 720 }}>
      <Skeleton variant="rounded" height={64} />
      <Skeleton variant="rounded" height={140} />
    </Stack>
  );
}
