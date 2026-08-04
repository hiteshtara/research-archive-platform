import { Alert, Box, Skeleton } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getProposalSummaryV1 } from "../../api/client";

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <Box
      sx={{
        backgroundColor: "action.hover",
        borderRadius: 1.5,
        p: 2,
      }}
    >
      <Box
        sx={{
          fontSize: 10.5,
          color: "text.secondary",
          textTransform: "uppercase",
          letterSpacing: "0.04em",
          fontFamily: "monospace",
        }}
      >
        {label}
      </Box>
      <Box sx={{ fontSize: 16, fontWeight: 700, mt: 0.5 }}>{value}</Box>
    </Box>
  );
}

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
    return (
      <Alert severity="error">Unable to load Sponsor and Program.</Alert>
    );
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
