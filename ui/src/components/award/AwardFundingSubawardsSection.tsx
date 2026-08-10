import { Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getAwardFundingSubawardsV1 } from "../../api/client";
import { formatCurrencyAmount as formatAmount } from "../../features/award/awardSectionsPresentation.mjs";
import {
  RELATIONSHIP_ACTION_LABEL,
  resolveRelationshipCardState,
} from "../../features/common/relationshipCardPresentation.mjs";
import { EmptyState } from "../common/EmptyState";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";
import { RelationshipCard } from "../common/RelationshipCard";
import { StatusPill } from "../common/StatusPill";

// Subaward(s) - the bidirectional counterpart to a Subaward's own
// Associated Award(s) card (SubawardWorkspacePage.tsx). One card per
// real archive.subaward_funding relationship row (family-wide - every
// award_id in this Award's whole award_number family). Subaward
// funding sources have no active/inactive flag in the source schema
// (unlike Award<->Proposal), so every row here is a real, standing
// relationship - never filtered. Fed from
// GET /api/v1/awards/{awardId}/funding-subawards, which already
// resolves the linked Subaward's CURRENT (ACTIVE) version server-side
// (navigableCurrentSubawardId) - "Open Subaward" navigates directly.
export function AwardFundingSubawardsSection({
  awardId,
}: {
  awardId: number;
}) {
  const fundingSubawardsQuery = useQuery({
    queryKey: ["award-funding-subawards-v1", awardId],
    queryFn: ({ signal }) => getAwardFundingSubawardsV1(awardId, signal),
  });

  if (fundingSubawardsQuery.isLoading) {
    return <LoadingState mode="skeleton" height={72} />;
  }

  if (fundingSubawardsQuery.isError) {
    return <ErrorState message="Unable to load Subawards." />;
  }

  const fundingSubawards = fundingSubawardsQuery.data ?? [];

  if (fundingSubawards.length === 0) {
    return (
      <EmptyState
        variant="text"
        message="No Subaward is recorded against this Award."
      />
    );
  }

  return (
    <Stack spacing={1.5}>
      <Typography variant="h6">
        {fundingSubawards.length === 1
          ? "Associated Subaward"
          : `Associated Subawards (${fundingSubawards.length})`}
      </Typography>

      {fundingSubawards.map((link, index) => {
        const { archived } = resolveRelationshipCardState(
          link.navigableCurrentSubawardId,
        );

        return (
          <RelationshipCard
            key={`${link.subawardCode}-${index}`}
            title={`Subaward ${link.subawardCode}`}
            archived={archived}
            statusLabel={
              <StatusPill status={link.subawardStatus} domain="subaward" />
            }
            metaText={
              link.organizationId
                ? `Organization ${link.organizationId}`
                : null
            }
            amountText={
              link.subawardAmount !== null
                ? formatAmount(link.subawardAmount)
                : null
            }
            buttonLabel={RELATIONSHIP_ACTION_LABEL.subaward}
            href={
              archived
                ? `/subawards/${link.navigableCurrentSubawardId}`
                : undefined
            }
          />
        );
      })}
    </Stack>
  );
}
