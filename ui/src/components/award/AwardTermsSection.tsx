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
import {
  formatAdvanceNotice,
  groupAwardSponsorTerms,
  hasAnyTerms,
  resolveAwardReportTermFieldLabel,
  resolveAwardReportTermHeading,
  resolveAwardReportTermRecipientLabel,
  resolveAwardSponsorTermLabel,
} from "../../features/award/awardSectionsPresentation.mjs";
import type { AwardReportTermV1 } from "../../types/api";
import { EmptyState } from "../common/EmptyState";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";

// Terms - lazy-loads from GET /api/v1/awards/{awardId}/terms. Sponsor
// terms and report terms are kept as two separate groups, never
// merged; each report term's recipients collapse under it.
//
// Sponsor terms are grouped by the 10 Kuali categories, in their own
// authoritative numeric code order (see groupAwardSponsorTerms) -
// mirroring AwardCustomDataSection's category-accordion layout. Report
// terms render every field through an explicit label rather than a
// bare code, falling back to the raw code only when its lookup is
// unresolved (see resolveAwardReportTermFieldLabel) - "No" is a real,
// valid resolved value (e.g. OSP Distribution), never treated as blank.
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

  const sponsorTermGroups = groupAwardSponsorTerms(terms.sponsorTerms);

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
          <Stack spacing={1} sx={{ mt: 0.5 }}>
            {sponsorTermGroups.map((group, groupIndex) => (
              <Accordion
                key={group.categoryCode ?? group.categoryDescription}
                defaultExpanded={groupIndex === 0}
                disableGutters
                variant="outlined"
                sx={{ "&:before": { display: "none" } }}
              >
                <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
                  <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                    <Typography sx={{ fontWeight: 700 }}>
                      {group.categoryDescription}
                    </Typography>
                    <Chip
                      size="small"
                      variant="outlined"
                      label={group.terms.length}
                    />
                  </Stack>
                </AccordionSummary>
                <AccordionDetails>
                  <Stack spacing={1}>
                    {group.terms.map((term) => (
                      <Typography
                        key={term.awardSponsorTermId}
                        variant="body2"
                        sx={{ overflowWrap: "break-word" }}
                      >
                        {resolveAwardSponsorTermLabel(term)}
                      </Typography>
                    ))}
                  </Stack>
                </AccordionDetails>
              </Accordion>
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
              <ReportTermCard key={term.awardReportTermId} term={term} />
            ))}
          </Stack>
        )}
      </Box>
    </Stack>
  );
}

function ReportTermCard({ term }: { term: AwardReportTermV1 }) {
  const reportClassLabel = resolveAwardReportTermFieldLabel(
    term.reportClassCode,
    term.reportClassDescription,
  );
  const frequencyLabel = resolveAwardReportTermFieldLabel(
    term.frequencyCode,
    term.frequencyDescription,
  );
  const frequencyBaseLabel = resolveAwardReportTermFieldLabel(
    term.frequencyBaseCode,
    term.frequencyBaseDescription,
  );
  const distributionLabel = resolveAwardReportTermFieldLabel(
    term.ospDistributionCode,
    term.distributionDescription,
  );
  const advanceNotice = formatAdvanceNotice(
    term.advanceNumberOfDays,
    term.advanceNumberOfMonths,
  );

  const fields: { label: string; value: string }[] = [];
  if (term.reportCode) {
    fields.push({ label: "Report Code", value: term.reportCode });
  }
  if (reportClassLabel) {
    fields.push({ label: "Report Class", value: reportClassLabel });
  }
  if (frequencyLabel) {
    fields.push({ label: "Frequency", value: frequencyLabel });
  }
  if (frequencyBaseLabel) {
    fields.push({ label: "Frequency Base", value: frequencyBaseLabel });
  }
  if (distributionLabel) {
    fields.push({ label: "OSP Distribution", value: distributionLabel });
  }
  if (term.dueDate) {
    fields.push({ label: "Due Date", value: term.dueDate });
  }
  if (advanceNotice) {
    fields.push({ label: "Advance Notice", value: advanceNotice });
  }

  return (
    <Accordion variant="outlined" disableGutters>
      <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
        <Stack sx={{ minWidth: 0 }}>
          <Typography sx={{ fontWeight: 700 }}>
            {resolveAwardReportTermHeading(term)}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {term.recipientCount} recipient{term.recipientCount === 1 ? "" : "s"}
          </Typography>
        </Stack>
      </AccordionSummary>

      <AccordionDetails>
        <Box
          sx={{
            display: "grid",
            gridTemplateColumns: "minmax(160px, 220px) 1fr",
            rowGap: 1,
            columnGap: 2,
          }}
        >
          {fields.map((field) => (
            <Box
              key={field.label}
              sx={{ display: "contents" }}
            >
              <Typography
                variant="body2"
                color="text.secondary"
                sx={{ fontWeight: 600 }}
              >
                {field.label}
              </Typography>
              <Typography variant="body2">{field.value}</Typography>
            </Box>
          ))}
        </Box>

        <Typography
          variant="body2"
          color="text.secondary"
          sx={{ fontWeight: 600, mt: 2 }}
        >
          Recipients
        </Typography>
        {term.recipients.length === 0 ? (
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            No recipients recorded.
          </Typography>
        ) : (
          <Stack spacing={0.5} sx={{ mt: 0.5 }}>
            {term.recipients.map((recipient) => (
              <Typography
                key={recipient.awardReportTermRecipientId}
                variant="body2"
              >
                {resolveAwardReportTermRecipientLabel(recipient)}
                {recipient.contactId ? ` · Contact ${recipient.contactId}` : ""}
                {recipient.numberOfCopies
                  ? ` · ${recipient.numberOfCopies} copies`
                  : ""}
              </Typography>
            ))}
          </Stack>
        )}
      </AccordionDetails>
    </Accordion>
  );
}
