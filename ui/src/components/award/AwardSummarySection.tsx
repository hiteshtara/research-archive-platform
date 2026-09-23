import { Box, Skeleton, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getAwardSummaryV1 } from "../../api/client";
import { formatCurrencyAmount as formatAmount } from "../../features/award/awardSectionsPresentation.mjs";
import { buildAwardSummaryGroups } from "../../features/award/awardSummaryPresentation.mjs";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";
import { StatCard } from "../common/StatCard";

type SummaryField = {
  label: string;
  value: string;
  caption?: string;
};

type SummaryGroup = {
  title: string;
  fields: SummaryField[];
};

const GRID = {
  display: "grid",
  gridTemplateColumns: {
    xs: "1fr",
    sm: "repeat(2, 1fr)",
    md: "repeat(3, 1fr)",
  },
  gap: 1.75,
} as const;

// Grouped stat-card summary fed from GET /api/v1/awards/{awardId}/summary.
//
// Every business label rendered here comes from
// awardSummaryPresentation.mjs and matches the legacy Kuali Award screen
// exactly - see that module's header. "Begin Date", "Closeout Date" and
// "Award Effective Date" are deliberately absent: the first two are
// near-empty source columns and the third is the same value now
// correctly labelled "Project Start Date".
export function AwardSummarySection({ awardId }: { awardId: number }) {
  const summaryQuery = useQuery({
    queryKey: ["award-summary-v1", awardId],
    queryFn: ({ signal }) => getAwardSummaryV1(awardId, signal),
  });

  if (summaryQuery.isLoading) {
    return (
      <Box sx={GRID}>
        {Array.from({ length: 9 }).map((_, index) => (
          <Skeleton key={index} variant="rounded" height={64} />
        ))}
      </Box>
    );
  }

  if (summaryQuery.isError) {
    return <ErrorState message="Unable to load the Award summary." />;
  }

  const summary = summaryQuery.data;

  if (!summary) {
    return null;
  }

  const groups = buildAwardSummaryGroups(summary) as SummaryGroup[];

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
      {groups.map((group) => (
        <Box key={group.title}>
          <Typography
            variant="overline"
            color="text.secondary"
            sx={{ display: "block", mb: 1, letterSpacing: "0.08em" }}
          >
            {group.title}
          </Typography>
          <Box sx={GRID}>
            {group.fields.map((field) => (
              <StatCard
                key={field.label}
                label={field.label}
                value={field.value}
                variant={field.caption ? "outlined" : "filled"}
                caption={field.caption}
              />
            ))}
          </Box>
        </Box>
      ))}

      <Box>
        <Typography
          variant="overline"
          color="text.secondary"
          sx={{ display: "block", mb: 1, letterSpacing: "0.08em" }}
        >
          Financial
        </Typography>
        <Box sx={GRID}>
          <StatCard
            label="Obligated Total"
            value={formatAmount(summary.obligatedTotalAmount)}
          />
          <StatCard
            label="Anticipated Total"
            value={formatAmount(summary.anticipatedTotalAmount)}
          />
          <StatCard
            label="Basis of Payment"
            value={summary.basisOfPaymentDescription ?? "—"}
          />
          <StatCard
            label="Method of Payment"
            value={summary.methodOfPaymentDescription ?? "—"}
          />
          <StatCard
            label="Principal Investigator"
            value={summary.principalInvestigator ?? "—"}
          />
          <StatCard
            label="Root Award Number"
            value={summary.rootAwardNumber ?? "This is the root award"}
          />
        </Box>
      </Box>
    </Box>
  );
}

export function AwardSummaryHeaderSkeleton() {
  return <LoadingState mode="spinner" />;
}
