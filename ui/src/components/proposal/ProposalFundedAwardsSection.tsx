import { ArrowForwardOutlined } from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Skeleton,
  Stack,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";

import {
  getProposalFundedAwardsV1,
  resolveAwardByNumberV1,
} from "../../api/client";
import { AwardStatusPill } from "../award/AwardStatusPill";

// Funded Awards - the primary navigation link from an Institutional
// Proposal to the Award(s) it funded, family-wide (every version of
// this Proposal's proposalNumber). Fed from GET
// /api/v1/proposals/{proposalId}/funded-awards, which never returns an
// internal awardId - "Open Award" resolves the current awardId only at
// click-time via GET /api/v1/awards/by-number/{awardNumber}, so no
// internal identifier is ever stored, displayed, or present in this
// component's own state beyond the moment of navigation.
export function ProposalFundedAwardsSection({
  proposalId,
}: {
  proposalId: number;
}) {
  const navigate = useNavigate();
  const [openingAwardNumber, setOpeningAwardNumber] = useState<string | null>(
    null,
  );
  const [openError, setOpenError] = useState<string | null>(null);

  const fundedAwardsQuery = useQuery({
    queryKey: ["proposal-funded-awards-v1", proposalId],
    queryFn: ({ signal }) => getProposalFundedAwardsV1(proposalId, signal),
  });

  async function openAward(awardNumber: string) {
    setOpenError(null);
    setOpeningAwardNumber(awardNumber);
    try {
      const identifier = await resolveAwardByNumberV1(awardNumber);
      navigate(`/awards/${identifier.awardId}`);
    } catch (error) {
      setOpenError(
        error instanceof Error
          ? error.message
          : `Unable to open Award ${awardNumber}.`,
      );
    } finally {
      setOpeningAwardNumber(null);
    }
  }

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
      {openError && <Alert severity="error">{openError}</Alert>}

      {fundedAwards.map((fundedAward) => {
        const isOpening = openingAwardNumber === fundedAward.awardNumber;

        return (
          <Card key={fundedAward.awardNumber} variant="outlined">
            <CardContent
              sx={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
                "&:last-child": { pb: 2 },
              }}
            >
              <Box>
                <Typography sx={{ fontWeight: 700 }}>
                  {fundedAward.awardNumber}
                </Typography>
                <Stack direction="row" spacing={1} sx={{ mt: 0.5, alignItems: "center" }}>
                  <Typography variant="body2" color="text.secondary">
                    Version {fundedAward.sequenceNumber ?? "—"}
                  </Typography>
                  <AwardStatusPill status={fundedAward.status} />
                </Stack>
              </Box>

              <Button
                variant="outlined"
                size="small"
                disabled={isOpening}
                endIcon={
                  isOpening ? (
                    <CircularProgress size={14} />
                  ) : (
                    <ArrowForwardOutlined fontSize="small" />
                  )
                }
                onClick={() => openAward(fundedAward.awardNumber)}
              >
                Open Award
              </Button>
            </CardContent>
          </Card>
        );
      })}
    </Stack>
  );
}
