import { Box, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getNegotiations } from "../api/client";
import { EmptyState } from "../components/common/EmptyState";
import { PaginationFooter } from "../components/common/PaginationFooter";
import { StatusPill } from "../components/common/StatusPill";
import { FilteredSearchBar } from "../components/common/search/FilteredSearchBar";
import { ResultCard } from "../components/common/search/ResultCard";
import { ResultCount } from "../components/common/search/ResultCount";
import { SearchPageLayout } from "../components/common/search/SearchPageLayout";
import { SearchStates } from "../components/common/search/SearchStates";
import {
  joinMetadata,
  resolveSearchState,
} from "../features/common/searchPresentation.mjs";
import {
  NEGOTIATION_DATE_RANGES,
  NEGOTIATION_FILTER_FIELDS,
  buildNegotiationPath,
  buildNegotiationSearchParams,
  formatLeadUnit,
} from "../features/negotiation/negotiationSearchPresentation.mjs";
import type { NegotiationFilterKey } from "../features/negotiation/negotiationSearchPresentation.d.mts";
import { useFilteredSearch } from "../hooks/useFilteredSearch";

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
  // Applied search state lives in the URL (shared useFilteredSearch), so a
  // reload, a shared link or Back/Forward reproduces the same result set;
  // the inputs edit a draft that only applies on Enter / Apply Filters.
  const search = useFilteredSearch<NegotiationFilterKey>({
    fields: NEGOTIATION_FILTER_FIELDS,
    dateRanges: NEGOTIATION_DATE_RANGES,
  });

  // Nothing is fetched until the user asks for something: free text or
  // at least one applied filter.
  const hasSearched = search.hasCriteria;

  const searchParameters = buildNegotiationSearchParams({
    query: search.appliedQuery,
    filters: search.appliedFilters,
    page: search.page,
    size: PAGE_SIZE,
  });

  // Keyed on the complete applied request and cancelled via `signal`, so a
  // superseded response can never render under newer criteria.
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

  return (
    <SearchPageLayout
      title="Negotiations"
      subtitle="Search archived negotiations and their associated Kuali records."
      search={
        <FilteredSearchBar
          search={search}
          fields={NEGOTIATION_FILTER_FIELDS}
          placeholder="Negotiation ID, title, status, negotiator, PI, sponsor, lead unit..."
          ariaLabel="Search Negotiations"
          panelId="negotiation-filters"
        />
      }
      belowSearch={
        !hasSearched && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 2.5 }}>
            Search by {SEARCH_DIMENSIONS.join(", ")}, or open Filters to
            combine criteria.
          </Typography>
        )
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
                  onPageChange={search.goToPage}
                />
              </Box>
            )}
          </>
        )}
      </SearchStates>
    </SearchPageLayout>
  );
}
