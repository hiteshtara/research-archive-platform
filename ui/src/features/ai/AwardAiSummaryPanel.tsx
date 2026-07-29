import AccessTimeOutlinedIcon from "@mui/icons-material/AccessTimeOutlined";
import AutoAwesomeOutlinedIcon from "@mui/icons-material/AutoAwesomeOutlined";
import CheckCircleOutlineIcon from "@mui/icons-material/CheckCircleOutlineOutlined";
import InfoOutlinedIcon from "@mui/icons-material/InfoOutlined";
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Stack,
  Typography,
} from "@mui/material";
import { alpha } from "@mui/material/styles";
import { useMutation } from "@tanstack/react-query";
import { useState } from "react";

import {
  ApiRequestError,
  generateAwardAiSummary,
} from "../../api/client";
import {
  currentAwardFacts,
  estimatedReadingSeconds,
  orderAwardTimeline,
  sequenceLabelFromChange,
  showDevelopmentMetadata,
  timelineLabel,
  visibleAwardTimeline,
} from "./awardAiPresentation.mjs";

interface AwardAiSummaryPanelProps {
  awardNumber: string;
}

interface FactCardProps {
  label: string;
  value: string;
}

function FactCard({ label, value }: FactCardProps) {
  return (
    <Card
      variant="outlined"
      sx={{
        borderColor: "divider",
        borderRadius: 3,
        boxShadow: "0 4px 18px rgba(0, 0, 0, 0.04)",
        height: "100%",
      }}
    >
      <CardContent sx={{ p: 2.25, "&:last-child": { pb: 2.25 } }}>
        <Typography
          color="text.secondary"
          component="dt"
          variant="caption"
          sx={{
            fontWeight: 700,
            letterSpacing: "0.04em",
            textTransform: "uppercase",
          }}
        >
          {label}
        </Typography>
        <Typography
          component="dd"
          variant="body1"
          sx={{
            fontWeight: 650,
            m: 0,
            mt: 0.75,
            overflowWrap: "anywhere",
          }}
        >
          {value}
        </Typography>
      </CardContent>
    </Card>
  );
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
  const [showAllSequences, setShowAllSequences] = useState(false);
  const summaryMutation = useMutation({
    mutationFn: () => generateAwardAiSummary(awardNumber),
    retry: false,
  });

  const summary = summaryMutation.data;
  const correlationId =
    summary?.correlationId ||
    (summaryMutation.error instanceof ApiRequestError
      ? summaryMutation.error.correlationId
      : undefined);
  const orderedTimeline = summary
    ? orderAwardTimeline(summary.timeline)
    : [];
  const earliestSequence =
    orderedTimeline.length === 0
      ? 0
      : Math.min(...orderedTimeline.map((record) => record.sequenceNumber));
  const displayedTimeline = visibleAwardTimeline(
    orderedTimeline,
    showAllSequences,
  );
  const timelineIsCollapsible =
    displayedTimeline.length < orderedTimeline.length || showAllSequences;
  const orderedCitations = summary
    ? [...summary.citations].sort(
        (left, right) =>
          right.sequenceNumber - left.sequenceNumber ||
          Number(right.recordId) - Number(left.recordId),
      )
    : [];
  const currentFacts = summary
    ? currentAwardFacts(summary.currentRecord, displayAmount)
    : [];
  const readingSeconds = summary
    ? estimatedReadingSeconds([
        summary.overview,
        ...summary.notableChanges,
        summary.archiveAssessment,
      ])
    : 10;

  function generateSummary() {
    setShowAllSequences(false);
    summaryMutation.mutate();
  }

  return (
    <Card
      component="section"
      aria-labelledby="award-ai-summary-heading"
      sx={{
        borderRadius: 4,
        boxShadow: "0 10px 32px rgba(0, 0, 0, 0.08)",
        overflow: "hidden",
      }}
    >
      <CardContent sx={{ p: { xs: 2.5, md: 4 }, "&:last-child": { pb: 4 } }}>
        <Stack spacing={{ xs: 3, md: 4 }}>
          <Stack
            direction={{ xs: "column", sm: "row" }}
            spacing={2}
            sx={{
              alignItems: { xs: "flex-start", sm: "center" },
              justifyContent: "space-between",
            }}
          >
            <Box>
              <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <AutoAwesomeOutlinedIcon color="primary" aria-hidden="true" />
                <Typography id="award-ai-summary-heading" variant="h4">
                  Award Summary
                </Typography>
              </Stack>
              <Typography color="text.secondary" sx={{ mt: 0.75 }}>
                AI-generated overview of archived Award history.
              </Typography>
            </Box>

            <Button
              variant="contained"
              onClick={generateSummary}
              disabled={summaryMutation.isPending}
              sx={{ borderRadius: 999, px: 2.5 }}
            >
              {summaryMutation.isPending
                ? "Generating…"
                : summaryMutation.isError
                  ? "Try again"
                  : summary
                    ? "Regenerate summary"
                    : "Generate summary"}
            </Button>
          </Stack>

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

          {summary && (
            <Stack spacing={{ xs: 3, md: 4 }}>
              <Stack
                direction={{ xs: "column", sm: "row" }}
                spacing={1.5}
                sx={{
                  alignItems: { xs: "flex-start", sm: "center" },
                  justifyContent: "space-between",
                }}
              >
                <Stack
                  direction="row"
                  spacing={0.75}
                  sx={{ alignItems: "center" }}
                >
                  <AccessTimeOutlinedIcon
                    color="action"
                    fontSize="small"
                    aria-hidden="true"
                  />
                  <Typography color="text.secondary" variant="body2">
                    Estimated reading time: about {readingSeconds} seconds
                  </Typography>
                </Stack>
                <Typography color="text.secondary" variant="caption">
                  Read-only AI narrative — verify against archive sources.
                </Typography>
              </Stack>

              <Card
                variant="outlined"
                sx={{
                  bgcolor: (theme) =>
                    alpha(theme.palette.primary.main, 0.045),
                  borderColor: (theme) =>
                    alpha(theme.palette.primary.main, 0.22),
                  borderRadius: 3.5,
                  boxShadow: "0 6px 24px rgba(0, 0, 0, 0.05)",
                }}
              >
                <CardContent sx={{ p: { xs: 2.5, md: 3 } }}>
                  <Typography
                    color="primary.main"
                    variant="overline"
                    sx={{ fontWeight: 800, letterSpacing: "0.08em" }}
                  >
                    Executive Summary
                  </Typography>
                  <Typography
                    sx={{
                      fontSize: { xs: "1rem", md: "1.125rem" },
                      lineHeight: 1.75,
                      mt: 1,
                      whiteSpace: "pre-wrap",
                    }}
                  >
                    {summary.overview}
                  </Typography>
                </CardContent>
              </Card>

              <Box component="section" aria-labelledby="current-record-heading">
                <Typography id="current-record-heading" variant="h5">
                  Current Record
                </Typography>
                <Typography color="text.secondary" sx={{ mt: 0.5 }}>
                  Authoritative values from the current archived sequence.
                </Typography>
                <Box
                  component="dl"
                  sx={{
                    display: "grid",
                    gap: 2,
                    gridTemplateColumns: {
                      xs: "1fr",
                      sm: "repeat(2, minmax(0, 1fr))",
                      lg: "repeat(3, minmax(0, 1fr))",
                    },
                    m: 0,
                    mt: 2,
                  }}
                >
                  {currentFacts.map((fact) => (
                    <FactCard
                      key={fact.label}
                      label={fact.label}
                      value={fact.value}
                    />
                  ))}
                </Box>
              </Box>

              <Box component="section" aria-labelledby="timeline-heading">
                <Typography id="timeline-heading" variant="h5">
                  Historical Timeline
                </Typography>
                <Typography color="text.secondary" sx={{ mt: 0.5 }}>
                  Award records shown from the current sequence to the original.
                </Typography>
                <Box
                  component="ol"
                  aria-label="Award history timeline"
                  sx={{ listStyle: "none", m: 0, mt: 2, p: 0 }}
                >
                  {displayedTimeline.map((record, index) => {
                    const label = timelineLabel(
                      record.sequenceNumber,
                      summary.currentRecord.sequenceNumber,
                      earliestSequence,
                    );
                    return (
                      <Box
                        component="li"
                        key={`${record.awardId}-${record.sequenceNumber}`}
                        sx={{
                          display: "grid",
                          gridTemplateColumns: "28px minmax(0, 1fr)",
                          minHeight:
                            index < displayedTimeline.length - 1 ? 84 : 64,
                        }}
                      >
                        <Box
                          aria-hidden="true"
                          sx={{ position: "relative", pt: 0.5 }}
                        >
                          <Box
                            sx={{
                              bgcolor:
                                label === "Current"
                                  ? "primary.main"
                                  : "background.paper",
                              border: "3px solid",
                              borderColor:
                                label === "Current"
                                  ? "primary.main"
                                  : "text.secondary",
                              borderRadius: "50%",
                              height: 14,
                              width: 14,
                            }}
                          />
                          {index < displayedTimeline.length - 1 && (
                            <Box
                              sx={{
                                borderLeft: "2px solid",
                                borderColor: "divider",
                                bottom: 0,
                                left: 6,
                                position: "absolute",
                                top: 18,
                              }}
                            />
                          )}
                        </Box>
                        <Box sx={{ pb: 2 }}>
                          <Typography
                            color={
                              label === "Current"
                                ? "primary.main"
                                : "text.secondary"
                            }
                            variant="caption"
                            sx={{
                              fontWeight: 800,
                              letterSpacing: "0.05em",
                              textTransform: "uppercase",
                            }}
                          >
                            {label}
                          </Typography>
                          <Typography variant="h6">
                            Sequence {record.sequenceNumber}
                          </Typography>
                          <Typography sx={{ mt: 0.25 }}>
                            <Chip
                              label={displayValue(record.status)}
                              size="small"
                              variant={
                                label === "Current" ? "filled" : "outlined"
                              }
                              color={
                                label === "Current" ? "primary" : "default"
                              }
                            />
                          </Typography>
                          {(index === 0 ||
                            record.sponsor !==
                              displayedTimeline[index - 1]?.sponsor) &&
                            record.sponsor && (
                              <Typography
                                color="text.secondary"
                                variant="body2"
                                sx={{ mt: 0.75 }}
                              >
                                Sponsor: {record.sponsor}
                              </Typography>
                            )}
                          {(record.beginDate || record.closeoutDate) && (
                            <Typography color="text.secondary" variant="body2">
                              Dates:{" "}
                              {record.beginDate && record.closeoutDate
                                ? `${record.beginDate} – ${record.closeoutDate}`
                                : record.beginDate
                                  ? `begins ${record.beginDate}`
                                  : `closeout ${record.closeoutDate}`}
                            </Typography>
                          )}
                          <Typography color="text.secondary" variant="caption">
                            Award record ID: {record.awardId}
                          </Typography>
                        </Box>
                      </Box>
                    );
                  })}
                </Box>
                {timelineIsCollapsible && (
                  <Button
                    aria-expanded={showAllSequences}
                    onClick={() =>
                      setShowAllSequences((currentlyShown) => !currentlyShown)
                    }
                    size="small"
                    variant="text"
                    sx={{ mt: 1 }}
                  >
                    {showAllSequences ? "Show fewer" : "Show all sequences"}
                  </Button>
                )}
              </Box>

              <Box component="section" aria-labelledby="major-changes-heading">
                <Typography id="major-changes-heading" variant="h5">
                  Key Changes
                </Typography>
                <Box
                  sx={{
                    display: "grid",
                    gap: 2,
                    gridTemplateColumns: {
                      xs: "1fr",
                      md: "repeat(2, minmax(0, 1fr))",
                    },
                    mt: 2,
                  }}
                >
                  {summary.notableChanges.map((change, index) => (
                    <Card
                      key={`${index}-${change}`}
                      variant="outlined"
                      sx={{
                        borderColor: (theme) =>
                          alpha(theme.palette.success.main, 0.35),
                        borderRadius: 3,
                        boxShadow: "0 5px 20px rgba(0, 0, 0, 0.04)",
                      }}
                    >
                      <CardContent sx={{ p: 2.5 }}>
                        <Stack
                          direction="row"
                          spacing={1.25}
                          sx={{ alignItems: "flex-start" }}
                        >
                          <CheckCircleOutlineIcon
                            color="success"
                            aria-label="Major change"
                          />
                          <Box>
                            <Typography
                              color="success.dark"
                              variant="caption"
                              sx={{
                                fontWeight: 800,
                                letterSpacing: "0.05em",
                                textTransform: "uppercase",
                              }}
                            >
                              Key Change
                            </Typography>
                            <Typography sx={{ fontWeight: 700, mt: 0.5 }}>
                              {sequenceLabelFromChange(change) ??
                                "Award history"}
                            </Typography>
                            <Typography sx={{ lineHeight: 1.65, mt: 1 }}>
                              {change}
                            </Typography>
                          </Box>
                        </Stack>
                      </CardContent>
                    </Card>
                  ))}
                </Box>
              </Box>

              <Card
                component="section"
                aria-labelledby="archive-assessment-heading"
                variant="outlined"
                sx={{
                  bgcolor: (theme) => alpha(theme.palette.info.main, 0.055),
                  borderColor: (theme) =>
                    alpha(theme.palette.info.main, 0.3),
                  borderRadius: 3.5,
                }}
              >
                <CardContent sx={{ p: { xs: 2.5, md: 3 } }}>
                  <Stack
                    direction="row"
                    spacing={1.5}
                    sx={{ alignItems: "flex-start" }}
                  >
                    <InfoOutlinedIcon color="info" aria-hidden="true" />
                    <Box>
                      <Typography
                        id="archive-assessment-heading"
                        variant="h5"
                      >
                        Archive Assessment
                      </Typography>
                      <Typography
                        sx={{ lineHeight: 1.75, mt: 1, whiteSpace: "pre-wrap" }}
                      >
                        {summary.archiveAssessment}
                      </Typography>
                    </Box>
                  </Stack>
                </CardContent>
              </Card>

              <Typography color="text.secondary" variant="caption">
                Support reference: {summary.correlationId}
              </Typography>

              {showDevelopmentMetadata(import.meta.env.DEV) && (
                <Box component="details">
                  <Typography component="summary" variant="body2">
                    Development details
                  </Typography>
                  <Stack
                    direction={{ xs: "column", sm: "row" }}
                    spacing={1}
                    sx={{ alignItems: "flex-start", mt: 1 }}
                  >
                    <Chip size="small" label={`Provider: ${summary.provider}`} />
                    <Chip size="small" label={`Model: ${summary.model}`} />
                  </Stack>
                </Box>
              )}

              <Card
                variant="outlined"
                sx={{ borderRadius: 3, overflow: "hidden" }}
              >
                <Box component="details">
                  <Box
                    component="summary"
                    sx={{
                      cursor: "pointer",
                      fontWeight: 750,
                      px: 2.5,
                      py: 2,
                      "&:focus-visible": {
                        outline: "3px solid",
                        outlineColor: "primary.main",
                        outlineOffset: -3,
                      },
                    }}
                  >
                    Sources ({orderedCitations.length})
                  </Box>
                  <Stack
                    component="ul"
                    spacing={0}
                    aria-label="Award summary sources"
                    sx={{ listStyle: "none", m: 0, p: 0 }}
                  >
                    {orderedCitations.length > 0 && (
                      <Box
                        component="li"
                        sx={{
                          borderTop: 1,
                          borderColor: "divider",
                          px: 2.5,
                          py: 1.25,
                        }}
                      >
                        <Typography color="text.secondary" variant="body2">
                          Award {orderedCitations[0].awardNumber}
                        </Typography>
                      </Box>
                    )}
                    {orderedCitations.map((citation) => (
                      <Box
                        component="li"
                        key={`${citation.recordId}-${citation.sequenceNumber}`}
                        sx={{
                          borderTop: 1,
                          borderColor: "divider",
                          px: 2.5,
                          py: 1.75,
                        }}
                      >
                        <Typography sx={{ fontWeight: 700 }}>
                          Sequence {citation.sequenceNumber}
                        </Typography>
                        <Typography color="text.secondary" variant="body2">
                          Archive Record ID: {citation.recordId}
                        </Typography>
                      </Box>
                    ))}
                  </Stack>
                </Box>
              </Card>
            </Stack>
          )}
        </Stack>
      </CardContent>
    </Card>
  );
}
