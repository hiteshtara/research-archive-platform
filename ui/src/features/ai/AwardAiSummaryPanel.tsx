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

interface AwardAiSummaryPanelProps {
  awardNumber: string;
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

              <Typography sx={{ whiteSpace: "pre-wrap" }}>
                {summaryMutation.data.summary}
              </Typography>

              <Box>
                <Typography variant="h6">Citations</Typography>
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
                  Technical details
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
            </Stack>
          )}
        </Stack>
      </CardContent>
    </Card>
  );
}
