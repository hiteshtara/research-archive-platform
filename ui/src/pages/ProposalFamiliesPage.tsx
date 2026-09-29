import { Box, Chip, Stack } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { searchProposalFamilies } from "../api/client";
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
} from "../features/common/searchPresentation.mjs";
import { PROPOSAL_FILTER_FIELDS } from "../features/search/searchFilterFields.mjs";
import type { ProposalFilterKey } from "../features/search/searchFilterFields.d.mts";
import { useFilteredSearch } from "../hooks/useFilteredSearch";

const PAGE_SIZE = 25;

const SEARCH_DIMENSIONS = [
  "Proposal Number",
  "PI",
  "Sponsor",
  "Lead Unit",
  "Title",
];

// One result per Proposal Number (its latest version). Uses the paged,
// filterable GET /api/proposals/search, so the count is a real total and
// every match is reachable - the older capped /families endpoint could
// only show the first 100.
export function ProposalFamiliesPage() {
  const search = useFilteredSearch<ProposalFilterKey>({
    fields: PROPOSAL_FILTER_FIELDS,
  });
  const { appliedQuery, appliedActiveFilters, page, hasCriteria } = search;

  // Keyed on the complete applied request and cancelled via `signal`.
  const searchQuery = useQuery({
    queryKey: ["proposal-search", appliedQuery, appliedActiveFilters, page],
    queryFn: ({ signal }) =>
      searchProposalFamilies(
        { query: appliedQuery, page, size: PAGE_SIZE, filters: appliedActiveFilters },
        signal,
      ),
    // Nothing is fetched until a search is run.
    enabled: hasCriteria,
  });

  const results = searchQuery.data ?? null;

  const state = resolveSearchState({
    hasSearched: hasCriteria,
    isLoading: searchQuery.isLoading,
    isError: searchQuery.isError,
    resultCount: results?.content.length ?? 0,
  });

  return (
    <SearchPageLayout
      title="Find a Proposal"
      subtitle="Search archived Proposal records by Proposal number, PI, sponsor, lead unit or title. One result per Proposal Number."
      search={
        <FilteredSearchBar
          search={search}
          fields={PROPOSAL_FILTER_FIELDS}
          placeholder="Proposal number, Orsmond, NIH..."
          ariaLabel="Search Proposals"
          panelId="proposal-filters"
        />
      }
      belowSearch={<HintChips hints={SEARCH_DIMENSIONS} />}
    >
      <SearchStates
        state={state}
        errorMessage="Unable to search Proposals right now. Try again in a moment."
      >
        {results && (
          <>
            <ResultCount total={results.totalElements} singular="proposal" />

            {results.content.length === 0 && (
              <EmptyState
                variant="text"
                message={emptyResultsMessage({
                  noun: "proposals",
                  query: appliedQuery,
                  filterCount: search.appliedCount,
                })}
              />
            )}

            <Stack spacing={1.25}>
              {results.content.map((proposal) => (
                <ResultCard
                  key={proposal.proposalNumber}
                  to={`/proposals/dashboard/${encodeURIComponent(proposal.currentProposalId)}`}
                  identifier={proposal.proposalNumber}
                  status={
                    <StatusPill status={proposal.status} domain="proposal" />
                  }
                  title={proposal.title ?? "Untitled Proposal"}
                  metadata={joinMetadata([
                    proposal.principalInvestigator
                      ? `PI: ${proposal.principalInvestigator}`
                      : null,
                    proposal.sponsorName,
                    proposal.leadUnitName,
                  ])}
                  rightSlot={
                    <Chip
                      size="small"
                      label={`v${proposal.latestVersionNumber}`}
                    />
                  }
                />
              ))}
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
