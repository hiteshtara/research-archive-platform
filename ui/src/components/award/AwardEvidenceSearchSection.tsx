import SearchOutlinedIcon from "@mui/icons-material/SearchOutlined";
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

import { ApiRequestError, searchAwardEvidence } from "../../api/client";
import {
  EVIDENCE_TYPES,
  canSubmitEvidenceSearch,
  evidenceScorePercentLabel,
  evidenceSearchErrorMessage,
  evidenceTypeLabel,
  toggleEvidenceType,
} from "../../features/award/awardEvidenceSearchPresentation.mjs";
import type { AwardEvidenceSearchResult } from "../../types/api";
import { EmptyState } from "../common/EmptyState";

interface AwardEvidenceSearchSectionProps {
  awardNumber: string;
  onNavigateToSection?: (targetSection: string) => void;
}

export function AwardEvidenceSearchSection({
  awardNumber,
  onNavigateToSection,
}: AwardEvidenceSearchSectionProps) {
  const [query, setQuery] = useState("");
  const [selectedTypes, setSelectedTypes] = useState<string[]>([]);

  const searchMutation = useMutation({
    mutationFn: (value: string) =>
      searchAwardEvidence(awardNumber, value, selectedTypes),
    retry: false,
  });

  const result = searchMutation.data;
  const correlationId =
    result?.correlationId ||
    (searchMutation.error instanceof ApiRequestError
      ? searchMutation.error.correlationId
      : undefined);

  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalized = query.trim();
    if (canSubmitEvidenceSearch(normalized, searchMutation.isPending)) {
      searchMutation.mutate(normalized);
    }
  }

  function retry() {
    const normalized = query.trim();
    if (canSubmitEvidenceSearch(normalized, searchMutation.isPending)) {
      searchMutation.mutate(normalized);
    }
  }

  return (
    <Card
      component="section"
      aria-labelledby="award-evidence-search-heading"
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
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <SearchOutlinedIcon color="primary" aria-hidden="true" />
              <Typography id="award-evidence-search-heading" variant="h5">
                Evidence Search
              </Typography>
            </Stack>
            <Typography color="text.secondary" sx={{ mt: 0.75 }}>
              Search this Award&rsquo;s structured evidence directly - every
              result is a real archived record, not a generated answer.
              Attachment contents are not searched here; see the
              Attachments tab for archived files.
            </Typography>
          </Box>

          <Stack
            direction="row"
            useFlexGap
            sx={{ flexWrap: "wrap", gap: 1 }}
            aria-label="Evidence type filters"
          >
            {EVIDENCE_TYPES.map((type) => (
              <Chip
                key={type}
                label={evidenceTypeLabel(type)}
                variant={selectedTypes.includes(type) ? "filled" : "outlined"}
                color={selectedTypes.includes(type) ? "primary" : "default"}
                clickable
                onClick={() =>
                  setSelectedTypes((current) =>
                    toggleEvidenceType(current, type),
                  )
                }
                disabled={searchMutation.isPending}
                sx={{ height: "auto", py: 0.5 }}
              />
            ))}
          </Stack>

          <Box component="form" onSubmit={submitSearch}>
            <Stack
              direction={{ xs: "column", sm: "row" }}
              spacing={1.5}
              sx={{ alignItems: { sm: "flex-start" } }}
            >
              <TextField
                fullWidth
                label="Question or evidence search"
                placeholder="Which proposal is connected to this Award?"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                slotProps={{ htmlInput: { maxLength: 500 } }}
                helperText={`${query.length}/500 characters`}
                disabled={searchMutation.isPending}
              />
              <Button
                type="submit"
                variant="contained"
                disabled={
                  !canSubmitEvidenceSearch(query, searchMutation.isPending)
                }
                sx={{ minWidth: 112, py: 1.85 }}
              >
                {searchMutation.isPending ? "Searching…" : "Search"}
              </Button>
            </Stack>
          </Box>

          {searchMutation.isPending && (
            <Stack
              direction="row"
              spacing={1.5}
              sx={{ alignItems: "center" }}
              role="status"
              aria-live="polite"
            >
              <CircularProgress size={22} />
              <Typography>Searching archived evidence…</Typography>
            </Stack>
          )}

          {searchMutation.isError && (
            <Stack spacing={1.5}>
              <Alert severity="error" aria-live="polite">
                {evidenceSearchErrorMessage(
                  searchMutation.error instanceof ApiRequestError
                    ? searchMutation.error.status
                    : undefined,
                )}
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
              <Button
                variant="outlined"
                size="small"
                onClick={retry}
                sx={{ alignSelf: "flex-start" }}
              >
                Retry
              </Button>
            </Stack>
          )}

          {result && result.insufficientEvidence && (
            <EmptyState
              variant="alert"
              message="No indexed evidence matched this search. Evidence indexing may not yet be complete for this Award, or try a different question or filter."
            />
          )}

          {result && !result.insufficientEvidence && (
            <Stack spacing={2} aria-live="polite">
              <Typography variant="overline" color="primary">
                {result.results.length}{" "}
                {result.results.length === 1 ? "result" : "results"}
              </Typography>
              <Stack divider={<Divider flexItem />} spacing={2}>
                {result.results.map((item) => (
                  <EvidenceResultCard
                    key={`${item.documentType}-${item.sourcePrimaryKey}`}
                    result={item}
                    onNavigate={onNavigateToSection}
                  />
                ))}
              </Stack>
            </Stack>
          )}
        </Stack>
      </CardContent>
    </Card>
  );
}

function EvidenceResultCard({
  result,
  onNavigate,
}: {
  result: AwardEvidenceSearchResult;
  onNavigate?: (targetSection: string) => void;
}) {
  return (
    <Card
      variant="outlined"
      sx={(theme) => ({
        borderColor: alpha(theme.palette.primary.main, 0.3),
        borderRadius: 3,
        bgcolor: alpha(theme.palette.primary.main, 0.035),
      })}
    >
      <CardContent sx={{ p: 2.5, "&:last-child": { pb: 2.5 } }}>
        <Stack spacing={1}>
          <Stack
            direction={{ xs: "column", sm: "row" }}
            spacing={1}
            sx={{
              alignItems: { sm: "center" },
              justifyContent: "space-between",
            }}
          >
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <Chip
                label={evidenceTypeLabel(result.documentType)}
                size="small"
                color="primary"
                variant="outlined"
              />
              <Typography color="text.secondary" variant="body2">
                Award {result.awardNumber}
              </Typography>
            </Stack>
            <Chip
              label={evidenceScorePercentLabel(result.score)}
              size="small"
              variant="outlined"
            />
          </Stack>

          <Typography sx={{ lineHeight: 1.7 }}>{result.excerpt}</Typography>

          <Stack
            direction={{ xs: "column", sm: "row" }}
            spacing={0.5}
            sx={{
              alignItems: { sm: "center" },
              justifyContent: "space-between",
            }}
          >
            <Typography color="text.secondary" variant="caption">
              Source: {result.sourceTable} #{result.sourcePrimaryKey}
            </Typography>
            {onNavigate && (
              <Button
                size="small"
                onClick={() => onNavigate(result.targetSection)}
              >
                View in Award record
              </Button>
            )}
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  );
}
