import { Box, Chip, Link, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { Link as RouterLink } from "react-router-dom";

import { searchProposalFamilies } from "../api/client";
import { EmptyState } from "../components/common/EmptyState";
import { PaginationFooter } from "../components/common/PaginationFooter";
import { StatusPill } from "../components/common/StatusPill";
import { FilteredSearchBar } from "../components/common/search/FilteredSearchBar";
import { HintChips } from "../components/common/search/HintChips";
import { ResultCard } from "../components/common/search/ResultCard";
import { ResultCount } from "../components/common/search/ResultCount";
import { SearchPageLayout } from "../components/common/search/SearchPageLayout";
import {
  InitialSearchHint,
  SearchStates,
} from "../components/common/search/SearchStates";
import { emptyResultsMessage } from "../features/common/filterPresentation.mjs";
import {
  INITIAL_SEARCH_HINT,
  joinMetadata,
  resolveSearchState,
  searchErrorMessage,
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
      belowSearch={
        <>
          <HintChips hints={SEARCH_DIMENSIONS} />
          {/* QA TC-015: say that nothing was searched yet,
              rather than rendering nothing at all. */}
          {!hasCriteria && (
            <Box sx={{ mt: 2.5 }}>
              <InitialSearchHint message={INITIAL_SEARCH_HINT} />
            </Box>
          )}

          {/*
            * Two different questions get asked here and this page
            * answers only one. "Every historical proposal row" belongs
            * in Kuali Documents; "this proposal's versions" belongs on
            * the record itself. Saying so is cheaper than a third page
            * listing the same rows.
            */}
          <Typography variant="body2" color="text.secondary" sx={{ mt: 2.5 }}>
            This lists one result per Proposal Number, showing its current
            version. To browse every archived proposal document,{" "}
            <Link component={RouterLink} to="/documents?module=PROPOSAL">
              use Kuali Documents
            </Link>
            . To see one proposal&rsquo;s versions, open it and choose Versions.
          </Typography>
        </>
      }
    >
      <SearchStates
        state={state}
        errorMessage={searchErrorMessage(
          searchQuery.error,
          "Unable to search Proposals right now. Try again in a moment.",
        )}
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
                    /*
                     * This value is proposal_sequence_status (the
                     * repository selects it AS status), which says
                     * whether this is the current version of the
                     * proposal - not whether the proposal is open,
                     * pending or funded. Unlabelled, "ACTIVE" was read
                     * as an open proposal, while the detail page shows
                     * the same record as Sequence status ACTIVE and
                     * Status Not Funded. The label is what keeps those
                     * apart.
                     */
                    <Stack
                      direction="row"
                      spacing={0.75}
                      sx={{ alignItems: "center" }}
                    >
                      <Typography variant="caption" color="text.secondary">
                        Sequence status
                      </Typography>
                      <StatusPill status={proposal.status} domain="proposal" />
                    </Stack>
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
