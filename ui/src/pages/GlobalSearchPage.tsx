import { Alert, Chip, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useEffect } from "react";
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
} from "../features/common/searchPresentation.mjs";
import {
  describeResultCard,
  filterOutIrbResults,
} from "../features/search/globalSearchPresentation.mjs";
import { GLOBAL_SEARCH_FILTER_FIELDS } from "../features/search/searchFilterFields.mjs";
import { emptyResultsMessage } from "../features/common/filterPresentation.mjs";
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
        errorMessage="Search results could not be loaded."
      >
        {results && (
          <>
            <ResultCount total={results.totalResults} singular="result" />

            {results.failedModules.length > 0 && (
              <Alert severity="warning" sx={{ mb: 1.5 }}>
                {results.failedModules.join(", ")} could not be searched right
                now. Showing results from the remaining modules.
              </Alert>
            )}

            {results.results.length === 0 && (
              <EmptyState
                variant="text"
                message={emptyResultsMessage({
                  noun: "archive records",
                  query,
                  filterCount: search.appliedCount,
                })}
              />
            )}

            <Stack spacing={1.25}>
              {results.results.map((result) => {
                const card = describeResultCard(result);

                return (
                  <ResultCard
                    key={`${result.module}-${result.recordId}-${result.identifier}-${result.sequenceNumber}`}
                    to={result.route || undefined}
                    identifier={card.identifier}
                    secondaryIdentifier={
                      <>
                        <Chip
                          label={result.module}
                          size="small"
                          color="primary"
                        />
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
                          domain={
                            STATUS_DOMAINS[result.module ?? ""] ?? "award"
                          }
                        />
                      ) : undefined
                    }
                    title={card.title}
                    // card.identifierLine repeats the identifier that
                    // is already this card's first line, so the
                    // secondary detail uses card.subtitleLine instead.
                    metadata={joinMetadata([
                      card.subtitleLine,
                      card.piLine,
                      card.matchedCaption,
                    ])}
                  />
                );
              })}
            </Stack>
          </>
        )}
      </SearchStates>
    </SearchPageLayout>
  );
}
