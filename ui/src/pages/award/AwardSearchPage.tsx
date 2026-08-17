import { SearchOutlined } from "@mui/icons-material";
import {
  Box,
  Card,
  CardContent,
  Chip,
  InputAdornment,
  Link,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link as RouterLink, useNavigate, useSearchParams } from "react-router-dom";

import { searchAwardsV1 } from "../../api/client";
import { EmptyState } from "../../components/common/EmptyState";
import { ErrorState } from "../../components/common/ErrorState";
import { LoadingState } from "../../components/common/LoadingState";
import { PaginationFooter } from "../../components/common/PaginationFooter";
import { StatusPill } from "../../components/common/StatusPill";
import { formatCurrencyAmount } from "../../features/award/awardSectionsPresentation.mjs";
import { describeSearchResults } from "../../features/award/awardSearchPresentation.mjs";

const PAGE_SIZE = 25;

const SEARCH_DIMENSIONS = [
  "Award Number",
  "Partial Award Number",
  "Wildcard (*text*)",
  "PI",
  "Sponsor",
  "Lead Unit",
  "Title",
  "Document Number",
];

// Entry point of the primary Award workflow: Search -> Search Results ->
// Award Hierarchy -> Award Dashboard. Matches the approved mockup's
// search-hero + results-list presentation.
export function AwardSearchPage() {
  const navigate = useNavigate();
  // Search state lives in the URL (?q=...&page=...), not local-only
  // useState, so navigating away and back (or using browser back/
  // forward) restores the exact search instead of silently resetting
  // it - matching AwardVersionSearchPage's own established convention
  // for this domain - and a revisit within the query cache's staleTime
  // renders instantly from cache instead of re-fetching.
  const [searchParams, setSearchParams] = useSearchParams();
  const initialQuery = searchParams.get("q") ?? "";
  const initialPage = Number(searchParams.get("page") ?? "0") || 0;

  const [query, setQuery] = useState(initialQuery);
  const [appliedQuery, setAppliedQuery] = useState(initialQuery);
  const [page, setPage] = useState(initialPage);

  const searchQuery = useQuery({
    queryKey: ["award-search-v1", appliedQuery, page],
    queryFn: ({ signal }) =>
      searchAwardsV1({ q: appliedQuery, page, size: PAGE_SIZE }, signal),
    enabled: appliedQuery.trim().length > 0,
  });

  function runSearch(value: string) {
    const trimmed = value.trim();
    setQuery(value);
    setAppliedQuery(trimmed);
    setPage(0);
    setSearchParams(trimmed ? { q: trimmed } : {});
  }

  function goToPage(nextPage: number) {
    setPage(nextPage);
    setSearchParams(appliedQuery ? { q: appliedQuery, page: String(nextPage) } : {});
  }

  const hasSearched = appliedQuery.trim().length > 0;

  return (
    <Stack spacing={4} sx={{ alignItems: "center" }}>
      <Box sx={{ maxWidth: 640, width: "100%", textAlign: "center", mt: 4 }}>
        <Typography variant="h4" sx={{ fontWeight: 700, mb: 1 }}>
          Find an Award
        </Typography>

        <Typography color="text.secondary" sx={{ mb: 4 }}>
          Search by Award number, PI, sponsor, lead unit, title, or document
          number. Use *text* for a wildcard search.
        </Typography>

        <TextField
          fullWidth
          autoFocus
          placeholder="105698, *105698*, Orsmond, NIH..."
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              runSearch(query);
            }
          }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchOutlined />
                </InputAdornment>
              ),
            },
          }}
          sx={{
            "& .MuiOutlinedInput-root": {
              fontSize: 16,
              borderRadius: 2.5,
            },
          }}
        />

        <Stack
          direction="row"
          spacing={1}
          sx={{ flexWrap: "wrap", justifyContent: "center", mt: 2 }}
        >
          {SEARCH_DIMENSIONS.map((dimension) => (
            <Chip
              key={dimension}
              label={dimension}
              size="small"
              variant="outlined"
            />
          ))}
        </Stack>

        <Typography variant="body2" color="text.secondary" sx={{ mt: 2.5 }}>
          This searches for one current Award record per Award number.
          Looking for an internal Award ID or a prior sequence?{" "}
          <Link component={RouterLink} to="/awards/versions/search">
            Use Historical Awards
          </Link>
          .
        </Typography>
      </Box>

      {hasSearched && (
        <Box sx={{ maxWidth: 680, width: "100%" }}>
          {searchQuery.isLoading && <LoadingState mode="spinner" />}

          {searchQuery.isError && (
            <ErrorState message="Unable to search Awards right now. Try again in a moment." />
          )}

          {searchQuery.data && (() => {
            const { totalElements, totalPages, content, exactDocumentMatch } =
              describeSearchResults(searchQuery.data);

            return (
            <>
              {exactDocumentMatch && (
                <Card
                  variant="outlined"
                  sx={{
                    mb: 2.5,
                    cursor: "pointer",
                    borderColor: "primary.main",
                    borderWidth: 2,
                    transition: "border-color .12s ease",
                    "&:hover": { borderColor: "primary.dark" },
                  }}
                  role="button"
                  tabIndex={0}
                  onClick={() =>
                    navigate(`/awards/${exactDocumentMatch.awardId}`)
                  }
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      navigate(`/awards/${exactDocumentMatch.awardId}`);
                    }
                  }}
                >
                  <CardContent sx={{ "&:last-child": { pb: 2 } }}>
                    <Stack
                      direction="row"
                      spacing={1}
                      sx={{ alignItems: "center", mb: 0.5 }}
                    >
                      <Chip
                        label="Exact document number match"
                        color="primary"
                        size="small"
                      />
                      <StatusPill status={exactDocumentMatch.status} domain="award" />
                    </Stack>
                    <Typography sx={{ fontWeight: 700 }}>
                      {exactDocumentMatch.awardNumber} &middot;
                      sequence{" "}
                      {exactDocumentMatch.sequenceNumber}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      Document {exactDocumentMatch.workflowDocumentNumber}
                      {" "}&middot;{" "}
                      {exactDocumentMatch.title ?? "Untitled award"}
                    </Typography>
                  </CardContent>
                </Card>
              )}

              <Typography
                variant="overline"
                color="text.secondary"
                sx={{ display: "block", mb: 1.5 }}
              >
                {totalElements.toLocaleString()} award
                {totalElements === 1 ? "" : "s"} found
              </Typography>

              {content.length === 0 && !exactDocumentMatch && (
                <EmptyState
                  variant="text"
                  message={`No awards match "${appliedQuery}".`}
                />
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
                    onClick={() =>
                      navigate(
                        `/awards/hierarchy/${encodeURIComponent(
                          hit.awardNumber,
                        )}`,
                      )
                    }
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        navigate(
                          `/awards/hierarchy/${encodeURIComponent(
                            hit.awardNumber,
                          )}`,
                        );
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
                        <Stack
                          direction="row"
                          spacing={1}
                          sx={{ alignItems: "center" }}
                        >
                          <Typography sx={{ fontWeight: 700 }}>
                            {hit.awardNumber}
                          </Typography>
                          <StatusPill status={hit.status} domain="award" />
                        </Stack>

                        <Typography
                          variant="body2"
                          color="text.secondary"
                          noWrap
                          sx={{ mt: 0.5 }}
                        >
                          {hit.title ?? "Untitled award"}
                        </Typography>

                        <Typography
                          variant="caption"
                          color="text.secondary"
                          sx={{ display: "block", mt: 0.25 }}
                        >
                          PI: {hit.principalInvestigator ?? "—"} &middot;{" "}
                          {hit.sponsor ?? "Sponsor unknown"}
                          {hit.leadUnit ? ` · ${hit.leadUnit}` : ""}
                        </Typography>
                      </Box>

                      <Typography sx={{ fontWeight: 700, whiteSpace: "nowrap" }}>
                        {formatCurrencyAmount(hit.currentObligatedAmount)}
                      </Typography>
                    </CardContent>
                  </Card>
                ))}
              </Stack>

              <Box sx={{ mt: 3 }}>
                <PaginationFooter
                  totalPages={totalPages}
                  page={page}
                  onPageChange={goToPage}
                />
              </Box>
            </>
            );
          })()}
        </Box>
      )}
    </Stack>
  );
}
