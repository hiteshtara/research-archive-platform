import { Chip, Stack } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getAwardFundingProposalsV1 } from "../../api/client";
import {
  buildFundingProposalMetaText,
  RELATIONSHIP_ACTION_LABEL,
  resolveFundingProposalCardSubtitle,
  resolveFundingProposalCardTitle,
  resolveRelationshipCardState,
} from "../../features/common/relationshipCardPresentation.mjs";
import { EmptyState } from "../common/EmptyState";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";
import { RelationshipCard } from "../common/RelationshipCard";
import { StatusPill } from "../common/StatusPill";

// Funding Proposal(s) - the bidirectional counterpart to Institutional
// Proposal's own Funded Awards section. One card per real
// archive.award_funding_proposal relationship row (family-wide - every
// award_id in this Award's whole award_number family), including
// inactive relationships (shown, labeled Inactive, never hidden) AND
// relationships whose linked Proposal hasn't been archived yet (shown
// with a "Proposal details not yet archived" fallback title/subtitle,
// never dropped - the preserved archive.award_funding_proposal row is
// the authoritative source of whether a relationship exists, not
// whether archive.proposal_version happens to have caught up yet - see
// docs/architecture/AWARD_FUNDING_PROPOSAL_UI_GAP_INVESTIGATION.md).
// Fed from GET /api/v1/awards/{awardId}/funding-proposals, which
// already resolves the linked Proposal's ACTIVE version server-side
// (navigableActiveProposalId) - "Open Proposal" navigates directly, and
// resolveRelationshipCardState already correctly disables that button
// (no href) when no navigable version exists yet, so an unresolved
// relationship never produces a broken link.
export function AwardFundingProposalsSection({
  awardId,
}: {
  awardId: number;
}) {
  const fundingProposalsQuery = useQuery({
    queryKey: ["award-funding-proposals-v1", awardId],
    queryFn: ({ signal }) => getAwardFundingProposalsV1(awardId, signal),
  });

  if (fundingProposalsQuery.isLoading) {
    return <LoadingState mode="skeleton" height={72} />;
  }

  if (fundingProposalsQuery.isError) {
    return <ErrorState message="Unable to load Funding Proposals." />;
  }

  const fundingProposals = fundingProposalsQuery.data ?? [];

  if (fundingProposals.length === 0) {
    return (
      <EmptyState
        variant="text"
        message="No Funding Proposal is recorded for this Award."
      />
    );
  }

  return (
    <Stack spacing={1.5}>
      {fundingProposals.map((link) => {
        const { archived } = resolveRelationshipCardState(
          link.navigableActiveProposalId,
        );

        return (
          <RelationshipCard
            key={link.awardFundingProposalId}
            title={resolveFundingProposalCardTitle(link)}
            subtitle={resolveFundingProposalCardSubtitle(link)}
            archived={archived}
            inactive={!link.relationshipActive}
            statusLabel={
              <>
                <StatusPill
                  status={link.proposalStatus}
                  domain="proposal"
                />
                {link.workflowDocumentNumber && (
                  <Chip
                    size="small"
                    variant="outlined"
                    label={`Workflow ${link.workflowDocumentNumber}`}
                  />
                )}
              </>
            }
            metaText={buildFundingProposalMetaText(link)}
            buttonLabel={RELATIONSHIP_ACTION_LABEL.proposal}
            href={
              archived
                ? `/proposals/dashboard/${link.navigableActiveProposalId}`
                : undefined
            }
          />
        );
      })}
    </Stack>
  );
}
