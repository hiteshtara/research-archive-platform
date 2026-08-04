import { Alert, Box, Skeleton } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getProposalSummaryV1 } from "../../api/client";
import { formatCurrencyAmount as formatAmount } from "../../features/award/awardSectionsPresentation.mjs";

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

function dateRange(start: string | null, end: string | null): string {
  if (!start && !end) {
    return "—";
  }
  return `${start ?? "—"} – ${end ?? "—"}`;
}

// Fed from the live GET /api/v1/proposals/{proposalId} endpoint.
// proposalNumber, sequenceNumber, and workflowDocumentNumber are three
// distinct identifiers, each shown explicitly - never inferred one
// from another.
export function ProposalSummarySection({
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
          gridTemplateColumns: "repeat(3, 1fr)",
          gap: 1.75,
        }}
      >
        {Array.from({ length: 9 }).map((_, index) => (
          <Skeleton key={index} variant="rounded" height={64} />
        ))}
      </Box>
    );
  }

  if (summaryQuery.isError) {
    return (
      <Alert severity="error">Unable to load the Proposal summary.</Alert>
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
        gridTemplateColumns: { xs: "1fr", sm: "repeat(2, 1fr)", md: "repeat(3, 1fr)" },
        gap: 1.75,
      }}
    >
      <StatCard label="Proposal number" value={summary.proposalNumber} />
      <StatCard
        label="Workflow document"
        value={summary.workflowDocumentNumber ?? "—"}
      />
      <StatCard
        label="Proposal version"
        value={String(summary.sequenceNumber)}
      />
      <StatCard label="Status" value={summary.status ?? "—"} />
      <StatCard label="Proposal type" value={summary.proposalType ?? "—"} />
      <StatCard label="Activity type" value={summary.activityType ?? "—"} />
      <StatCard label="Lead unit" value={summary.leadUnitName ?? "—"} />
      <StatCard label="Sponsor" value={summary.sponsorName ?? "—"} />
      <StatCard
        label="Principal investigator"
        value={summary.principalInvestigatorName ?? "—"}
      />
      <StatCard
        label="Requested dates"
        value={dateRange(summary.initialStartDate, summary.initialEndDate)}
      />
      <StatCard
        label="Initial total cost"
        value={formatAmount(summary.initialTotalCost)}
      />
      <StatCard
        label="Total cost"
        value={formatAmount(summary.totalCost)}
      />
    </Box>
  );
}
