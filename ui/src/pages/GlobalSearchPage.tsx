import { Alert, Box, Button, Chip, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";

import { globalSearch } from "../api/client";
import { EmptyState } from "../components/common/EmptyState";
import { StatusPill } from "../components/common/StatusPill";
import type { StatusDomain } from "../components/common/StatusPill";
import { HintChips } from "../components/common/search/HintChips";
import { ResultCard } from "../components/common/search/ResultCard";
import { ResultCount } from "../components/common/search/ResultCount";
import { FilteredSearchBar } from "../components/common/search/FilteredSearchBar";
import { SearchPageLayout } from "../components/common/search/SearchPageLayout";
import { SearchStates } from "../components/common/search/SearchStates";
import {
  joinMetadata,
  resolveSearchState,
  searchErrorMessage,
} from "../features/common/searchPresentation.mjs";
import {
  describeGlobalSearchOutcome,
  describeResultCard,
  filterOutIrbResults,
  incompleteSearchMessage,
  noDirectMatchesMessage,
} from "../features/search/globalSearchPresentation.mjs";
import { GLOBAL_SEARCH_FILTER_FIELDS } from "../features/search/searchFilterFields.mjs";
import type { GlobalSearchItem } from "../types/api";
import type { GlobalSearchFilterKey } from "../features/search/searchFilterFields.d.mts";
import { useFilteredSearch } from "../hooks/useFilteredSearch";

const MINIMUM_QUERY_LENGTH = 2;

const SEARCH_DIMENSIONS = [
  "Document Number",
  "PI",
  "Sponsor",
  "Award",
  "Title",
];

const STATUS_DOMAINS: Record<string, StatusDomain> = {
  AWARD: "award",
  PROPOSAL: "proposal",
  NEGOTIATION: "negotiation",
  SUBAWARD: "subaward",
};

export function GlobalSearchPage() {
  const [searchParams, setSearchParams] = useSearchParams();

  // This page used ?query= before the archive standardised on ?q=.
  // Existing bookmarks and copied links keep working: a legacy value is
  // migrated to ?q= on arrival rather than silently ignored.
  const legacyQuery = searchParams.get("query");
  useEffect(() => {
    if (legacyQuery && !searchParams.get("q")) {
      setSearchParams({ q: legacyQuery }, { replace: true });
    }
  }, [legacyQuery, searchParams, setSearchParams]);

  const search = useFilteredSearch<GlobalSearchFilterKey>({
    fields: GLOBAL_SEARCH_FILTER_FIELDS,
  });
  const query = search.appliedQuery;
  const draft = search.draftQuery;
  const modules = search.appliedActiveFilters.modules
    ? [search.appliedActiveFilters.modules]
    : [];

  const longEnough = query.trim().length >= MINIMUM_QUERY_LENGTH;

  // Record Type narrows a text search; it never runs one on its own
  // (the API requires at least two characters of text).
  const submit = () => {
    if (draft.trim().length >= MINIMUM_QUERY_LENGTH) {
      search.apply();
    }
  };

  // Keyed on the text AND the record-type restriction, cancelled via
  // `signal`, so a superseded response never renders.
  const searchQuery = useQuery({
    queryKey: ["global-search", query, modules],
    queryFn: async ({ signal }) =>
      filterOutIrbResults(await globalSearch(query, { modules }, signal)),
    enabled: longEnough,
  });

  // Revealing related results is a per-search decision: a new query
  // starts hidden again, so a suggestion never carries over.
  const [relatedRevealed, setRelatedRevealed] = useState(false);
  useEffect(() => {
    setRelatedRevealed(false);
  }, [query]);

  const results = searchQuery.data ?? null;

  const state = resolveSearchState({
    hasSearched: longEnough,
    isLoading: searchQuery.isLoading,
    isError: searchQuery.isError,
    resultCount: results?.results.length ?? 0,
  });

  return (
    <SearchPageLayout
      title="Search the Archive"
      subtitle="Search Awards, Proposals, Negotiations, and Subawards at once by document number, PI, sponsor, award number or title."
      search={
        <FilteredSearchBar
          search={search}
          fields={GLOBAL_SEARCH_FILTER_FIELDS}
          onSubmit={submit}
          placeholder="Search document number, PI, sponsor, award, title..."
          ariaLabel="Search the archive"
          panelId="global-search-filters"
        />
      }
      belowSearch={
        <>
          <HintChips hints={SEARCH_DIMENSIONS} />
          {draft.trim().length < MINIMUM_QUERY_LENGTH &&
            (draft.trim().length > 0 || search.hasUnappliedChanges) && (
              <Typography
                variant="body2"
                color="text.secondary"
                sx={{ mt: 2.5 }}
              >
                Enter at least {MINIMUM_QUERY_LENGTH} characters to search.
              </Typography>
            )}
        </>
      }
    >
      <SearchStates
        state={state}
        errorMessage={searchErrorMessage(
          searchQuery.error,
          "Search results could not be loaded.",
        )}
      >
        {results && (() => {
          const outcome = describeGlobalSearchOutcome({
            results: results.results,
            failedModules: results.failedModules,
            relatedRevealed,
          });

          const renderCard = (result: GlobalSearchItem) => {
            const card = describeResultCard(result);

            return (
              <ResultCard
                key={`${result.module}-${result.recordId}-${result.identifier}-${result.sequenceNumber}`}
                to={result.route || undefined}
                identifier={card.identifier}
                secondaryIdentifier={
                  <>
                    <Chip label={result.module} size="small" color="primary" />
                    {result.documentNumber && (
                      <Chip
                        label={`Doc ${result.documentNumber}`}
                        size="small"
                        variant="outlined"
                      />
                    )}
                    {card.showSemanticChip && (
                      <Chip
                        label={card.semanticChipLabel}
                        size="small"
                        variant="outlined"
                      />
                    )}
                  </>
                }
                status={
                  result.status ? (
                    <StatusPill
                      status={result.status}
                      domain={STATUS_DOMAINS[result.module ?? ""] ?? "award"}
                    />
                  ) : undefined
                }
                title={card.title}
                // card.identifierLine repeats the identifier that is
                // already this card's first line, so the secondary
                // detail uses card.subtitleLine instead.
                metadata={joinMetadata([
                  card.subtitleLine,
                  card.piLine,
                  card.matchedCaption,
                ])}
              />
            );
          };

          return (
            <>
              <ResultCount total={outcome.directCount} singular="result" />

              {/* A module that failed is not a module that found
                  nothing, so this is never collapsed into "no
                  matches". */}
              {outcome.searchIncomplete && (
                <Alert severity="warning" sx={{ mb: 1.5 }}>
                  {incompleteSearchMessage(outcome.failedModules)}
                </Alert>
              )}

              {outcome.showNoDirectMatches && (
                <EmptyState variant="text" message={noDirectMatchesMessage(query)} />
              )}

              {outcome.directCount > 0 && (
                <Stack spacing={1.25}>{outcome.direct.map(renderCard)}</Stack>
              )}

              {/* With nothing direct, related results stay behind a
                  deliberate action - a suggestion should never arrive
                  looking like an answer. */}
              {outcome.offerRelatedToggle && !relatedRevealed && (
                <Box sx={{ mt: 1 }}>
                  <Button variant="outlined" onClick={() => setRelatedRevealed(true)}>
                    {outcome.showRelatedActionLabel}
                  </Button>
                </Box>
              )}

              {outcome.showRelatedSection && (
                <Box sx={{ mt: outcome.directCount > 0 ? 3 : 2 }}>
                  <Typography component="h2" variant="h6">
                    {outcome.relatedHeading}
                  </Typography>
                  <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, mb: 1.5 }}>
                    {outcome.relatedExplanation}
                  </Typography>
                  <Stack spacing={1.25}>{outcome.related.map(renderCard)}</Stack>
                </Box>
              )}
            </>
          );
        })()}
      </SearchStates>
    </SearchPageLayout>
  );
}
