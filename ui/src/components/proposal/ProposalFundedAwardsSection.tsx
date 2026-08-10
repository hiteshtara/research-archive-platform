import { Stack } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getProposalFundedAwardsV1 } from "../../api/client";
import {
  buildFundedAwardMetaText,
  RELATIONSHIP_ACTION_LABEL,
  resolveRelationshipCardState,
} from "../../features/common/relationshipCardPresentation.mjs";
import { EmptyState } from "../common/EmptyState";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";
import { RelationshipCard } from "../common/RelationshipCard";
import { StatusPill } from "../common/StatusPill";

// Funded Awards - the primary navigation link from an Institutional
// Proposal to the Award(s) it funded. One card per real
// archive.proposal_award relationship row (family-wide - every
// version of this Proposal's proposalNumber), including inactive
// relationships (shown, labeled Inactive, never hidden). Fed from GET
// /api/v1/proposals/{proposalId}/funded-awards, which already resolves
// the linked Award's CURRENT version server-side
// (navigableCurrentAwardId) - "Open Award" navigates directly, no
// separate resolve call needed. The exact historical award linked at
// submission time (exactLinkedAwardId/linkedAwardVersion) is kept for
// audit but never rendered as visible text.
export function ProposalFundedAwardsSection({
  proposalId,
}: {
  proposalId: number;
}) {
  const fundedAwardsQuery = useQuery({
    queryKey: ["proposal-funded-awards-v1", proposalId],
    queryFn: ({ signal }) => getProposalFundedAwardsV1(proposalId, signal),
  });

  if (fundedAwardsQuery.isLoading) {
    return <LoadingState mode="skeleton" height={72} count={2} />;
  }

  if (fundedAwardsQuery.isError) {
    return <ErrorState message="Unable to load Funded Awards." />;
  }

  const fundedAwards = fundedAwardsQuery.data ?? [];

  if (fundedAwards.length === 0) {
    return (
      <EmptyState
        variant="text"
        message="No Awards have been funded from this Proposal."
      />
    );
  }

  return (
    <Stack spacing={1.5}>
      {fundedAwards.map((fundedAward, index) => {
        const { archived } = resolveRelationshipCardState(
          fundedAward.navigableCurrentAwardId,
        );

        return (
          <RelationshipCard
            key={`${fundedAward.awardNumber}-${index}`}
            title={fundedAward.awardNumber}
            subtitle={fundedAward.awardTitle ?? "Untitled award"}
            archived={archived}
            inactive={!fundedAward.relationshipActive}
            statusLabel={
              <StatusPill status={fundedAward.awardStatus} domain="award" />
            }
            metaText={buildFundedAwardMetaText(fundedAward)}
            buttonLabel={RELATIONSHIP_ACTION_LABEL.award}
            href={
              archived
                ? `/awards/${fundedAward.navigableCurrentAwardId}`
                : undefined
            }
          />
        );
      })}
    </Stack>
  );
}
