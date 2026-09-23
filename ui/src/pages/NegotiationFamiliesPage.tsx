import { ArrowForwardOutlined, SearchOutlined } from "@mui/icons-material";
import {
  Box,
  Card,
  CardContent,
  InputAdornment,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { getNegotiations } from "../api/client";
import { EmptyState } from "../components/common/EmptyState";
import { ErrorState } from "../components/common/ErrorState";
import { FilterChips } from "../components/common/FilterChips";
import {
  FilterPanel,
  FilterToggleButton,
} from "../components/common/FilterPanel";
import { LoadingState } from "../components/common/LoadingState";
import { PaginationFooter } from "../components/common/PaginationFooter";
import { StatusPill } from "../components/common/StatusPill";
import {
  NEGOTIATION_FILTER_FIELDS,
  buildNegotiationFilterChips,
  buildNegotiationPath,
  buildNegotiationSearchParams,
  clearNegotiationFilters,
  countActiveNegotiationFilters,
  describeNegotiationResults,
  emptyNegotiationFilters,
  formatLeadUnit,
  removeNegotiationFilter,
} from "../features/negotiation/negotiationSearchPresentation.mjs";
import type {
  NegotiationFilterKey,
  NegotiationFilters,
} from "../features/negotiation/negotiationSearchPresentation.d.mts";

const pageSize = 25;

function display(value: string | number | null) {
  return value ?? "—";
}

export function NegotiationFamiliesPage() {
  const navigate = useNavigate();

  const [search, setSearch] = useState("");
  const [appliedSearch, setAppliedSearch] = useState("");

  // `filters` is what the panel is editing; `appliedFilters` is what the
  // last search actually used. Keeping them apart means typing in the
  // panel does not fire a request per keystroke, and the chips always
  // describe the result set on screen rather than a pending edit.
  const [filters, setFilters] = useState<NegotiationFilters>(
    emptyNegotiationFilters(),
  );
  const [appliedFilters, setAppliedFilters] = useState<NegotiationFilters>(
    emptyNegotiationFilters(),
  );

  const [filtersOpen, setFiltersOpen] = useState(false);
  const [page, setPage] = useState(0);

  const searchParameters = buildNegotiationSearchParams({
    query: appliedSearch,
    filters: appliedFilters,
    page,
    size: pageSize,
  });

  const query = useQuery({
    queryKey: ["negotiations", searchParameters],
    queryFn: ({ signal }) => getNegotiations(searchParameters, signal),
  });

  const chips = buildNegotiationFilterChips(appliedFilters);
  const activeCount = countActiveNegotiationFilters(filters);

  const applySearch = () => {
    setAppliedSearch(search.trim());
    setAppliedFilters(filters);
    setPage(0);
  };

  const changeFilter = (key: NegotiationFilterKey, value: string) => {
    setFilters((current) => ({ ...current, [key]: value }));
  };

  // Removing a chip is an immediate action on the applied result set, so
  // it drops the filter from both the panel and the live search at once.
  const removeChip = (key: NegotiationFilterKey) => {
    const next = removeNegotiationFilter(appliedFilters, key);
    setFilters(next);
    setAppliedFilters(next);
    setPage(0);
  };

  const clearAll = () => {
    const cleared = clearNegotiationFilters();
    setFilters(cleared);
    setAppliedFilters(cleared);
    setPage(0);
  };

  return (
    <Stack spacing={3}>
      <Card>
        <CardContent>
          <Typography variant="h4" sx={{ fontWeight: 700 }}>
            Negotiations
          </Typography>

          <Typography color="text.secondary" sx={{ mt: 1 }}>
            Search archived agreements and their associated Kuali records.
          </Typography>

          <Stack
            direction={{ xs: "column", sm: "row" }}
            spacing={1.5}
            sx={{ mt: 3, alignItems: { xs: "stretch", sm: "center" } }}
          >
            <TextField
              fullWidth
              placeholder="Negotiation ID, title, status, negotiator, PI, sponsor, lead unit..."
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  applySearch();
                }
              }}
              slotProps={{
                input: {
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchOutlined />
                    </InputAdornment>
                  ),
                },
              }}
            />

            <FilterToggleButton
              open={filtersOpen}
              activeCount={activeCount}
              onClick={() => setFiltersOpen((open) => !open)}
            />
          </Stack>

          <FilterPanel
            open={filtersOpen}
            fields={NEGOTIATION_FILTER_FIELDS}
            values={filters}
            onChange={changeFilter}
            onApply={applySearch}
            onClearAll={clearAll}
          />

          <FilterChips
            chips={chips}
            onRemove={removeChip}
            onClearAll={clearAll}
          />
        </CardContent>
      </Card>

      {query.isLoading && <LoadingState mode="spinner" />}

      {query.isError && (
        <ErrorState message="Unable to load Negotiations right now. Try again in a moment." />
      )}

      {query.data && (
        <Stack spacing={1.25}>
          <Typography variant="body2" color="text.secondary">
            {describeNegotiationResults({
              totalElements: query.data.totalElements,
              page: query.data.page,
              size: query.data.size,
              count: query.data.content.length,
            })}
          </Typography>

          {query.data.content.length === 0 ? (
            <EmptyState message="No matching Negotiations were found." />
          ) : (
            query.data.content.map((negotiation) => {
              const leadUnit = formatLeadUnit(
                negotiation.leadUnitName,
                negotiation.leadUnitNumber,
              );
              const path = buildNegotiationPath(negotiation.negotiationId);

              return (
                <Card
                  key={negotiation.negotiationId}
                  variant="outlined"
                  sx={{
                    cursor: "pointer",
                    "&:hover": { borderColor: "primary.main" },
                  }}
                  role="button"
                  tabIndex={0}
                  onClick={() => navigate(path)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      navigate(path);
                    }
                  }}
                >
                  <CardContent sx={{ "&:last-child": { pb: 2 } }}>
                    <Stack
                      direction="row"
                      spacing={2}
                      sx={{ alignItems: "flex-start" }}
                    >
                      <Box sx={{ minWidth: 0, flexGrow: 1 }}>
                        <Stack
                          direction="row"
                          spacing={1}
                          sx={{ alignItems: "center", flexWrap: "wrap" }}
                        >
                          <Typography sx={{ fontWeight: 700 }}>
                            {negotiation.negotiationId}
                          </Typography>
                          <StatusPill
                            status={negotiation.negotiationStatusDescription}
                            domain="negotiation"
                          />
                          <Typography
                            variant="body2"
                            color="text.secondary"
                            sx={{ whiteSpace: "nowrap" }}
                          >
                            {display(
                              negotiation.negotiationAgreementTypeDescription,
                            )}
                          </Typography>
                        </Stack>

                        <Typography sx={{ mt: 0.5 }}>
                          {display(negotiation.title)}
                        </Typography>

                        <Typography variant="body2" color="text.secondary">
                          {display(negotiation.principalInvestigatorName)}
                          {negotiation.sponsorName
                            ? ` · ${negotiation.sponsorName}`
                            : ""}
                        </Typography>

                        {/*
                          Lead Unit: BU explicitly asked for the actual
                          unit name as the primary value, with the number
                          as secondary metadata - never the number alone.
                        */}
                        <Typography variant="body2" sx={{ mt: 0.5 }}>
                          {leadUnit.primary}
                        </Typography>
                        {leadUnit.secondary && (
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            component="div"
                          >
                            {leadUnit.secondary}
                          </Typography>
                        )}

                        <Typography
                          variant="caption"
                          color="text.secondary"
                          component="div"
                          sx={{ mt: 0.5 }}
                        >
                          Negotiator {display(negotiation.negotiatorFullName)}
                          {negotiation.negotiationStartDate
                            ? ` · Started ${negotiation.negotiationStartDate}`
                            : ""}
                        </Typography>
                      </Box>

                      <ArrowForwardOutlined color="action" />
                    </Stack>
                  </CardContent>
                </Card>
              );
            })
          )}

          {query.data.totalPages > 1 && (
            <Box sx={{ mt: 2 }}>
              <PaginationFooter
                page={query.data.page}
                totalPages={query.data.totalPages}
                onPageChange={setPage}
              />
            </Box>
          )}
        </Stack>
      )}
    </Stack>
  );
}
