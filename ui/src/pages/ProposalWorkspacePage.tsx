import { Alert, Link } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { Navigate, Link as RouterLink, useParams } from "react-router-dom";

import { ApiRequestError, getProposalWorkspace } from "../api/client";
import { LoadingState } from "../components/common/LoadingState";
import { proposalWorkspaceErrorMessage } from "../features/proposal/proposalWorkspacePresentation.mjs";

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
    // A missing Proposal will not appear on retry.
    retry: (failureCount, error) =>
      !(error instanceof ApiRequestError && error.status === 404) && failureCount < 2,
  });

  // getProposalWorkspace validates the payload, so a successful result
  // always carries current.proposalId (a search page or family list is
  // rejected as an error rather than rendered).
  if (workspaceQuery.isSuccess && workspaceQuery.data) {
    return (
      <Navigate
        to={`/proposals/dashboard/${workspaceQuery.data.current.proposalId}`}
        replace
      />
    );
  }

  if (workspaceQuery.isError) {
    const notFound =
      workspaceQuery.error instanceof ApiRequestError &&
      workspaceQuery.error.status === 404;
    return (
      <Alert severity={notFound ? "warning" : "error"}>
        {proposalWorkspaceErrorMessage(
          notFound ? "not-found" : "unavailable",
          proposalNumber ?? "",
        )}{" "}
        <Link component={RouterLink} to="/proposals">
          Search Proposals
        </Link>
      </Alert>
    );
  }

  return <LoadingState />;
}
