import { Box, Stack } from "@mui/material";
import type { ReactNode } from "react";

import { buildFilterChips } from "../../../features/common/filterPresentation.mjs";
import { canSubmitSearchText } from "../../../features/common/searchPresentation.mjs";
import type { FilterFieldDefinition } from "../../../features/common/filterPresentation.mjs";
import type { FilteredSearchState } from "../../../hooks/useFilteredSearch";
import { FilterChips } from "../FilterChips";
import { FilterPanel, FilterToggleButton } from "../FilterPanel";
import { SearchBox } from "./SearchBox";

/**
 * Search input + Filters toggle, the expandable panel beneath them, and
 * the applied-filter chips - the one search header every archive search
 * page renders, laid out exactly like the Negotiations reference.
 *
 * `displayFields` lets a page show a subset of its (stable) fields - e.g.
 * Archived File Finder hides identifiers that do not apply to the chosen
 * record type - without changing the fields the state is built from.
 *
 * `showSearchBox={false}` is for identifier-only pages with no free-text
 * search (Archived File Finder); the toggle and panel remain.
 */
export function FilteredSearchBar<Key extends string>({
  search,
  fields,
  displayFields,
  placeholder,
  ariaLabel,
  showSearchBox = true,
  panelId = "filter-panel",
  onSubmit,
  belowChips,
  chipFields,
}: {
  search: FilteredSearchState<Key>;
  fields: readonly FilterFieldDefinition<Key>[];
  displayFields?: readonly FilterFieldDefinition<Key>[];
  placeholder?: string;
  ariaLabel?: string;
  showSearchBox?: boolean;
  panelId?: string;
  /** Overrides apply() for the search box, e.g. a minimum query length. */
  onSubmit?: () => void;
  belowChips?: ReactNode;
  /**
   * Labels for the APPLIED filters' chips, when they depend on applied
   * state (Archived File Finder's "Award Number" vs "Proposal Number").
   */
  chipFields?: readonly FilterFieldDefinition<Key>[];
}) {
  /*
   * Length is enforced here rather than inside the search box, because
   * this one handler is what both ways of running a search call: Enter
   * in the box, and Apply Filters in the panel below it (QA TC-018).
   * Guarding only the box left Apply Filters able to submit a query the
   * box itself was refusing - the same search, allowed or refused
   * depending on which control you happened to use.
   *
   * A page's own onSubmit is wrapped too, so a page cannot opt out of
   * the limit by supplying one.
   */
  const runSearch = onSubmit ?? (() => void search.apply());
  const queryTooLong = !canSubmitSearchText(search.draftQuery);
  const submit = () => {
    if (queryTooLong) {
      return;
    }
    runSearch();
  };
  const chips = chipFields
    ? buildFilterChips(search.appliedFilters, chipFields)
    : search.chips;

  return (
    <>
      <Stack
        direction={{ xs: "column", sm: "row" }}
        spacing={1.5}
        sx={{
          alignItems: { xs: "stretch", sm: "center" },
          justifyContent: showSearchBox ? undefined : "center",
        }}
      >
        {showSearchBox && (
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <SearchBox
              value={search.draftQuery}
              onChange={search.setDraftQuery}
              onSubmit={submit}
              placeholder={placeholder}
              ariaLabel={ariaLabel}
            />
          </Box>
        )}
        <FilterToggleButton
          open={search.panelOpen}
          activeCount={search.appliedCount}
          onClick={search.togglePanel}
          panelId={panelId}
        />
      </Stack>

      <FilterPanel
        open={search.panelOpen}
        fields={displayFields ?? fields}
        values={search.draftFilters}
        onChange={search.changeFilter}
        onApply={submit}
        onClearAll={search.clearAll}
        errors={search.errors}
        applyDisabled={!search.canApply || queryTooLong}
        hasUnappliedChanges={search.hasUnappliedChanges}
        panelId={panelId}
      />

      <FilterChips
        chips={chips}
        onRemove={search.removeAppliedFilter}
        onClearAll={search.clearAll}
      />

      {belowChips}
    </>
  );
}
