import { CheckCircle, RadioButtonUnchecked } from "@mui/icons-material";
import { Box, Card, CardContent, Skeleton, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getProposalFundedAwardsV1, getProposalSummaryV1 } from "../../api/client";
import { formatCurrencyAmount as formatAmount } from "../../features/award/awardSectionsPresentation.mjs";
import { ErrorState } from "../common/ErrorState";
import { StatCard } from "../common/StatCard";

function dateRange(start: string | null, end: string | null): string {
  if (!start && !end) {
    return "—";
  }
  return `${start ?? "—"} → ${end ?? "—"}`;
}

function LifecycleStep({
  label,
  done,
}: {
  label: string;
  done: boolean;
}) {
  const Icon = done ? CheckCircle : RadioButtonUnchecked;
  return (
    <Stack direction="row" spacing={0.75} sx={{ alignItems: "center" }}>
      <Icon
        fontSize="small"
        color={done ? "success" : "disabled"}
      />
      <Typography
        variant="body2"
        color={done ? "text.primary" : "text.disabled"}
        sx={{ fontWeight: done ? 600 : 400 }}
      >
        {label}
      </Typography>
    </Stack>
  );
}

// Fed from the live GET /api/v1/proposals/{proposalId} endpoint.
// Card-based cost/period stats (matching the Award dashboard's own
// visual language) rather than a long key-value table - identifying
// fields (proposalNumber, sequenceNumber, workflowDocumentNumber, PI,
// sponsor, lead unit) live in the dashboard's own header instead of
// being repeated here.
export function ProposalSummarySection({
  proposalId,
}: {
  proposalId: number;
}) {
  const summaryQuery = useQuery({
    queryKey: ["proposal-summary-v1", proposalId],
    queryFn: ({ signal }) => getProposalSummaryV1(proposalId, signal),
  });

  const fundedAwardsQuery = useQuery({
    queryKey: ["proposal-funded-awards-v1", proposalId],
    queryFn: ({ signal }) => getProposalFundedAwardsV1(proposalId, signal),
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
        {Array.from({ length: 8 }).map((_, index) => (
          <Skeleton key={index} variant="rounded" height={64} />
        ))}
      </Box>
    );
  }

  if (summaryQuery.isError) {
    return <ErrorState message="Unable to load the Proposal summary." />;
  }

  const summary = summaryQuery.data;

  if (!summary) {
    return null;
  }

  const hasFundedAward = (fundedAwardsQuery.data?.length ?? 0) > 0;

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="overline" color="text.secondary">
          Initial Costs
        </Typography>
        <Box
          sx={{
            mt: 0.5,
            display: "grid",
            gridTemplateColumns: { xs: "1fr", sm: "repeat(3, 1fr)" },
            gap: 1.75,
          }}
        >
          <StatCard label="Initial Direct" value={formatAmount(summary.initialDirectCost)} />
          <StatCard label="Initial Indirect" value={formatAmount(summary.initialIndirectCost)} />
          <StatCard label="Initial Total" value={formatAmount(summary.initialTotalCost)} />
        </Box>
      </Box>

      <Box>
        <Typography variant="overline" color="text.secondary">
          Total Costs
        </Typography>
        <Box
          sx={{
            mt: 0.5,
            display: "grid",
            gridTemplateColumns: { xs: "1fr", sm: "repeat(3, 1fr)" },
            gap: 1.75,
          }}
        >
          <StatCard label="Total Direct" value={formatAmount(summary.totalDirectCost)} />
          <StatCard label="Total Indirect" value={formatAmount(summary.totalIndirectCost)} />
          <StatCard label="Total Cost" value={formatAmount(summary.totalCost)} />
        </Box>
      </Box>

      <Box
        sx={{
          display: "grid",
          gridTemplateColumns: { xs: "1fr", sm: "repeat(2, 1fr)" },
          gap: 1.75,
        }}
      >
        <StatCard
          label="Initial Period"
          value={dateRange(summary.initialStartDate, summary.initialEndDate)}
        />
        <StatCard
          label="Total Period"
          value={dateRange(summary.totalStartDate, summary.totalEndDate)}
        />
      </Box>

      <Card variant="outlined">
        <CardContent>
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ textTransform: "uppercase", letterSpacing: "0.04em" }}
          >
            Research Lifecycle
          </Typography>

          <Stack
            direction="row"
            spacing={2.5}
            sx={{ mt: 1, flexWrap: "wrap", alignItems: "center" }}
          >
            <LifecycleStep label="Proposal Log" done />
            <LifecycleStep label="Institutional Proposal" done />
            <LifecycleStep label="Award" done={hasFundedAward} />
            <LifecycleStep label="Budget" done={false} />
            <LifecycleStep label="Time & Money" done={false} />
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  );
}
