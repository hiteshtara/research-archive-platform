import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  List,
  ListItem,
  ListItemText,
  Stack,
  Typography,
} from "@mui/material";
import { useMutation } from "@tanstack/react-query";

import {
  ApiRequestError,
  generateAwardAiSummary,
} from "../../api/client";
import {
  orderAwardTimeline,
  showDevelopmentMetadata,
  timelineLabel,
} from "./awardAiPresentation.mjs";

interface AwardAiSummaryPanelProps {
  awardNumber: string;
}

function displayValue(value: string | number | null): string {
  return value === null || value === "" ? "Not available" : String(value);
}

function displayAmount(value: number | null): string {
  return value === null
    ? "Not available"
    : new Intl.NumberFormat("en-US", {
        style: "currency",
        currency: "USD",
      }).format(value);
}

function errorMessage(error: Error): string {
  if (error instanceof ApiRequestError) {
    switch (error.status) {
      case 401:
        return "Your session has expired. Sign in again to generate a summary.";
      case 404:
        return "The AI summary endpoint is unavailable, or this Award could not be found.";
      case 503:
        return "The AI summary service is temporarily unavailable. Try again later.";
      default:
        return "The AI summary could not be generated. Try again later.";
    }
  }

  return "The AI summary service could not be reached. Check your connection and try again.";
}

