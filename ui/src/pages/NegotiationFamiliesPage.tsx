import { Box, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useSearchParams } from "react-router-dom";

import { getNegotiations } from "../api/client";
import { EmptyState } from "../components/common/EmptyState";
import { FilterChips } from "../components/common/FilterChips";
import {
  FilterPanel,
  FilterToggleButton,
} from "../components/common/FilterPanel";
import { PaginationFooter } from "../components/common/PaginationFooter";
import { StatusPill } from "../components/common/StatusPill";
import { ResultCard } from "../components/common/search/ResultCard";
import { ResultCount } from "../components/common/search/ResultCount";
import { SearchBox } from "../components/common/search/SearchBox";
import { SearchPageLayout } from "../components/common/search/SearchPageLayout";
import { SearchStates } from "../components/common/search/SearchStates";
import {
  joinMetadata,
  resolveSearchState,
} from "../features/common/searchPresentation.mjs";
import {
  NEGOTIATION_FILTER_FIELDS,
  buildNegotiationFilterChips,
  buildNegotiationPath,
  buildNegotiationSearchParams,
  buildNegotiationUrlParams,
  clearNegotiationFilters,
  countActiveNegotiationFilters,
  formatLeadUnit,
  hasNegotiationSearchCriteria,
  negotiationFiltersFromParams,
  negotiationPageFromParams,
  negotiationQueryFromParams,
  removeNegotiationFilter,
} from "../features/negotiation/negotiationSearchPresentation.mjs";
import type {
  NegotiationFilterKey,
  NegotiationFilters,
} from "../features/negotiation/negotiationSearchPresentation.d.mts";

const PAGE_SIZE = 25;

const SEARCH_DIMENSIONS = [
  "Negotiation ID",
  "Title",
  "Status",
  "Negotiator",
  "PI",
  "Sponsor",
  "Lead Unit",
];

function display(value: string | number | null) {
  return value ?? "—";
}

