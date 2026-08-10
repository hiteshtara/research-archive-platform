import { Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getAwardAssociatedNegotiationsV1 } from "../../api/client";
import {
  RELATIONSHIP_ACTION_LABEL,
  resolveNegotiationCardTitle,
} from "../../features/common/relationshipCardPresentation.mjs";
import { EmptyState } from "../common/EmptyState";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";
import { RelationshipCard } from "../common/RelationshipCard";
import { StatusPill } from "../common/StatusPill";

// Negotiations - the bidirectional counterpart to a Negotiation's own
// "Associated Record" link (NegotiationWorkspacePage.tsx). One card per
// real archive.negotiation row, family-wide, whose association
// resolves to this Award's whole award_number family. Unlike Subaward,
// Negotiation has no version/family concept of its own - every card's
// "Open Negotiation" is always navigable, never disabled.
export function AwardAssociatedNegotiationsSection({
  awardId,
}: {
  awardId: number;
}) {
  const negotiationsQuery = useQuery({
    queryKey: ["award-associated-negotiations-v1", awardId],
    queryFn: ({ signal }) => getAwardAssociatedNegotiationsV1(awardId, signal),
  });

  if (negotiationsQuery.isLoading) {
    return <LoadingState mode="skeleton" height={72} />;
  }

  if (negotiationsQuery.isError) {
    return <ErrorState message="Unable to load Negotiations." />;
  }

  const negotiations = negotiationsQuery.data ?? [];

  if (negotiations.length === 0) {
    return (
      <EmptyState
        variant="text"
        message="No Negotiation is recorded against this Award."
      />
    );
  }

  return (
    <Stack spacing={1.5}>
      <Typography variant="h6">
        {negotiations.length === 1
          ? "Associated Negotiation"
          : `Associated Negotiations (${negotiations.length})`}
      </Typography>

      {negotiations.map((negotiation) => (
        <RelationshipCard
          key={negotiation.negotiationId}
          title={resolveNegotiationCardTitle(negotiation)}
          archived
          statusLabel={
            <StatusPill
              status={negotiation.negotiationStatusDescription}
              domain="negotiation"
            />
          }
          metaText={negotiation.negotiationAgreementTypeDescription}
          amountText={
            negotiation.negotiatorFullName
              ? `Negotiator: ${negotiation.negotiatorFullName}`
              : null
          }
          buttonLabel={RELATIONSHIP_ACTION_LABEL.negotiation}
          href={`/negotiations/${negotiation.negotiationId}`}
        />
      ))}
    </Stack>
  );
}
