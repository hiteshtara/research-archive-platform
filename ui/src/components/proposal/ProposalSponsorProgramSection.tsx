import { Box, Skeleton } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getProposalSummaryV1 } from "../../api/client";
import { ErrorState } from "../common/ErrorState";
import { StatCard } from "../common/StatCard";

// Sponsor and Program - the same fields already present on
// GET /api/v1/proposals/{proposalId} (sponsorCode/sponsorName/
// proposalType/activityType), reorganized into their own section
// rather than crowded into the main Summary cards. No new data.
export function ProposalSponsorProgramSection({
  proposalId,
}: {
  proposalId: number;
}) {
  const summaryQuery = useQuery({
    queryKey: ["proposal-summary-v1", proposalId],
    queryFn: ({ signal }) => getProposalSummaryV1(proposalId, signal),
  });

  if (summaryQuery.isLoading) {
    return (
      <Box
        sx={{
          display: "grid",
          gridTemplateColumns: "repeat(2, 1fr)",
          gap: 1.75,
        }}
      >
        {Array.from({ length: 4 }).map((_, index) => (
          <Skeleton key={index} variant="rounded" height={64} />
        ))}
      </Box>
    );
  }

  if (summaryQuery.isError) {
    return <ErrorState message="Unable to load Sponsor and Program." />;
  }

  const summary = summaryQuery.data;

  if (!summary) {
    return null;
  }

  return (
    <Box
      sx={{
        display: "grid",
        gridTemplateColumns: { xs: "1fr", sm: "repeat(2, 1fr)" },
        gap: 1.75,
      }}
    >
      <StatCard label="Sponsor" value={summary.sponsorName ?? "—"} />
      <StatCard label="Sponsor code" value={summary.sponsorCode ?? "—"} />
      <StatCard label="Proposal type" value={summary.proposalType ?? "—"} />
      <StatCard label="Activity type" value={summary.activityType ?? "—"} />
    </Box>
  );
}
