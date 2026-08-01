import { Alert, Box, CircularProgress, Skeleton } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getAwardSummaryV1 } from "../../api/client";

function formatAmount(amount: number | null): string {
  if (amount === null) {
    return "—";
  }

  return amount.toLocaleString("en-US", {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: 0,
  });
}

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

// Approved mockup's stat-card summary grid, fed from the live
// GET /api/v1/awards/{awardId}/summary endpoint.
export function AwardSummarySection({ awardId }: { awardId: number }) {
  const summaryQuery = useQuery({
    queryKey: ["award-summary-v1", awardId],
    queryFn: ({ signal }) => getAwardSummaryV1(awardId, signal),
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
      <Alert severity="error">Unable to load the Award summary.</Alert>
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
      <StatCard label="Sponsor" value={summary.sponsor ?? "—"} />
      <StatCard label="Prime sponsor" value={summary.primeSponsor ?? "—"} />
      <StatCard label="Lead unit" value={summary.leadUnit ?? "—"} />
      <StatCard
        label="Principal investigator"
        value={summary.principalInvestigator ?? "—"}
      />
      <StatCard
        label="Obligated total"
        value={formatAmount(summary.obligatedTotalAmount)}
      />
      <StatCard
        label="Anticipated total"
        value={formatAmount(summary.anticipatedTotalAmount)}
      />
      <StatCard
        label="Award effective date"
        value={summary.awardEffectiveDate ?? "—"}
      />
      <StatCard label="Begin date" value={summary.beginDate ?? "—"} />
      <StatCard label="Closeout date" value={summary.closeoutDate ?? "—"} />
      <StatCard
        label="Basis of payment"
        value={summary.basisOfPaymentDescription ?? "—"}
      />
      <StatCard
        label="Method of payment"
        value={summary.methodOfPaymentDescription ?? "—"}
      />
      <StatCard
        label="Root award number"
        value={summary.rootAwardNumber ?? "This is the root award"}
      />
    </Box>
  );
}

export function AwardSummaryHeaderSkeleton() {
  return (
    <Box sx={{ display: "grid", placeItems: "center", py: 6 }}>
      <CircularProgress />
    </Box>
  );
}