export function AwardAiSummaryPanel({
  awardNumber,
}: AwardAiSummaryPanelProps) {
  const summaryMutation = useMutation({
    mutationFn: () => generateAwardAiSummary(awardNumber),
    retry: false,
  });

  const correlationId =
    summaryMutation.data?.correlationId ||
    (summaryMutation.error instanceof ApiRequestError
      ? summaryMutation.error.correlationId
      : undefined);
  const orderedTimeline = summaryMutation.data
    ? orderAwardTimeline(summaryMutation.data.timeline)
    : [];
  const earliestSequence =
    orderedTimeline.length === 0
      ? 0
      : Math.min(...orderedTimeline.map((record) => record.sequenceNumber));

  return (
    <Card component="section" aria-labelledby="award-ai-summary-heading">
      <CardContent>
        <Stack spacing={2}>
          <Box>
            <Typography id="award-ai-summary-heading" variant="h5">
              AI Award History Summary
            </Typography>
            <Typography color="text.secondary" sx={{ mt: 0.5 }}>
              Generate a read-only summary from archived Award history records.
            </Typography>
          </Box>

          <Box>
            <Button
              variant="contained"
              onClick={() => summaryMutation.mutate()}
              disabled={summaryMutation.isPending}
            >
              {summaryMutation.isPending
                ? "Generating summary…"
                : summaryMutation.isError
                  ? "Retry AI Summary"
                  : "Generate AI Summary"}
            </Button>
          </Box>

          {summaryMutation.isPending && (
            <Stack
              direction="row"
              spacing={1.5}
              sx={{ alignItems: "center" }}
              role="status"
              aria-live="polite"
            >
              <CircularProgress size={22} />
              <Typography>Generating the Award history summary…</Typography>
            </Stack>
          )}

          {summaryMutation.error && (
            <Alert severity="error" role="alert">
              {errorMessage(summaryMutation.error)}
              {correlationId && (
                <Typography component="div" variant="caption" sx={{ mt: 0.5 }}>
                  Support reference: {correlationId}
                </Typography>
              )}
            </Alert>
          )}

          {summaryMutation.data && (
            <Stack spacing={2}>
              <Alert severity="warning">
                AI-generated summary. Verify important details against the
                archived records and citations below.
              </Alert>

              <Box>
                <Typography variant="h6">Overview</Typography>
                <Typography sx={{ mt: 1, whiteSpace: "pre-wrap" }}>
                  {summaryMutation.data.overview}
                </Typography>
              </Box>

              <Box>
                <Typography variant="h6">Current Record</Typography>
                <List dense aria-label="Current Award record">
                  <ListItem disableGutters>
                    <ListItemText
                      primary={`Award ${summaryMutation.data.currentRecord.awardNumber}, sequence ${summaryMutation.data.currentRecord.sequenceNumber}`}
                      secondary={`Archive record ID: ${summaryMutation.data.currentRecord.awardId}`}
                    />
                  </ListItem>
                  <ListItem disableGutters>
                    <ListItemText
                      primary={displayValue(
                        summaryMutation.data.currentRecord.title,
                      )}
                      secondary="Title"
                    />
                  </ListItem>
                  <ListItem disableGutters>
                    <ListItemText
                      primary={displayValue(
                        summaryMutation.data.currentRecord.status,
                      )}
                      secondary="Status"
                    />
                  </ListItem>
                  <ListItem disableGutters>
                    <ListItemText
                      primary={displayValue(
                        summaryMutation.data.currentRecord.sponsor,
                      )}
                      secondary="Sponsor"
                    />
                  </ListItem>
                  <ListItem disableGutters>
                    <ListItemText
                      primary={displayValue(
                        summaryMutation.data.currentRecord.leadUnit,
                      )}
                      secondary="Lead unit"
                    />
                  </ListItem>
                  <ListItem disableGutters>
                    <ListItemText
                      primary={
                        summaryMutation.data.currentRecord
                          .principalInvestigators.length > 0
                          ? summaryMutation.data.currentRecord.principalInvestigators.join(
                              ", ",
                            )
                          : "Not available"
                      }
                      secondary="Principal investigator(s)"
                    />
                  </ListItem>
                  <ListItem disableGutters>
                    <ListItemText
                      primary={`${displayValue(summaryMutation.data.currentRecord.beginDate)} – ${displayValue(summaryMutation.data.currentRecord.closeoutDate)}`}
                      secondary="Begin date – closeout date"
                    />
                  </ListItem>
                  <ListItem disableGutters>
                    <ListItemText
                      primary={`${displayAmount(summaryMutation.data.currentRecord.anticipatedTotalAmount)} anticipated; ${displayAmount(summaryMutation.data.currentRecord.obligatedTotalAmount)} obligated`}
                      secondary="Current amounts"
                    />
                  </ListItem>
                </List>
              </Box>

              <Box>
                <Typography variant="h6">Historical Timeline</Typography>
                <List dense aria-label="Award history timeline">
                  {orderedTimeline.map((record) => (
                    <ListItem
                      key={`${record.awardId}-${record.sequenceNumber}`}
                      disableGutters
                      sx={{
                        alignItems: "flex-start",
                        borderBottom: 1,
                        borderColor: "divider",
                        py: 1,
                      }}
                    >
                      <ListItemText
                        primary={
                          <Stack spacing={0.25}>
                            <Typography
                              component="span"
                              sx={{ fontWeight: 700 }}
                              variant="body2"
                            >
                              {timelineLabel(
                                record.sequenceNumber,
                                summaryMutation.data.currentRecord
                                  .sequenceNumber,
                                earliestSequence,
                              )}
                            </Typography>
                            <Typography component="span" variant="body1">
                              Sequence {record.sequenceNumber}
                            </Typography>
                            <Typography component="span" variant="body2">
                              Status: {displayValue(record.status)}
                            </Typography>
                          </Stack>
                        }
                        secondary={`Record ${record.awardId}; ${displayValue(record.sponsor)}; ${displayValue(record.beginDate)} – ${displayValue(record.closeoutDate)}`}
                      />
                    </ListItem>
                  ))}
                </List>
              </Box>

              <Box>
                <Typography variant="h6">Notable Changes</Typography>
                {summaryMutation.data.notableChanges.length === 0 ? (
                  <Typography color="text.secondary" sx={{ mt: 1 }}>
                    No notable changes were identified.
                  </Typography>
                ) : (
                  <List dense aria-label="Notable Award changes">
                    {summaryMutation.data.notableChanges.map(
                      (change, index) => (
                        <ListItem key={`${index}-${change}`} disableGutters>
                          <ListItemText primary={change} />
                        </ListItem>
                      ),
                    )}
                  </List>
                )}
              </Box>

              <Box>
                <Typography variant="h6">Archive Assessment</Typography>
                <Typography sx={{ mt: 1, whiteSpace: "pre-wrap" }}>
                  {summaryMutation.data.archiveAssessment}
                </Typography>
              </Box>

              <Box>
                <Typography variant="h6">Sources</Typography>
                {summaryMutation.data.citations.length === 0 ? (
                  <Typography color="text.secondary" sx={{ mt: 1 }}>
                    No supporting archive records were cited.
                  </Typography>
                ) : (
                  <List dense aria-label="Award summary citations">
                    {summaryMutation.data.citations.map((citation) => (
                      <ListItem
                        key={`${citation.recordId}-${citation.sequenceNumber}`}
                        disableGutters
                      >
                        <ListItemText
                          primary={`Award ${citation.awardNumber}, sequence ${citation.sequenceNumber}`}
                          secondary={`Archive record ID: ${citation.recordId}`}
                        />
                      </ListItem>
                    ))}
                  </List>
                )}
              </Box>

              <Typography color="text.secondary" variant="caption">
                Support reference: {summaryMutation.data.correlationId}
              </Typography>

              {showDevelopmentMetadata(import.meta.env.DEV) && (
                <Box
                  component="details"
                  sx={{
                    "& summary": {
                      cursor: "pointer",
                      width: "fit-content",
                    },
                  }}
                >
                  <Typography component="summary" variant="body2">
                    Development details
                  </Typography>
                  <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
                    <Chip
                      size="small"
                      label={`Provider: ${summaryMutation.data.provider}`}
                    />
                    <Chip
                      size="small"
                      label={`Model: ${summaryMutation.data.model}`}
                    />
                  </Stack>
                </Box>
              )}
            </Stack>
          )}
        </Stack>
      </CardContent>
    </Card>
  );
}
