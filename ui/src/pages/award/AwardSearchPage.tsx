import { Box, Chip, Link, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { Link as RouterLink, useLocation } from "react-router-dom";

import { searchAwardsV1 } from "../../api/client";
import { EmptyState } from "../../components/common/EmptyState";
import { PaginationFooter } from "../../components/common/PaginationFooter";
import { StatusPill } from "../../components/common/StatusPill";
import { FilteredSearchBar } from "../../components/common/search/FilteredSearchBar";
import { HintChips } from "../../components/common/search/HintChips";
import { ResultCard } from "../../components/common/search/ResultCard";
import { ResultCount } from "../../components/common/search/ResultCount";
import { SearchPageLayout } from "../../components/common/search/SearchPageLayout";
import {
  InitialSearchHint,
  SearchStates,
} from "../../components/common/search/SearchStates";
import { emptyResultsMessage } from "../../features/common/filterPresentation.mjs";
import {
  INITIAL_SEARCH_HINT,
  resolveSearchState,
  searchErrorMessage,
} from "../../features/common/searchPresentation.mjs";
import { buildSearchReturn } from "../../features/award/searchReturnContext.mjs";
import { formatCurrencyAmount } from "../../features/award/awardSectionsPresentation.mjs";
import {
  AWARD_WILDCARD_HINT,
  describeSearchResults,
} from "../../features/award/awardSearchPresentation.mjs";
import {
  AWARD_DATE_RANGES,
  AWARD_FILTER_FIELDS,
} from "../../features/search/searchFilterFields.mjs";
import type { AwardFilterKey } from "../../features/search/searchFilterFields.d.mts";
import { useFilteredSearch } from "../../hooks/useFilteredSearch";

const PAGE_SIZE = 25;

const SEARCH_DIMENSIONS = [
  "Award Number",
  "PI",
  "Sponsor",
  "Lead Unit",
  "Title",
  "Document Number",
];

// Entry point of the primary Award workflow: Search -> Search Results ->
// Award Hierarchy -> Award Dashboard.
//
// Current Award FAMILIES only (one current record per Award number); the
// structured filters apply to that current record, server-side. Every
// archived version lives on Historical Awards instead.
export function AwardSearchPage() {
  const search = useFilteredSearch<AwardFilterKey>({
    fields: AWARD_FILTER_FIELDS,
    dateRanges: AWARD_DATE_RANGES,
  });
  const { appliedQuery, appliedActiveFilters, page, hasCriteria } = search;

  // The exact result list to come back to (QA TC-021). Every applied
  // criterion, the sort and the page number already live in the URL, so
  // this one string is the whole state - nothing to keep in sync.
  const location = useLocation();
  const searchReturn = buildSearchReturn(location.pathname, location.search);

  // Keyed on the complete applied request and cancelled via `signal`, so a
  // superseded response can never render under newer criteria.
  const searchQuery = useQuery({
    queryKey: ["award-search-v1", appliedQuery, appliedActiveFilters, page],
    queryFn: ({ signal }) =>
      searchAwardsV1(
        { q: appliedQuery, page, size: PAGE_SIZE, filters: appliedActiveFilters },
        signal,
      ),
    enabled: hasCriteria,
  });

  const results = searchQuery.data
    ? describeSearchResults(searchQuery.data)
    : null;

  // An exact document-number hit is a result in its own right, so a page
  // showing only that must not read as "no results".
  const resultCount =
    (results?.content.length ?? 0) + (results?.exactDocumentMatch ? 1 : 0);

  const state = resolveSearchState({
    hasSearched: hasCriteria,
    isLoading: searchQuery.isLoading,
    isError: searchQuery.isError,
    resultCount,
  });

  return (
    <SearchPageLayout
      title="Find an Award"
      subtitle={`Search by Award number, Grant Number, PI, sponsor, lead unit, title, or document number. ${AWARD_WILDCARD_HINT}`}
      search={
        <FilteredSearchBar
          search={search}
          fields={AWARD_FILTER_FIELDS}
          placeholder="105698, 105698*, Orsmond, NIH..."
          ariaLabel="Search Awards"
          panelId="award-filters"
        />
      }
      belowSearch={
        <>
          <HintChips hints={SEARCH_DIMENSIONS} />

          {/* QA TC-015. An empty submit makes no request and renders no
              result area, which is correct - but it used to say nothing
              at all, so the page looked broken rather than waiting.
              InitialSearchHint already existed for this and no page had
              ever used it. */}
          {!hasCriteria && (
            <Box sx={{ mt: 2.5 }}>
              <InitialSearchHint message={INITIAL_SEARCH_HINT} />
            </Box>
          )}

          <Typography variant="body2" color="text.secondary" sx={{ mt: 2.5 }}>
            This searches for one current Award record per Award number. Looking
            for an internal Award ID or a prior sequence?{" "}
            <Link component={RouterLink} to="/awards/versions/search">
              Use Historical Awards
            </Link>
            .
          </Typography>
        </>
      }
    >
      <SearchStates
        state={state}
        errorMessage={searchErrorMessage(
          searchQuery.error,
          "Unable to search Awards right now. Try again in a moment.",
        )}
      >
        {results && (
          <>
            {results.exactDocumentMatch && (
              <ResultCard
                to={`/awards/${results.exactDocumentMatch.awardId}`}
                state={searchReturn}
                emphasized
                sx={{ mb: 2.5 }}
                banner={
                  <>
                    <Chip
                      label="Exact document number match"
                      color="primary"
                      size="small"
                    />
                    <StatusPill
                      status={results.exactDocumentMatch.status}
                      domain="award"
                    />
                  </>
                }
                identifier={`${results.exactDocumentMatch.awardNumber} · sequence ${results.exactDocumentMatch.sequenceNumber}`}
                title={`Document ${results.exactDocumentMatch.workflowDocumentNumber} · ${
                  results.exactDocumentMatch.title ?? "Untitled award"
                }`}
              />
            )}

            <ResultCount total={results.totalElements} singular="award" />

            {results.content.length === 0 && !results.exactDocumentMatch && (
              <EmptyState
                variant="text"
                message={emptyResultsMessage({
                  noun: "awards",
                  query: appliedQuery,
                  filterCount: search.appliedCount,
                })}
              />
            )}

            <Stack spacing={1.25}>
              {results.content.map((hit) => (
                <ResultCard
                  key={hit.awardId}
                  to={`/awards/hierarchy/${encodeURIComponent(hit.awardNumber)}`}
                  state={searchReturn}
                  identifier={hit.awardNumber}
                  status={<StatusPill status={hit.status} domain="award" />}
                  title={hit.title ?? "Untitled award"}
                  metadata={
                    <>
                      PI: {hit.principalInvestigator ?? "—"} &middot;{" "}
                      {hit.sponsor ?? "Sponsor unknown"}
                      {hit.leadUnit ? ` · ${hit.leadUnit}` : ""}
                      {hit.grantNumber
                        ? ` · Grant Number: ${hit.grantNumber}`
                        : ""}
                    </>
                  }
                  rightSlot={formatCurrencyAmount(hit.currentObligatedAmount)}
                />
              ))}
            </Stack>

            <Box sx={{ mt: 3 }}>
              <PaginationFooter
                totalPages={results.totalPages}
                page={page}
                onPageChange={search.goToPage}
              />
            </Box>
          </>
        )}
      </SearchStates>
    </SearchPageLayout>
  );
}
