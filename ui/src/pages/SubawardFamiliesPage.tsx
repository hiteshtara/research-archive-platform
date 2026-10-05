import { Box, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getSubawards } from "../api/client";
import { EmptyState } from "../components/common/EmptyState";
import { PaginationFooter } from "../components/common/PaginationFooter";
import { StatusPill } from "../components/common/StatusPill";
import { FilteredSearchBar } from "../components/common/search/FilteredSearchBar";
import { HintChips } from "../components/common/search/HintChips";
import { ResultCard } from "../components/common/search/ResultCard";
import { ResultCount } from "../components/common/search/ResultCount";
import { SearchPageLayout } from "../components/common/search/SearchPageLayout";
import { SearchStates } from "../components/common/search/SearchStates";
import { emptyResultsMessage } from "../features/common/filterPresentation.mjs";
import {
  joinMetadata,
  resolveSearchState,
  searchErrorMessage,
} from "../features/common/searchPresentation.mjs";
import {
  SUBAWARD_DATE_RANGES,
  SUBAWARD_FILTER_FIELDS,
} from "../features/search/searchFilterFields.mjs";
import type { SubawardFilterKey } from "../features/search/searchFilterFields.d.mts";
import { useFilteredSearch } from "../hooks/useFilteredSearch";

const PAGE_SIZE = 25;

const SEARCH_DIMENSIONS = [
  "Subaward Code",
  "FRN",
  "Document Number",
  "Title",
  "Organization",
  "Account",
];

function formatDateRange(startDate: string | null, endDate: string | null) {
  if (!startDate && !endDate) {
    return null;
  }
  return `${startDate ?? "—"} to ${endDate ?? "—"}`;
}

export function SubawardFamiliesPage() {
  const search = useFilteredSearch<SubawardFilterKey>({
    fields: SUBAWARD_FILTER_FIELDS,
    dateRanges: SUBAWARD_DATE_RANGES,
  });
  const { appliedQuery: query, appliedActiveFilters, page } = search;
  const hasSearched = search.hasCriteria;

  // Free text keeps the server's FRN branch (a 9-10 digit query also
  // matches Funding Reference Numbers across the family); structured
  // filters AND with it server-side. Keyed on the complete applied request
  // and cancelled via `signal`.
  const searchQuery = useQuery({
    queryKey: ["subawards", query, appliedActiveFilters, page],
    queryFn: ({ signal }) =>
      getSubawards(
        { query, page, size: PAGE_SIZE, filters: appliedActiveFilters },
        signal,
      ),
    // No preload. This page used to fetch the first 25 of 88,818
    // archived Subaward records on mount.
    enabled: hasSearched,
  });

  const results = searchQuery.data ?? null;

  const state = resolveSearchState({
    hasSearched,
    isLoading: searchQuery.isLoading,
    isError: searchQuery.isError,
    resultCount: results?.content.length ?? 0,
  });

  return (
    <SearchPageLayout
      title="Find a Subaward"
      subtitle="Search archived Subaward records by Subaward code, FRN, document number, title, organization or account, and open a specific source version."
      search={
        <FilteredSearchBar
          search={search}
          fields={SUBAWARD_FILTER_FIELDS}
          placeholder="Subaward code, FRN, document number, organization..."
          ariaLabel="Search Subawards"
          panelId="subaward-filters"
        />
      }
      belowSearch={<HintChips hints={SEARCH_DIMENSIONS} />}
    >
      <SearchStates
        state={state}
        errorMessage={searchErrorMessage(
          searchQuery.error,
          "Unable to search Subawards right now. Try again in a moment.",
        )}
      >
        {results && (
          <>
            <ResultCount total={results.totalElements} singular="subaward" />

            {results.content.length === 0 && (
              <EmptyState
                variant="text"
                message={emptyResultsMessage({
                  noun: "subawards",
                  query,
                  filterCount: search.appliedCount,
                })}
              />
            )}

            <Stack spacing={1.25}>
              {results.content.map((subaward) => (
                <ResultCard
                  key={`${subaward.subawardId}-${subaward.sequenceNumber}`}
                  to={`/subawards/${encodeURIComponent(subaward.subawardId)}`}
                  identifier={subaward.subawardCode}
                  secondaryIdentifier={
                    <Typography variant="body2" color="text.secondary">
                      sequence {subaward.sequenceNumber}
                    </Typography>
                  }
                  // statusDescription is numbered in the source
                  // ("07. Executed"); StatusPill shows the words and
                  // keeps the ordinal as secondary metadata.
                  status={
                    <StatusPill
                      status={subaward.statusDescription}
                      domain="subaward"
                    />
                  }
                  title={subaward.title ?? "Untitled Subaward"}
                  metadata={joinMetadata([
                    // organizationId has no verified name anywhere in
                    // the archive, so the identifier stands alone
                    // rather than being dressed up as a name.
                    subaward.organizationId
                      ? `Organization ${subaward.organizationId}`
                      : null,
                    subaward.accountNumber
                      ? `Account ${subaward.accountNumber}`
                      : null,
                    subaward.documentNumber
                      ? `Document ${subaward.documentNumber}`
                      : null,
                  ])}
                  rightSlot={
                    <Typography variant="body2" color="text.secondary">
                      {formatDateRange(subaward.startDate, subaward.endDate) ??
                        "—"}
                    </Typography>
                  }
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
