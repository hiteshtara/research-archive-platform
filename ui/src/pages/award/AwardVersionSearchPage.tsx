import { Box, Chip, Link, Stack, TextField, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { Link as RouterLink } from "react-router-dom";

import { searchAwardVersionsV1 } from "../../api/client";
import { EmptyState } from "../../components/common/EmptyState";
import { PaginationFooter } from "../../components/common/PaginationFooter";
import { StatusPill } from "../../components/common/StatusPill";
import { FilteredSearchBar } from "../../components/common/search/FilteredSearchBar";
import { ResultCard } from "../../components/common/search/ResultCard";
import { ResultCount } from "../../components/common/search/ResultCount";
import { SearchPageLayout } from "../../components/common/search/SearchPageLayout";
import {
  InitialSearchHint,
  SearchStates,
} from "../../components/common/search/SearchStates";
import { emptyResultsMessage } from "../../features/common/filterPresentation.mjs";
import type { FilterErrors } from "../../features/common/filterPresentation.mjs";
import {
  INITIAL_SEARCH_HINT,
  resolveSearchState,
  searchErrorMessage,
} from "../../features/common/searchPresentation.mjs";
import { AWARD_WILDCARD_HINT } from "../../features/award/awardSearchPresentation.mjs";
import {
  startsVersionSearch,
  describeVersionSearchResults,
  isValidAwardIdInput,
  versionCurrentLabel,
  versionDetailPath,
} from "../../features/award/awardVersionSearchPresentation.mjs";
import {
  AWARD_VERSION_DATE_RANGES,
  AWARD_VERSION_FILTER_FIELDS,
  AWARD_VERSION_SORT_OPTIONS,
} from "../../features/search/searchFilterFields.mjs";
import type { AwardVersionFilterKey } from "../../features/search/searchFilterFields.d.mts";
import { useFilteredSearch } from "../../hooks/useFilteredSearch";
import type { ExtraParamDefinition } from "../../hooks/useFilteredSearch";

const PAGE_SIZE = 25;

// Sort is ordering, not a filter: it is never counted or chipped, and
// changing it applies immediately (from page 1).
const EXTRA: readonly ExtraParamDefinition[] = [
  { key: "sort", defaultValue: "sequence", allowed: ["sequence", "date"] },
];

// Award ID is an exact numeric identifier, never a partial/substring
// search - validated before it is applied so an obviously bad value never
// reaches the API (which validates independently too).
function validateAwardId(
  filters: Record<AwardVersionFilterKey, string>,
): FilterErrors<AwardVersionFilterKey> {
  const value = filters.awardId ?? "";
  return value.trim().length > 0 && !isValidAwardIdInput(value)
    ? { awardId: "Award ID must be a whole number." }
    : {};
}

// Historical Award Records explorer: one result per award_id (a specific
// version), never scoped to the current version - the version-level
// counterpart to AwardSearchPage. The exact identifiers, the Versions
// filter and the Award attribute filters share the one filter panel;
// applied state lives in the URL, so Back/Forward restores the exact
// search that was active.
export function AwardVersionSearchPage() {
  const search = useFilteredSearch<AwardVersionFilterKey>({
    fields: AWARD_VERSION_FILTER_FIELDS,
    dateRanges: AWARD_VERSION_DATE_RANGES,
    extra: EXTRA,
    extraValidate: validateAwardId,
  });
  const { appliedQuery, appliedActiveFilters, appliedExtra, page } = search;
  const sort = appliedExtra.sort === "date" ? "date" : "sequence";

  // The Versions filter narrows but never starts a search on its own:
  // "historical" alone would list every archived version.
  const { versionFilter, awardNumber, documentNumber, awardId, ...attributeFilters } =
    appliedActiveFilters;
  // A hand-edited or old link can carry a non-numeric Award ID that never
  // went through the panel's validation: never send it, say why instead.
  const appliedAwardIdError = validateAwardId(search.appliedFilters).awardId;
  const hasSearched = startsVersionSearch({
    appliedQuery,
    appliedActiveFilters,
    awardIdError: appliedAwardIdError,
  });

  const searchQuery = useQuery({
    queryKey: ["award-version-search-v1", appliedQuery, appliedActiveFilters, sort, page],
    queryFn: ({ signal }) =>
      searchAwardVersionsV1(
        {
          q: appliedQuery,
          awardNumber,
          documentNumber,
          awardId,
          versionFilter:
            versionFilter === "current" || versionFilter === "historical"
              ? versionFilter
              : "all",
          sort,
          page,
          size: PAGE_SIZE,
          filters: attributeFilters,
        },
        signal,
      ),
    enabled: hasSearched,
  });

  const { totalElements, totalPages, content } =
    describeVersionSearchResults(searchQuery.data);

  return (
    <SearchPageLayout
      title="Search Historical Awards"
      // The free-text box here runs through the same AwardSearchPattern
      // as Awards (AwardArchiveService.searchVersions), so the wildcard
      // guidance is true on this page too and belongs on it - a page
      // where the capability works but is never explained is exactly
      // the TC-009 defect.
      subtitle={`Each result is an individual archived Award version, not a family or current-record summary - every historical sequence is searchable, including by its exact internal Award ID. Selecting a result opens that exact version. ${AWARD_WILDCARD_HINT}`}
      search={
        <FilteredSearchBar
          search={search}
          fields={AWARD_VERSION_FILTER_FIELDS}
          placeholder="Title, sponsor, PI, or lead unit..."
          ariaLabel="Search Historical Award Records"
          panelId="award-version-filters"
        />
      }
      belowSearch={
        <>
          {appliedAwardIdError && (
            <Typography role="alert" variant="body2" color="error" sx={{ mt: 2 }}>
              {appliedAwardIdError} Correct it in Filters to search.
            </Typography>
          )}
          {/* QA TC-015, with this page's own wrinkle: hasSearched
              deliberately ignores versionFilter, because it carries a
              default of "all" and would otherwise make the page look
              permanently searched. So choosing only a Versions value
              submits nothing - and said nothing. The agreed sentence is
              kept verbatim; the second sentence is specific to this
              page and is why the copy is not shared from
              INITIAL_SEARCH_HINT alone. */}
          {!hasSearched && !appliedAwardIdError && (
            <Box sx={{ mt: 2.5 }}>
              <InitialSearchHint
                message={`${INITIAL_SEARCH_HINT} The Versions choice narrows a search; it does not start one.`}
              />
            </Box>
          )}

          <Typography variant="body2" color="text.secondary" sx={{ mt: 2.5 }}>
            Looking for the current record for an Award number instead?{" "}
            <Link component={RouterLink} to="/awards/search">
              Use Awards
            </Link>
            .
          </Typography>
        </>
      }
    >
      <SearchStates
        state={resolveSearchState({
          hasSearched,
          isLoading: searchQuery.isLoading,
          isError: searchQuery.isError,
          resultCount: content.length,
        })}
        errorMessage={searchErrorMessage(
          searchQuery.error,
          "Unable to search Historical Award Records right now. Try again in a moment.",
        )}
      >
        {searchQuery.data && (
          <>
            <Stack
              direction={{ xs: "column", sm: "row" }}
              spacing={1}
              sx={{ justifyContent: "space-between", alignItems: { sm: "center" } }}
            >
              <ResultCount total={totalElements} singular="version" />
              <Stack direction="row" spacing={1} sx={{ alignItems: "center", mb: 1.5 }}>
                <Typography
                  component="label"
                  htmlFor="award-version-sort"
                  variant="body2"
                  sx={{ fontWeight: 600, whiteSpace: "nowrap" }}
                >
                  Sort by
                </Typography>
                <TextField
                  select
                  size="small"
                  id="award-version-sort"
                  value={sort}
                  onChange={(event) => search.setExtra("sort", event.target.value)}
                  slotProps={{ select: { native: true } }}
                >
                  {AWARD_VERSION_SORT_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </TextField>
              </Stack>
            </Stack>

            {content.length === 0 && (
              <EmptyState
                variant="text"
                message={emptyResultsMessage({
                  noun: "Award versions",
                  query: appliedQuery,
                  filterCount: search.appliedCount,
                })}
              />
            )}

            <Stack spacing={1.25}>
              {content.map((hit) => (
                <ResultCard
                  key={hit.awardId}
                  to={versionDetailPath(hit)}
                  identifier={hit.awardNumber}
                  secondaryIdentifier={
                    <>
                      <Chip
                        size="small"
                        label={`Seq ${hit.sequenceNumber ?? "—"}`}
                      />
                      <Chip
                        size="small"
                        color={hit.primaryCurrent ? "success" : "default"}
                        label={versionCurrentLabel(hit)}
                      />
                    </>
                  }
                  status={<StatusPill status={hit.status} domain="award" />}
                  title={hit.title ?? "Untitled award"}
                  metadata={
                    <>
                      {[hit.sponsor, hit.principalInvestigator, hit.leadUnit]
                        .filter(Boolean)
                        .join(" · ") || "—"}
                      {hit.documentNumber ? ` · Doc ${hit.documentNumber}` : ""}
                    </>
                  }
                  rightSlot={
                    <Typography variant="caption" color="text.secondary">
                      {hit.updateTimestamp ?? hit.awardEffectiveDate ?? "—"}
                    </Typography>
                  }
                />
              ))}
            </Stack>

            <Box sx={{ mt: 3 }}>
              <PaginationFooter
                totalPages={totalPages}
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
