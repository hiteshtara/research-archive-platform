import { ArrowForwardOutlined } from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Skeleton,
  Stack,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";

import { getProposalFundedAwardsV1 } from "../../api/client";
import { AwardStatusPill } from "../award/AwardStatusPill";

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
  const navigate = useNavigate();

  const fundedAwardsQuery = useQuery({
    queryKey: ["proposal-funded-awards-v1", proposalId],
    queryFn: ({ signal }) => getProposalFundedAwardsV1(proposalId, signal),
  });

  if (fundedAwardsQuery.isLoading) {
    return (
      <Stack spacing={1.5}>
        <Skeleton variant="rounded" height={72} />
        <Skeleton variant="rounded" height={72} />
      </Stack>
    );
  }

  if (fundedAwardsQuery.isError) {
    return <Alert severity="error">Unable to load Funded Awards.</Alert>;
  }

  const fundedAwards = fundedAwardsQuery.data ?? [];

  if (fundedAwards.length === 0) {
    return (
      <Typography color="text.secondary">
        No Awards have been funded from this Proposal.
      </Typography>
    );
  }

  return (
    <Stack spacing={1.5}>
      {fundedAwards.map((fundedAward, index) => (
        <Card
          key={`${fundedAward.awardNumber}-${index}`}
          variant="outlined"
          sx={{
            opacity: fundedAward.relationshipActive ? 1 : 0.6,
          }}
        >
          <CardContent
            sx={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              "&:last-child": { pb: 2 },
            }}
          >
            <Box>
              <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <Typography sx={{ fontWeight: 700 }}>
                  {fundedAward.awardNumber}
                </Typography>
                {!fundedAward.relationshipActive && (
                  <Chip size="small" variant="outlined" label="Inactive" />
                )}
              </Stack>

              <Typography variant="body2" color="text.secondary">
                {fundedAward.awardTitle ?? "Untitled award"}
              </Typography>

              <Stack
                direction="row"
                spacing={1}
                sx={{ mt: 0.5, alignItems: "center", flexWrap: "wrap" }}
              >
                <AwardStatusPill status={fundedAward.awardStatus} />
                <Typography variant="caption" color="text.secondary">
                  Current version {fundedAward.currentAwardVersion ?? "—"}
                  {fundedAward.linkedAwardVersion !==
                    fundedAward.currentAwardVersion &&
                    ` (linked at version ${fundedAward.linkedAwardVersion ?? "—"})`}
                  {" · Proposal version "}
                  {fundedAward.proposalVersion ?? "—"}
                </Typography>
              </Stack>
            </Box>

            <Button
              variant="outlined"
              size="small"
              disabled={fundedAward.navigableCurrentAwardId === null}
              endIcon={<ArrowForwardOutlined fontSize="small" />}
              onClick={() =>
                fundedAward.navigableCurrentAwardId !== null &&
                navigate(`/awards/${fundedAward.navigableCurrentAwardId}`)
              }
            >
              Open Award
            </Button>
          </CardContent>
        </Card>
      ))}
    </Stack>
  );
}
