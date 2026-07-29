import AutoAwesomeOutlinedIcon from "@mui/icons-material/AutoAwesomeOutlined";
import HelpOutlineOutlinedIcon from "@mui/icons-material/HelpOutlineOutlined";
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Divider,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { alpha } from "@mui/material/styles";
import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";

import {
  ApiRequestError,
  askAwardQuestion,
} from "../../api/client";
import {
  canSubmitAwardQuestion,
  showQuestionDevelopmentMetadata,
  suggestedAwardQuestions,
} from "./awardQuestionPresentation.mjs";

interface AwardAiQuestionPanelProps {
  awardNumber: string;
}

function questionError(error: Error): string {
  if (error instanceof ApiRequestError) {
    if (error.status === 401) {
      return "Your session has expired. Sign in again to ask a question.";
    }
    if (error.status === 404) {
      return "This Award could not be found, or Award questions are unavailable.";
    }
    if (error.status === 400) {
      return "Enter a valid question of no more than 500 characters.";
    }
  }
  return "The question could not be answered. Try again later.";
}

export function AwardAiQuestionPanel({
  awardNumber,
}: AwardAiQuestionPanelProps) {
  const [question, setQuestion] = useState("");
  const questionMutation = useMutation({
    mutationFn: (value: string) =>
      askAwardQuestion(awardNumber, value),
    retry: false,
  });
  const result = questionMutation.data;
  const correlationId =
    result?.correlationId ||
    (questionMutation.error instanceof ApiRequestError
      ? questionMutation.error.correlationId
      : undefined);
  const orderedCitations = result
    ? [...result.citations].sort(
        (left, right) =>
          right.sequenceNumber - left.sequenceNumber ||
          Number(right.recordId) - Number(left.recordId),
      )
    : [];

  function submitQuestion(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalized = question.trim();
    if (canSubmitAwardQuestion(normalized, questionMutation.isPending)) {
      questionMutation.mutate(normalized);
    }
  }

  return (
    <Card
      component="section"
      aria-labelledby="award-question-heading"
      sx={{
        borderRadius: 4,
        boxShadow: "0 8px 28px rgba(0, 0, 0, 0.07)",
      }}
    >
      <CardContent
        sx={{
          p: { xs: 2.5, md: 3.5 },
          "&:last-child": { pb: { xs: 2.5, md: 3.5 } },
        }}
      >
        <Stack spacing={3}>
          <Box>
            <Stack
              direction="row"
              spacing={1}
              sx={{ alignItems: "center" }}
            >
              <HelpOutlineOutlinedIcon color="primary" aria-hidden="true" />
              <Typography id="award-question-heading" variant="h5">
                Ask a Question
              </Typography>
            </Stack>
            <Typography color="text.secondary" sx={{ mt: 0.75 }}>
              Ask about facts or changes in this archived Award.
            </Typography>
          </Box>

          <Stack
            direction="row"
            useFlexGap
            sx={{ flexWrap: "wrap", gap: 1 }}
            aria-label="Suggested questions"
          >
            {suggestedAwardQuestions.map((suggestion) => (
              <Chip
                key={suggestion}
                label={suggestion}
                variant="outlined"
                clickable
                onClick={() => setQuestion(suggestion)}
                disabled={questionMutation.isPending}
                sx={{ height: "auto", py: 0.5 }}
              />
            ))}
          </Stack>

          <Box component="form" onSubmit={submitQuestion}>
            <Stack
              direction={{ xs: "column", sm: "row" }}
              spacing={1.5}
              sx={{ alignItems: { sm: "flex-start" } }}
            >
              <TextField
                fullWidth
                label="Question about this Award"
                value={question}
                onChange={(event) => setQuestion(event.target.value)}
                slotProps={{ htmlInput: { maxLength: 500 } }}
                helperText={`${question.length}/500 characters`}
                disabled={questionMutation.isPending}
              />
              <Button
                type="submit"
                variant="contained"
                disabled={!canSubmitAwardQuestion(
                  question,
                  questionMutation.isPending,
                )}
                sx={{ minWidth: 112, py: 1.85 }}
              >
                {questionMutation.isPending ? "Asking…" : "Ask"}
              </Button>
            </Stack>
          </Box>

          {questionMutation.isPending && (
            <Stack
              direction="row"
              spacing={1.5}
              sx={{ alignItems: "center" }}
              role="status"
              aria-live="polite"
            >
              <CircularProgress size={22} />
              <Typography>Reviewing the archived Award records…</Typography>
            </Stack>
          )}

          {questionMutation.isError && (
            <Alert severity="error" aria-live="polite">
              {questionError(questionMutation.error)}
              {correlationId && (
                <Typography
                  component="span"
                  variant="body2"
                  sx={{ display: "block", mt: 0.75 }}
                >
                  Reference: {correlationId}
                </Typography>
              )}
            </Alert>
          )}

          {result && (
            <Stack spacing={2.5} aria-live="polite">
              <Card
                variant="outlined"
                sx={(theme) => ({
                  borderColor: alpha(theme.palette.primary.main, 0.3),
                  borderRadius: 3,
                  bgcolor: alpha(theme.palette.primary.main, 0.035),
                })}
              >
                <CardContent
                  sx={{ p: 2.5, "&:last-child": { pb: 2.5 } }}
                >
                  <Stack
                    direction="row"
                    spacing={1}
                    sx={{ alignItems: "center" }}
                  >
                    <AutoAwesomeOutlinedIcon
                      color="primary"
                      fontSize="small"
                      aria-hidden="true"
                    />
                    <Typography variant="overline" color="primary">
                      Answer
                    </Typography>
                  </Stack>
                  <Typography sx={{ mt: 1, lineHeight: 1.7 }}>
                    {result.answer}
                  </Typography>
                </CardContent>
              </Card>

              <Box
                component="details"
                sx={{
                  "& summary": {
                    cursor: "pointer",
                    fontWeight: 700,
                    py: 1,
                    "&:focus-visible": {
                      outline: "3px solid",
                      outlineColor: "primary.light",
                      outlineOffset: 2,
                    },
                  },
                }}
              >
                <Typography component="summary">
                  Sources ({orderedCitations.length})
                </Typography>
                <Stack
                  divider={<Divider flexItem />}
                  sx={{ mt: 1 }}
                  aria-label="Answer sources"
                >
                  {orderedCitations.map((citation) => (
                    <Stack
                      key={`${citation.recordId}-${citation.sequenceNumber}`}
                      direction={{ xs: "column", sm: "row" }}
                      spacing={0.5}
                      sx={{ py: 1, justifyContent: "space-between" }}
                    >
                      <Typography>
                        Sequence {citation.sequenceNumber}
                      </Typography>
                      <Typography color="text.secondary" variant="body2">
                        Archive record ID {citation.recordId}
                      </Typography>
                    </Stack>
                  ))}
                </Stack>
              </Box>

              {showQuestionDevelopmentMetadata(import.meta.env.DEV) && (
                <Typography color="text.secondary" variant="caption">
                  Provider: {result.provider} · Model: {result.model}
                </Typography>
              )}
            </Stack>
          )}
        </Stack>
      </CardContent>
    </Card>
  );
}
