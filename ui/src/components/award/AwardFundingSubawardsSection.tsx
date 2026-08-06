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

import { getAwardFundingSubawardsV1 } from "../../api/client";
import { formatCurrencyAmount as formatAmount } from "../../features/award/awardSectionsPresentation.mjs";
import { AwardStatusPill } from "./AwardStatusPill";

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
  const navigate = useNavigate();

  const fundingSubawardsQuery = useQuery({
    queryKey: ["award-funding-subawards-v1", awardId],
    queryFn: ({ signal }) => getAwardFundingSubawardsV1(awardId, signal),
  });

  if (fundingSubawardsQuery.isLoading) {
    return (
      <Stack spacing={1.5}>
        <Skeleton variant="rounded" height={72} />
      </Stack>
    );
  }

  if (fundingSubawardsQuery.isError) {
    return <Alert severity="error">Unable to load Subawards.</Alert>;
  }

  const fundingSubawards = fundingSubawardsQuery.data ?? [];

  if (fundingSubawards.length === 0) {
    return (
      <Typography color="text.secondary">
        No Subaward is recorded against this Award.
      </Typography>
    );
  }

  return (
    <Stack spacing={1.5}>
      <Typography variant="h6">
        {fundingSubawards.length === 1
          ? "Associated Subaward"
          : `Associated Subawards (${fundingSubawards.length})`}
      </Typography>

      {fundingSubawards.map((link, index) => (
        <Card key={`${link.subawardCode}-${index}`} variant="outlined">
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
                Subaward {link.subawardCode}
              </Typography>

              <Stack
                direction="row"
                spacing={1}
                sx={{ mt: 0.5, alignItems: "center", flexWrap: "wrap" }}
              >
                {link.navigableCurrentSubawardId !== null && (
                  <AwardStatusPill status={link.subawardStatus} />
                )}
                {link.navigableCurrentSubawardId === null && (
                  <Chip
                    size="small"
                    variant="outlined"
                    label="Not currently archived"
                  />
                )}
                {link.organizationId && (
                  <Typography variant="caption" color="text.secondary">
                    Organization {link.organizationId}
                  </Typography>
                )}
                {link.subawardAmount !== null && (
                  <Typography variant="caption" color="text.secondary">
                    {formatAmount(link.subawardAmount)}
                  </Typography>
                )}
              </Stack>
            </Box>

            <Button
              variant="outlined"
              size="small"
              disabled={link.navigableCurrentSubawardId === null}
              endIcon={<ArrowForwardOutlined fontSize="small" />}
              onClick={() =>
                link.navigableCurrentSubawardId !== null &&
                navigate(`/subawards/${link.navigableCurrentSubawardId}`)
              }
            >
              Open Subaward
            </Button>
          </CardContent>
        </Card>
      ))}
    </Stack>
  );
}
