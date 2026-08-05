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
import { formatCurrencyAmount as formatAmount } from "../../features/award/awardSectionsPresentation.mjs";
import { AwardStatusPill } from "./AwardStatusPill";

// Funding Proposal(s) - the bidirectional counterpart to Institutional
// Proposal's own Funded Awards section. One card per real
// archive.award_funding_proposal relationship row (family-wide - every
// award_id in this Award's whole award_number family), including
// inactive relationships (shown, labeled Inactive, never hidden). Fed
// from GET /api/v1/awards/{awardId}/funding-proposals, which already
// resolves the linked Proposal's ACTIVE version server-side
// (navigableActiveProposalId) - "Open Proposal" navigates directly.
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

  const fundingProposals = fundingProposalsQuery.data ?? [];

  if (fundingProposals.length === 0) {
    return (
      <Typography color="text.secondary">
        No Funding Proposal is recorded for this Award.
      </Typography>
    );
  }

  return (
    <Stack spacing={1.5}>
      {fundingProposals.map((link, index) => (
        <Card
          key={`${link.proposalNumber}-${index}`}
          variant="outlined"
          sx={{ opacity: link.relationshipActive ? 1 : 0.6 }}
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
                  Institutional Proposal {link.proposalNumber}
                </Typography>
                {!link.relationshipActive && (
                  <Chip size="small" variant="outlined" label="Inactive" />
                )}
              </Stack>

              <Typography variant="body2" color="text.secondary">
                {link.proposalTitle ?? "Untitled proposal"}
              </Typography>

              <Stack
                direction="row"
                spacing={1}
                sx={{ mt: 0.5, alignItems: "center", flexWrap: "wrap" }}
              >
                <AwardStatusPill status={link.proposalStatus} />
                {link.workflowDocumentNumber && (
                  <Chip
                    size="small"
                    variant="outlined"
                    label={`Workflow ${link.workflowDocumentNumber}`}
                  />
                )}
                <Typography variant="caption" color="text.secondary">
                  {link.principalInvestigatorName ?? "—"}
                  {link.sponsorName ? ` · ${link.sponsorName}` : ""}
                  {link.requestedTotalCost !== null
                    ? ` · Requested ${formatAmount(link.requestedTotalCost)}`
                    : ""}
                </Typography>
              </Stack>
            </Box>

            <Button
              variant="outlined"
              size="small"
              endIcon={<ArrowForwardOutlined fontSize="small" />}
              disabled={link.navigableActiveProposalId === null}
              onClick={() =>
                link.navigableActiveProposalId !== null &&
                navigate(`/proposals/dashboard/${link.navigableActiveProposalId}`)
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
