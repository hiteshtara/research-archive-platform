import { Box, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { useNavigate, useParams } from "react-router-dom";

import { getAwardHierarchyV1 } from "../../api/client";
import { AwardBreadcrumb } from "../../components/award/AwardBreadcrumb";
import { AwardHierarchyTree } from "../../components/award/AwardHierarchyTree";
import { flattenHierarchyNodes } from "../../components/award/hierarchyUtils";
import { ErrorState } from "../../components/common/ErrorState";
import { LoadingState } from "../../components/common/LoadingState";
import type { AwardHierarchyNode } from "../../types/api";

// Standalone hierarchy screen, reached after selecting a search result.
// Clicking any node in the tree opens that Award's Dashboard - matches
// the approved mockup's renderHierarchyStandalone/renderTree behavior,
// fully recursive rather than the mockup's 2-level demo limitation.
export function AwardHierarchyPage() {
  const { awardNumber } = useParams<{ awardNumber: string }>();
  const navigate = useNavigate();

  const hierarchyQuery = useQuery({
    queryKey: ["award-hierarchy-v1", awardNumber],
    queryFn: ({ signal }) => getAwardHierarchyV1(awardNumber ?? "", signal),
    enabled: Boolean(awardNumber),
  });

  const nodesByAwardNumber = useMemo(() => {
    if (!hierarchyQuery.data) {
      return new Map<string, AwardHierarchyNode>();
    }

    return flattenHierarchyNodes(hierarchyQuery.data.root);
  }, [hierarchyQuery.data]);

  function openDashboard(node: AwardHierarchyNode) {
    if (node.awardId === null) {
      return;
    }

    navigate(`/awards/${node.awardId}`);
  }

  if (hierarchyQuery.isLoading) {
    return <LoadingState />;
  }

  if (hierarchyQuery.isError) {
    return (
      <ErrorState
        message={`Unable to load the hierarchy for Award ${awardNumber}.`}
      />
    );
  }

  if (!hierarchyQuery.data) {
    return null;
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography
          variant="overline"
          color="text.secondary"
          sx={{ display: "block" }}
        >
          Award hierarchy
        </Typography>

        <AwardBreadcrumb
          path={hierarchyQuery.data.selectedAwardPath}
          nodesByAwardNumber={nodesByAwardNumber}
          onNavigate={openDashboard}
        />
      </Box>

      <Typography variant="body2" color="text.secondary">
        Click any Award below to open its dashboard - no page reload.
      </Typography>

      <AwardHierarchyTree
        root={hierarchyQuery.data.root}
        selectedAwardNumber={hierarchyQuery.data.requestedAwardNumber}
        onSelect={openDashboard}
      />
    </Stack>
  );
}