export function NegotiationFamiliesPage() {
  // The applied search lives in the URL, like every other archive search
  // page, so a reload or a shared link reproduces the same result set
  // instead of dropping back to the empty state with populated inputs.
  const [searchParams, setSearchParams] = useSearchParams();

  const appliedSearch = negotiationQueryFromParams(searchParams);
  const appliedFilters = negotiationFiltersFromParams(searchParams);
  const page = negotiationPageFromParams(searchParams);

  // `draft`/`filters` are what the inputs are editing; the URL holds what
  // the last search actually used. Keeping them apart means typing does
  // not fire a request per keystroke, and the chips always describe the
  // result set on screen rather than a pending edit.
  const [draft, setDraft] = useState(appliedSearch);
  const [filters, setFilters] = useState<NegotiationFilters>(appliedFilters);
  const [filtersOpen, setFiltersOpen] = useState(false);

  // Nothing is fetched until the user asks for something. This page used
  // to query on mount, putting the first page of 10,775 Negotiations on
  // screen before anyone had searched.
  const hasSearched = hasNegotiationSearchCriteria({
    query: appliedSearch,
    filters: appliedFilters,
  });

  const searchParameters = buildNegotiationSearchParams({
    query: appliedSearch,
    filters: appliedFilters,
    page,
    size: PAGE_SIZE,
  });

  const query = useQuery({
    queryKey: ["negotiations", searchParameters],
    queryFn: ({ signal }) => getNegotiations(searchParameters, signal),
    enabled: hasSearched,
  });

  const results = query.data ?? null;

  const state = resolveSearchState({
    hasSearched,
    isLoading: query.isLoading,
    isError: query.isError,
    resultCount: results?.content.length ?? 0,
  });

  const commit = (
    nextQuery: string,
    nextFilters: NegotiationFilters,
    nextPage = 0,
  ) => {
    setSearchParams(
      buildNegotiationUrlParams({
        query: nextQuery,
        filters: nextFilters,
        page: nextPage,
      }),
    );
  };

  const applySearch = () => commit(draft.trim(), filters);

  const changeFilter = (key: NegotiationFilterKey, value: string) => {
    setFilters((current) => ({ ...current, [key]: value }));
  };

  // Removing a chip acts on the applied result set immediately, so it
  // drops the filter from both the panel and the live search at once.
  const removeChip = (key: NegotiationFilterKey) => {
    const next = removeNegotiationFilter(appliedFilters, key);
    setFilters(next);
    commit(appliedSearch, next);
  };

  // Clear All removes the structured filters AND the free text, which
  // returns the page to its initial state and stops it querying.
  const clearAll = () => {
    const cleared = clearNegotiationFilters();
    setFilters(cleared);
    setDraft("");
    commit("", cleared);
  };

  return (
    <SearchPageLayout
      title="Negotiations"
      subtitle="Search archived negotiations and their associated Kuali records."
      search={
        <Stack
          direction={{ xs: "column", sm: "row" }}
          spacing={1.5}
          sx={{ alignItems: { xs: "stretch", sm: "center" } }}
        >
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <SearchBox
              value={draft}
              onChange={setDraft}
              onSubmit={applySearch}
              placeholder="Negotiation ID, title, status, negotiator, PI, sponsor, lead unit..."
              ariaLabel="Search Negotiations"
            />
          </Box>
          <FilterToggleButton
            open={filtersOpen}
            activeCount={countActiveNegotiationFilters(filters)}
            onClick={() => setFiltersOpen((open) => !open)}
          />
        </Stack>
      }
      belowSearch={
        <>
          <FilterPanel
            open={filtersOpen}
            fields={NEGOTIATION_FILTER_FIELDS}
            values={filters}
            onChange={changeFilter}
            onApply={applySearch}
            onClearAll={clearAll}
          />
          <FilterChips
            chips={buildNegotiationFilterChips(appliedFilters)}
            onRemove={removeChip}
            onClearAll={clearAll}
          />
          {!hasSearched && (
            <Typography
              variant="body2"
              color="text.secondary"
              sx={{ mt: 2.5 }}
            >
              Search by {SEARCH_DIMENSIONS.join(", ")}, or open Filters to
              combine criteria.
            </Typography>
          )}
        </>
      }
    >
      <SearchStates
        state={state}
        errorMessage="Unable to load Negotiations right now. Try again in a moment."
      >
        {results && (
          <>
            <ResultCount total={results.totalElements} singular="negotiation" />

            {results.content.length === 0 && (
              <EmptyState
                variant="text"
                message="No matching Negotiations were found."
              />
            )}

            <Stack spacing={1.25}>
              {results.content.map((negotiation) => {
                // Lead Unit: BU explicitly asked for the actual unit name
                // as the primary value, with the number as secondary
                // metadata - never the number alone.
                const leadUnit = formatLeadUnit(
                  negotiation.leadUnitName,
                  negotiation.leadUnitNumber,
                );

                return (
                  <ResultCard
                    key={negotiation.negotiationId}
                    to={buildNegotiationPath(negotiation.negotiationId)}
                    identifier={negotiation.negotiationId}
                    secondaryIdentifier={
                      <Typography variant="body2" color="text.secondary">
                        {display(
                          negotiation.negotiationAgreementTypeDescription,
                        )}
                      </Typography>
                    }
                    status={
                      <StatusPill
                        status={negotiation.negotiationStatusDescription}
                        domain="negotiation"
                      />
                    }
                    title={display(negotiation.title)}
                    metadata={joinMetadata([
                      negotiation.principalInvestigatorName,
                      negotiation.sponsorName,
                      leadUnit.primary,
                      leadUnit.secondary,
                      negotiation.negotiatorFullName
                        ? `Negotiator ${negotiation.negotiatorFullName}`
                        : null,
                      negotiation.negotiationStartDate
                        ? `Started ${negotiation.negotiationStartDate}`
                        : null,
                    ])}
                  />
                );
              })}
            </Stack>

            {results.totalPages > 1 && (
              <Box sx={{ mt: 3 }}>
                <PaginationFooter
                  page={results.page}
                  totalPages={results.totalPages}
                  onPageChange={(next) =>
                    commit(appliedSearch, appliedFilters, next)
                  }
                />
              </Box>
            )}
          </>
        )}
      </SearchStates>
    </SearchPageLayout>
  );
}
