import { Box, Chip, Link, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { Link as RouterLink } from "react-router-dom";

import { searchAwardsV1 } from "../../api/client";
import { EmptyState } from "../../components/common/EmptyState";
import { PaginationFooter } from "../../components/common/PaginationFooter";
import { StatusPill } from "../../components/common/StatusPill";
import { HintChips } from "../../components/common/search/HintChips";
import { ResultCard } from "../../components/common/search/ResultCard";
import { ResultCount } from "../../components/common/search/ResultCount";
import { SearchBox } from "../../components/common/search/SearchBox";
import { SearchPageLayout } from "../../components/common/search/SearchPageLayout";
import { SearchStates } from "../../components/common/search/SearchStates";
import { resolveSearchState } from "../../features/common/searchPresentation.mjs";
import { formatCurrencyAmount } from "../../features/award/awardSectionsPresentation.mjs";
import { describeSearchResults } from "../../features/award/awardSearchPresentation.mjs";
import { useSearchQueryParam } from "../../hooks/useSearchQueryParam";

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
// Award Hierarchy -> Award Dashboard.
//
// Awards is the visual reference for every archive search page, so this
// page now consumes the shared SearchPageLayout/SearchBox/HintChips/
// ResultCount/ResultCard/SearchStates rather than owning that markup:
// the shared components were extracted FROM this page, and it must
// render identically after the extraction. The only deliberate
// behavioural change is that each result card is now a real anchor
// instead of a div with an onClick, so Cmd-click, middle-click, "Open
// in new tab" and "Copy link address" work.
export function AwardSearchPage() {
  const { draft, setDraft, query, page, submit, goToPage, hasSearched } =
    useSearchQueryParam();

  const searchQuery = useQuery({
    queryKey: ["award-search-v1", query, page],
    queryFn: ({ signal }) =>
      searchAwardsV1({ q: query, page, size: PAGE_SIZE }, signal),
    enabled: hasSearched,
  });

  const results = searchQuery.data
    ? describeSearchResults(searchQuery.data)
    : null;

  // An exact document-number hit is a result in its own right, so a page
  // showing only that must not read as "no results".
  const resultCount =
    (results?.content.length ?? 0) + (results?.exactDocumentMatch ? 1 : 0);

  const state = resolveSearchState({
    hasSearched,
    isLoading: searchQuery.isLoading,
    isError: searchQuery.isError,
    resultCount,
  });

  return (
    <SearchPageLayout
      title="Find an Award"
      subtitle="Search by Award number, Grant Number, PI, sponsor, lead unit, title, or document number. Use *text* for a wildcard search."
      search={
        <SearchBox
          value={draft}
          onChange={setDraft}
          onSubmit={submit}
          placeholder="105698, *105698*, Orsmond, NIH..."
          ariaLabel="Search Awards"
        />
      }
      belowSearch={
        <>
          <HintChips hints={SEARCH_DIMENSIONS} />

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
        errorMessage="Unable to search Awards right now. Try again in a moment."
      >
        {results && (
          <>
            {results.exactDocumentMatch && (
              <ResultCard
                to={`/awards/${results.exactDocumentMatch.awardId}`}
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
                message={`No awards match "${query}".`}
              />
            )}

            <Stack spacing={1.25}>
              {results.content.map((hit) => (
                <ResultCard
                  key={hit.awardId}
                  to={`/awards/hierarchy/${encodeURIComponent(hit.awardNumber)}`}
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
                onPageChange={goToPage}
              />
            </Box>
          </>
        )}
      </SearchStates>
    </SearchPageLayout>
  );
}
