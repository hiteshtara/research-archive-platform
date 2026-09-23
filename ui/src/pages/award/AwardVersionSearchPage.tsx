import {
  Box,
  Chip,
  Grid,
  Link,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Link as RouterLink, useSearchParams } from "react-router-dom";

import { searchAwardVersionsV1 } from "../../api/client";
import { EmptyState } from "../../components/common/EmptyState";
import { PaginationFooter } from "../../components/common/PaginationFooter";
import { StatusPill } from "../../components/common/StatusPill";
import { ResultCard } from "../../components/common/search/ResultCard";
import { ResultCount } from "../../components/common/search/ResultCount";
import { SearchBox } from "../../components/common/search/SearchBox";
import { SearchPageLayout } from "../../components/common/search/SearchPageLayout";
import { SearchStates } from "../../components/common/search/SearchStates";
import { resolveSearchState } from "../../features/common/searchPresentation.mjs";
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
    <SearchPageLayout
      title="Search Historical Awards"
      subtitle="Each result is an individual archived Award version, not a family or current-record summary - every historical sequence is searchable, including by its exact internal Award ID. Selecting a result opens that exact version."
      search={
        <SearchBox
          value={qDraft}
          onChange={setQDraft}
          onSubmit={(value) => updateParam("q", value)}
          placeholder="Title, sponsor, PI, or lead unit..."
          ariaLabel="Search Historical Award Records"
        />
      }
      belowSearch={
        <>
          {/*
            Every filter this page already had is preserved. Typing
            still commits to the URL on a debounce, so live searching
            is unchanged; Enter now also submits immediately, which is
            the behaviour shared with every other archive search page.
          */}
          <Grid container spacing={2} sx={{ mt: 1, textAlign: "left" }}>
            <Grid size={{ xs: 12, sm: 6, md: 4 }}>
              <TextField
                fullWidth
                size="small"
                label="Award number (exact)"
                value={awardNumberDraft}
                onChange={(event) => setAwardNumberDraft(event.target.value)}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 4 }}>
              <TextField
                fullWidth
                size="small"
                label="Document number (exact)"
                value={documentNumberDraft}
                onChange={(event) => setDocumentNumberDraft(event.target.value)}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 4 }}>
              <TextField
                fullWidth
                size="small"
                label="Award ID (exact)"
                placeholder="e.g. 3561589"
                value={awardIdDraft}
                onChange={(event) => setAwardIdDraft(event.target.value)}
                error={awardIdError}
                helperText={
                  awardIdError ? "Award ID must be a whole number." : " "
                }
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 4 }}>
              <TextField
                fullWidth
                size="small"
                select
                label="Version"
                value={versionFilter}
                onChange={(event) =>
                  updateParam("versionFilter", event.target.value)
                }
              >
                <MenuItem value="all">All versions</MenuItem>
                <MenuItem value="current">Current only</MenuItem>
                <MenuItem value="historical">Historical only</MenuItem>
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 4 }}>
              <TextField
                fullWidth
                size="small"
                select
                label="Sort by"
                value={sort}
                onChange={(event) => updateParam("sort", event.target.value)}
              >
                <MenuItem value="sequence">Sequence number</MenuItem>
                <MenuItem value="date">Last updated</MenuItem>
              </TextField>
            </Grid>
          </Grid>

          <Typography variant="body2" color="text.secondary" sx={{ mt: 2.5 }}>
            Looking for the current record for an Award number instead?{" "}
            <Link component={RouterLink} to="/awards/search">
              Use Awards
            </Link>
            .
          </Typography>
        </>
      }
    >
      <SearchStates
        state={resolveSearchState({
          hasSearched: hasSearched && !awardIdError,
          isLoading: searchQuery.isLoading,
          isError: searchQuery.isError,
          resultCount: content.length,
        })}
        errorMessage="Unable to search Historical Award Records right now. Try again in a moment."
      >
        {searchQuery.data && (
          <>
            <ResultCount total={totalElements} singular="version" />

            {content.length === 0 && (
              <EmptyState
                variant="text"
                message="No Award versions match this search."
              />
            )}

            <Stack spacing={1.25}>
              {content.map((hit) => (
                <ResultCard
                  key={hit.awardId}
                  to={versionDetailPath(hit)}
                  identifier={hit.awardNumber}
                  secondaryIdentifier={
                    <>
                      <Chip
                        size="small"
                        label={`Seq ${hit.sequenceNumber ?? "—"}`}
                      />
                      <Chip
                        size="small"
                        color={hit.primaryCurrent ? "success" : "default"}
                        label={versionCurrentLabel(hit)}
                      />
                    </>
                  }
                  status={<StatusPill status={hit.status} domain="award" />}
                  title={hit.title ?? "Untitled award"}
                  metadata={
                    <>
                      {[hit.sponsor, hit.principalInvestigator, hit.leadUnit]
                        .filter(Boolean)
                        .join(" · ") || "—"}
                      {hit.documentNumber ? ` · Doc ${hit.documentNumber}` : ""}
                    </>
                  }
                  rightSlot={
                    <Typography variant="caption" color="text.secondary">
                      {hit.updateTimestamp ?? hit.awardEffectiveDate ?? "—"}
                    </Typography>
                  }
                />
              ))}
            </Stack>

            <Box sx={{ mt: 3 }}>
              <PaginationFooter
                totalPages={totalPages}
                page={page}
                onPageChange={setPage}
              />
            </Box>
          </>
        )}
      </SearchStates>
    </SearchPageLayout>
  );
}
