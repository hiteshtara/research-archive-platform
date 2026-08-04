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

import { getAwardFundingProposalsV1 } from "../../api/client";

function isActive(activeFlag: string | null): boolean {
  return ["Y", "YES", "TRUE", "1"].includes(
    (activeFlag ?? "").trim().toUpperCase(),
  );
}

// Funding Proposal(s) - the bidirectional counterpart to Institutional
// Proposal's own Funded Awards section. Fed from GET
// /api/v1/awards/{awardId}/funding-proposals. proposalId links
// directly to the Institutional Proposal dashboard - a real Proposal
// identifier, not an Award one.
export function AwardFundingProposalsSection({
  awardId,
}: {
  awardId: number;
}) {
  const navigate = useNavigate();

  const fundingProposalsQuery = useQuery({
    queryKey: ["award-funding-proposals-v1", awardId],
    queryFn: ({ signal }) => getAwardFundingProposalsV1(awardId, signal),
  });

  if (fundingProposalsQuery.isLoading) {
    return (
      <Stack spacing={1.5}>
        <Skeleton variant="rounded" height={72} />
      </Stack>
    );
  }

  if (fundingProposalsQuery.isError) {
    return <Alert severity="error">Unable to load Funding Proposals.</Alert>;
  }

  const links = fundingProposalsQuery.data ?? [];

  if (links.length === 0) {
    return (
      <Typography color="text.secondary">
        No Funding Proposal is recorded for this Award.
      </Typography>
    );
  }

  return (
    <Stack spacing={1.5}>
      {links.map((link) => (
        <Card key={link.awardFundingProposalId ?? link.proposalId} variant="outlined">
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
                Institutional Proposal
              </Typography>
              <Stack direction="row" spacing={1} sx={{ mt: 0.5, alignItems: "center" }}>
                {isActive(link.activeFlag) && (
                  <Chip size="small" color="success" label="Active" />
                )}
                {link.sourceUpdateTimestamp && (
                  <Typography variant="body2" color="text.secondary">
                    Updated {link.sourceUpdateTimestamp}
                  </Typography>
                )}
              </Stack>
            </Box>

            <Button
              variant="outlined"
              size="small"
              endIcon={<ArrowForwardOutlined fontSize="small" />}
              disabled={link.proposalId === null}
              onClick={() =>
                link.proposalId !== null &&
                navigate(`/proposals/dashboard/${link.proposalId}`)
              }
            >
              Open Proposal
            </Button>
          </CardContent>
        </Card>
      ))}
    </Stack>
  );
}
