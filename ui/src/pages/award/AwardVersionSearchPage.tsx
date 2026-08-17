import { SearchOutlined } from "@mui/icons-material";
import {
  Box,
  Card,
  CardContent,
  Chip,
  Link,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Link as RouterLink, useNavigate, useSearchParams } from "react-router-dom";

import { searchAwardVersionsV1 } from "../../api/client";
import { EmptyState } from "../../components/common/EmptyState";
import { ErrorState } from "../../components/common/ErrorState";
import { LoadingState } from "../../components/common/LoadingState";
import { PaginationFooter } from "../../components/common/PaginationFooter";
import { StatusPill } from "../../components/common/StatusPill";
import {
  describeVersionSearchResults,
  isValidAwardIdInput,
  versionCurrentLabel,
  versionDetailPath,
} from "../../features/award/awardVersionSearchPresentation.mjs";
import { useDebouncedCallback } from "../../hooks/useDebouncedCallback";

const PAGE_SIZE = 25;
const SEARCH_DEBOUNCE_MS = 350;

// Keeps a text field feeling instantly responsive to typing (local
// state, updated synchronously) while the URL/query-param update it
// eventually commits - and therefore the network request it triggers,
// since q/awardNumber/documentNumber/awardId all feed the search
// queryKey - is debounced to fire only once typing pauses. Stays in
// sync with external URL changes (e.g. browser back/forward) via the
// effect below.
function useDebouncedUrlParam(
  urlValue: string,
  commit: (value: string) => void,
): [string, (value: string) => void] {
  const [draft, setDraft] = useState(urlValue);
  const debouncedCommit = useDebouncedCallback(commit, SEARCH_DEBOUNCE_MS);

  useEffect(() => {
    setDraft(urlValue);
    // Only external (e.g. back/forward) changes to urlValue should
    // resync draft - not every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlValue]);

  function handleChange(value: string) {
    setDraft(value);
    debouncedCommit(value);
  }

  return [draft, handleChange];
}

// Historical Award Records explorer: one result per award_id (a
// specific version), never scoped to the current version - the
// version-level counterpart to AwardSearchPage. Every filter (q,
// awardNumber, documentNumber, awardId, versionFilter, sort, page)
// lives in the URL's own search params rather than component state, so
// browser back/forward naturally restores the exact search that was
// active, not just the page shell.
export function AwardVersionSearchPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const q = searchParams.get("q") ?? "";
  const awardNumber = searchParams.get("awardNumber") ?? "";
  const documentNumber = searchParams.get("documentNumber") ?? "";
  const awardId = searchParams.get("awardId") ?? "";
  const rawVersionFilter = searchParams.get("versionFilter");
  const versionFilter: "all" | "current" | "historical" =
    rawVersionFilter === "current" || rawVersionFilter === "historical"
      ? rawVersionFilter
      : "all";
  const rawSort = searchParams.get("sort");
  const sort: "sequence" | "date" = rawSort === "date" ? "date" : "sequence";
  const page = Number(searchParams.get("page") ?? "0") || 0;

  // Award ID is an exact numeric identifier, never a partial/substring
  // search - validated client-side so an obviously bad value never
  // reaches the API at all (the API validates independently too, as
  // defense in depth for direct callers - see AwardArchiveService).
  const awardIdIsValid = isValidAwardIdInput(awardId);
  const awardIdError = awardId.trim().length > 0 && !awardIdIsValid;

  const hasSearched =
    q.trim().length > 0 ||
    awardNumber.trim().length > 0 ||
    documentNumber.trim().length > 0 ||
    (awardId.trim().length > 0 && awardIdIsValid);

  const searchQuery = useQuery({
    queryKey: [
      "award-version-search-v1",
      q,
      awardNumber,
      documentNumber,
      awardId,
      versionFilter,
      sort,
      page,
    ],
    queryFn: ({ signal }) =>
      searchAwardVersionsV1(
        {
          q,
          awardNumber,
          documentNumber,
          awardId: awardIdIsValid ? awardId.trim() : "",
          versionFilter,
          sort,
          page,
          size: PAGE_SIZE,
        },
        signal,
      ),
    enabled: hasSearched && !awardIdError,
  });

  function updateParam(name: string, value: string) {
    const next = new URLSearchParams(searchParams);
    if (value) {
      next.set(name, value);
    } else {
      next.delete(name);
    }
    next.delete("page");
    setSearchParams(next);
  }

  // Debounced drafts for display/typing only - q/awardNumber/
  // documentNumber/awardId above (URL-sourced) remain the values that
  // actually drive the search query, unchanged until typing pauses.
  const [qDraft, setQDraft] = useDebouncedUrlParam(q, (value) =>
    updateParam("q", value),
  );
  const [awardNumberDraft, setAwardNumberDraft] = useDebouncedUrlParam(
    awardNumber,
    (value) => updateParam("awardNumber", value),
  );
  const [documentNumberDraft, setDocumentNumberDraft] = useDebouncedUrlParam(
    documentNumber,
    (value) => updateParam("documentNumber", value),
  );
  const [awardIdDraft, setAwardIdDraft] = useDebouncedUrlParam(
    awardId,
    (value) => updateParam("awardId", value),
  );

  function setPage(nextPage: number) {
    const next = new URLSearchParams(searchParams);
    next.set("page", String(nextPage));
    setSearchParams(next);
  }

  const { totalElements, totalPages, content } =
    describeVersionSearchResults(searchQuery.data);

  return (
    <Stack spacing={4} sx={{ alignItems: "center" }}>
      <Box sx={{ maxWidth: 760, width: "100%", textAlign: "center", mt: 4 }}>
        <Typography variant="h4" sx={{ fontWeight: 700, mb: 1 }}>
          Historical Award Records
        </Typography>

        <Typography color="text.secondary" sx={{ mb: 1 }}>
          Each result here is an individual archived Award version, not
          a family/current-record summary - every historical sequence
          is searchable, including by its exact internal Award ID.
          Selecting a result opens that exact version.
        </Typography>

        <Typography variant="body2" color="text.secondary" sx={{ mb: 4 }}>
          Looking for the current record for an Award number instead?{" "}
          <Link component={RouterLink} to="/awards/search">
            Use Awards
          </Link>
          .
        </Typography>

        <Stack spacing={2}>
          <TextField
            fullWidth
            autoFocus
            placeholder="Title, sponsor, PI, or lead unit..."
            value={qDraft}
            onChange={(event) => setQDraft(event.target.value)}
            slotProps={{
              input: {
                startAdornment: <SearchOutlined sx={{ mr: 1 }} />,
              },
            }}
          />

          <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
            <TextField
              fullWidth
              label="Award number (exact)"
              value={awardNumberDraft}
              onChange={(event) => setAwardNumberDraft(event.target.value)}
            />
            <TextField
              fullWidth
              label="Document number (exact)"
              value={documentNumberDraft}
              onChange={(event) => setDocumentNumberDraft(event.target.value)}
            />
            <TextField
              fullWidth
              label="Award ID (exact)"
              placeholder="e.g. 3561589"
              value={awardIdDraft}
              onChange={(event) => setAwardIdDraft(event.target.value)}
              error={awardIdError}
              helperText={awardIdError ? "Award ID must be a whole number." : " "}
            />
          </Stack>

          <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
            <TextField
              fullWidth
              select
              label="Version"
              value={versionFilter}
              onChange={(event) => updateParam("versionFilter", event.target.value)}
            >
              <MenuItem value="all">All versions</MenuItem>
              <MenuItem value="current">Current only</MenuItem>
              <MenuItem value="historical">Historical only</MenuItem>
            </TextField>
            <TextField
              fullWidth
              select
              label="Sort by"
              value={sort}
              onChange={(event) => updateParam("sort", event.target.value)}
            >
              <MenuItem value="sequence">Sequence number</MenuItem>
              <MenuItem value="date">Last updated</MenuItem>
            </TextField>
          </Stack>
        </Stack>
      </Box>

      {hasSearched && (
        <Box sx={{ maxWidth: 900, width: "100%" }}>
          {searchQuery.isLoading && <LoadingState mode="spinner" />}

          {searchQuery.isError && (
            <ErrorState message="Unable to search Historical Award Records right now. Try again in a moment." />
          )}

          {searchQuery.data && (
            <>
              <Typography
                variant="overline"
                color="text.secondary"
                sx={{ display: "block", mb: 1.5 }}
              >
                {totalElements.toLocaleString()} version
                {totalElements === 1 ? "" : "s"} found
              </Typography>

              {content.length === 0 && (
                <EmptyState variant="text" message="No Award versions match this search." />
              )}

              <Stack spacing={1.25}>
                {content.map((hit) => (
                  <Card
                    key={hit.awardId}
                    variant="outlined"
                    sx={{
                      cursor: "pointer",
                      transition: "border-color .12s ease",
                      "&:hover": { borderColor: "primary.main" },
                    }}
                    role="button"
                    tabIndex={0}
                    onClick={() => navigate(versionDetailPath(hit))}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        navigate(versionDetailPath(hit));
                      }
                    }}
                  >
                    <CardContent
                      sx={{
                        display: "flex",
                        justifyContent: "space-between",
                        alignItems: "center",
                        gap: 2,
                        "&:last-child": { pb: 2 },
                      }}
                    >
                      <Box sx={{ minWidth: 0 }}>
                        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                          <Typography sx={{ fontWeight: 700 }}>
                            {hit.awardNumber}
                          </Typography>
                          <Chip size="small" label={`Seq ${hit.sequenceNumber ?? "—"}`} />
                          <Chip
                            size="small"
                            color={hit.primaryCurrent ? "success" : "default"}
                            label={versionCurrentLabel(hit)}
                          />
                          <StatusPill status={hit.status} domain="award" />
                        </Stack>

                        <Typography variant="body2" color="text.secondary" noWrap sx={{ mt: 0.5 }}>
                          {hit.title ?? "Untitled award"}
                        </Typography>

                        <Typography variant="caption" color="text.secondary">
                          {[hit.sponsor, hit.principalInvestigator, hit.leadUnit]
                            .filter(Boolean)
                            .join(" · ") || "—"}
                          {hit.documentNumber ? ` · Doc ${hit.documentNumber}` : ""}
                        </Typography>
                      </Box>

                      <Typography variant="caption" color="text.secondary" sx={{ flexShrink: 0 }}>
                        {hit.updateTimestamp ?? hit.awardEffectiveDate ?? "—"}
                      </Typography>
                    </CardContent>
                  </Card>
                ))}
              </Stack>

              <Box sx={{ mt: 2 }}>
                <PaginationFooter totalPages={totalPages} page={page} onPageChange={setPage} />
              </Box>
            </>
          )}
        </Box>
      )}
    </Stack>
  );
}
