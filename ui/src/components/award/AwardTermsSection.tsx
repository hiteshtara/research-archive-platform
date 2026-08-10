import { ExpandMoreOutlined } from "@mui/icons-material";
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  Chip,
  Stack,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getAwardTermsV1 } from "../../api/client";
import { hasAnyTerms } from "../../features/award/awardSectionsPresentation.mjs";
import { EmptyState } from "../common/EmptyState";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";

// Terms - lazy-loads from GET /api/v1/awards/{awardId}/terms. Sponsor
// terms and report terms are kept as two separate groups, never
// merged; each report term's recipients collapse under it.
export function AwardTermsSection({ awardId }: { awardId: number }) {
  const termsQuery = useQuery({
    queryKey: ["award-terms-v1", awardId],
    queryFn: ({ signal }) => getAwardTermsV1(awardId, signal),
  });

  if (termsQuery.isLoading) {
    return <LoadingState mode="skeleton" heights={[64, 160]} />;
  }

  if (termsQuery.isError) {
    return <ErrorState message="Unable to load Terms." />;
  }

  const terms = termsQuery.data;

  if (!terms) {
    return null;
  }

  if (!hasAnyTerms(terms.sponsorTerms, terms.reportTerms)) {
    return (
      <EmptyState
        variant="text"
        message="No terms are recorded for this Award version."
      />
    );
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="overline" color="text.secondary">
          Sponsor terms
        </Typography>

        {terms.sponsorTerms.length === 0 ? (
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            No sponsor terms recorded.
          </Typography>
        ) : (
          <Stack direction="row" spacing={1} sx={{ mt: 0.5, flexWrap: "wrap" }}>
            {terms.sponsorTerms.map((term) => (
              <Chip
                key={term.awardSponsorTermId}
                variant="outlined"
                label={`Sponsor Term ${term.sponsorTermId ?? "—"}`}
              />
            ))}
          </Stack>
        )}
      </Box>

      <Box>
        <Typography variant="overline" color="text.secondary">
          Report terms
        </Typography>

        {terms.reportTerms.length === 0 ? (
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            No report terms recorded.
          </Typography>
        ) : (
          <Stack spacing={1} sx={{ mt: 0.5 }}>
            {terms.reportTerms.map((term) => (
              <Accordion key={term.awardReportTermId} variant="outlined" disableGutters>
                <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
                  <Stack sx={{ minWidth: 0 }}>
                    <Typography sx={{ fontWeight: 700 }}>
                      {term.reportCode ?? term.reportClassCode ?? "Report term"}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {term.frequencyCode ?? "Frequency unrecorded"}
                      {term.dueDate ? ` · Due ${term.dueDate}` : ""}
                      {` · ${term.recipients.length} recipient${
                        term.recipients.length === 1 ? "" : "s"
                      }`}
                    </Typography>
                  </Stack>
                </AccordionSummary>

                <AccordionDetails>
                  <Typography variant="body2" color="text.secondary">
                    Class {term.reportClassCode ?? "—"} · Frequency base{" "}
                    {term.frequencyBaseCode ?? "—"} · OSP distribution{" "}
                    {term.ospDistributionCode ?? "—"}
                  </Typography>

                  {term.recipients.length === 0 ? (
                    <Typography color="text.secondary" sx={{ mt: 1 }}>
                      No recipients recorded.
                    </Typography>
                  ) : (
                    <Stack spacing={0.5} sx={{ mt: 1 }}>
                      {term.recipients.map((recipient) => (
                        <Typography
                          key={recipient.awardReportTermRecipientId}
                          variant="body2"
                        >
                          {recipient.contactTypeCode ?? "Recipient"}
                          {recipient.contactId
                            ? ` · Contact ${recipient.contactId}`
                            : ""}
                          {recipient.numberOfCopies
                            ? ` · ${recipient.numberOfCopies} copies`
                            : ""}
                        </Typography>
                      ))}
                    </Stack>
                  )}
                </AccordionDetails>
              </Accordion>
            ))}
          </Stack>
        )}
      </Box>
    </Stack>
  );
}
