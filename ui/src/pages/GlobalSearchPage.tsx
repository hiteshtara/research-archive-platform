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
import { SearchBox } from "../components/common/search/SearchBox";
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
import { useSearchQueryParam } from "../hooks/useSearchQueryParam";

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

  const { draft, setDraft, query, submit } = useSearchQueryParam();

  const longEnough = query.trim().length >= MINIMUM_QUERY_LENGTH;

  const searchQuery = useQuery({
    queryKey: ["global-search", query],
    queryFn: async () => filterOutIrbResults(await globalSearch(query)),
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
        <SearchBox
          value={draft}
          onChange={setDraft}
          onSubmit={(value) => {
            if (value.trim().length >= MINIMUM_QUERY_LENGTH) {
              submit(value);
            }
          }}
          placeholder="Search document number, PI, sponsor, award, title..."
          ariaLabel="Search the archive"
        />
      }
      belowSearch={
        <>
          <HintChips hints={SEARCH_DIMENSIONS} />
          {draft.trim().length > 0 &&
            draft.trim().length < MINIMUM_QUERY_LENGTH && (
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
                message="No matching archive records were found."
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
