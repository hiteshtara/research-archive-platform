import { Chip, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getProposalFamilies } from "../api/client";
import { EmptyState } from "../components/common/EmptyState";
import { StatusPill } from "../components/common/StatusPill";
import { HintChips } from "../components/common/search/HintChips";
import { ResultCard } from "../components/common/search/ResultCard";
import { ResultCount } from "../components/common/search/ResultCount";
import { SearchBox } from "../components/common/search/SearchBox";
import { SearchPageLayout } from "../components/common/search/SearchPageLayout";
import { SearchStates } from "../components/common/search/SearchStates";
import {
  joinMetadata,
  resolveSearchState,
} from "../features/common/searchPresentation.mjs";
import { useSearchQueryParam } from "../hooks/useSearchQueryParam";

// The Proposal families endpoint returns a plain capped array, not a
// paginated page. That API contract is left exactly as it is - changing
// it merely to gain a pagination control would be changing API semantics
// for styling - so this page shows the cap honestly instead of implying
// the count is a total.
const RESULT_LIMIT = 100;

const SEARCH_DIMENSIONS = [
  "Proposal Number",
  "PI",
  "Sponsor",
  "Lead Unit",
  "Title",
];

export function ProposalFamiliesPage() {
  const { draft, setDraft, query, submit, hasSearched } =
    useSearchQueryParam();

  const searchQuery = useQuery({
    queryKey: ["proposal-families", query],
    queryFn: ({ signal }) =>
      getProposalFamilies({ query, limit: RESULT_LIMIT }, signal),
    // Nothing is fetched until a search is run. This page used to load
    // 100 Proposal families on mount; a primary search page must not put
    // rows on screen merely because data exists.
    enabled: hasSearched,
  });

  const results = searchQuery.data ?? null;

  const state = resolveSearchState({
    hasSearched,
    isLoading: searchQuery.isLoading,
    isError: searchQuery.isError,
    resultCount: results?.length ?? 0,
  });

  return (
    <SearchPageLayout
      title="Find a Proposal"
      subtitle="Search archived Proposal records by Proposal number, PI, sponsor, lead unit or title. One result per Proposal Number."
      search={
        <SearchBox
          value={draft}
          onChange={setDraft}
          onSubmit={submit}
          placeholder="Proposal number, Orsmond, NIH..."
          ariaLabel="Search Proposals"
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
            <ResultCount total={results.length} singular="proposal" />

            {results.length === 0 && (
              <EmptyState
                variant="text"
                message={`No proposals match "${query}".`}
              />
            )}

            <Stack spacing={1.25}>
              {results.map((proposal) => (
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

            {results.length === RESULT_LIMIT && (
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ display: "block", mt: 2, textAlign: "center" }}
              >
                Showing the first {RESULT_LIMIT} matches. Narrow the search
                to see more specific results.
              </Typography>
            )}
          </>
        )}
      </SearchStates>
    </SearchPageLayout>
  );
}
