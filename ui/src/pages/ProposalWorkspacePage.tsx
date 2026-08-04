import { Alert, Box, CircularProgress } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { Navigate, useParams } from "react-router-dom";

import { getProposalWorkspace } from "../api/client";

// Retired: this page's own General/Awards/History tabs predated the
// current ProposalDashboardPage (Summary/Versions/Funded Awards/
// Attachments/People and Units/Comments) and bypassed it entirely (a
// flat key-value table, an Awards tab exposing raw award IDs). Kept as
// a resolve-and-redirect shim - not removed outright - so no old
// bookmark/link to /proposals/:proposalNumber strands a user; the same
// "redirect rather than 404" convention already used for the retired
// Award Families/History pages (see App.tsx).
export function ProposalWorkspacePage() {
  const { proposalNumber } = useParams();

  const workspaceQuery = useQuery({
    queryKey: ["proposal-workspace", proposalNumber],
    enabled: !!proposalNumber,
    queryFn: () => getProposalWorkspace(proposalNumber!),
  });

  if (workspaceQuery.isSuccess && workspaceQuery.data) {
    return (
      <Navigate
        to={`/proposals/dashboard/${workspaceQuery.data.current.proposalId}`}
        replace
      />
    );
  }

  if (workspaceQuery.isError) {
    return <Alert severity="error">Unable to load Proposal workspace.</Alert>;
  }

  return (
    <Box sx={{ display: "grid", placeItems: "center", py: 10 }}>
      <CircularProgress />
    </Box>
  );
}
