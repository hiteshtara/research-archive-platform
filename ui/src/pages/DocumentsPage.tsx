import { SearchOutlined } from "@mui/icons-material";
import {
  Box,
  Card,
  CardContent,
  Checkbox,
  Chip,
  FormControl,
  FormControlLabel,
  InputAdornment,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import type { SelectChangeEvent } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { ApiRequestError, searchDocumentExplorer } from "../api/client";
import { EmptyState } from "../components/common/EmptyState";
import { ErrorState } from "../components/common/ErrorState";
import { LoadingState } from "../components/common/LoadingState";
import { PaginationFooter } from "../components/common/PaginationFooter";
import {
  DOCUMENT_EXPLORER_PRESETS,
  EXPLORER_MODULES,
  NORMALIZED_STATUSES,
  additionalRelationshipsLabel,
  documentSearchErrorMessage,
  documentSearchResultsCountLabel,
  explorerModuleLabel,
  isNavigable,
  moduleFacetLabel,
  normalizedStatusLabel,
  unitSourceLabel,
} from "../features/documents/documentsPresentation.mjs";
import type { DocumentExplorerResult } from "../types/api";

const PAGE_SIZE = 25;

const EMPTY_FILTERS = {
  query: "",
  module: "",
  normalizedStatus: "",
  nativeStatus: "",
  unitNumber: "",
  includeAnyUnit: false,
  personName: "",
  personRole: "",
  piOnly: false,
  sponsorCode: "",
  subrecipientOrganizationId: "",
  dateFrom: "",
  dateTo: "",
  sort: "",
};

// Kuali Document Explorer - the four approved core business-record
// modules only (Award, Proposal, Negotiation, Subaward). IRB is
// deliberately not offered as a filter and never contributes to
// counts, per docs/architecture/KUALI_DOCUMENT_EXPLORER_DESIGN.md §16
// decision 7. Entirely deterministic SQL - no AI/Bedrock/embedding
// involvement anywhere in this page or its API. Attachments are never
// shown here as documents; they remain reachable from the owning
// Award/Proposal/Negotiation/Subaward record itself.
export function DocumentsPage() {
  const navigate = useNavigate();

  const [draft, setDraft] = useState(EMPTY_FILTERS);
  const [applied, setApplied] = useState(EMPTY_FILTERS);
  const [page, setPage] = useState(0);

  const searchQuery = useQuery({
    queryKey: ["document-explorer", applied, page],
    queryFn: ({ signal }) =>
      searchDocumentExplorer({ ...applied, page, size: PAGE_SIZE }, signal),
  });

  function runSearch() {
    setApplied(draft);
    setPage(0);
  }

  function clearFilters() {
    setDraft(EMPTY_FILTERS);
    setApplied(EMPTY_FILTERS);
    setPage(0);
  }

  function applyPreset(filters: Record<string, string | boolean>) {
    const next = { ...EMPTY_FILTERS, ...filters };
    setDraft(next);
    setApplied(next);
    setPage(0);
  }

  function openResult(result: DocumentExplorerResult) {
    if (isNavigable(result)) {
      navigate(result.targetRoute as string);
    }
  }

  const data = searchQuery.data;

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h4">Kuali Documents</Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5 }}>
          Search archived workflow and business documents across Award,
          Proposal, Negotiation, and Subaward. Attachments are separate
          files reached from the owning record, not shown here.
        </Typography>
      </Box>

      <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap" }}>
        {DOCUMENT_EXPLORER_PRESETS.map((preset) => (
          <Chip
            key={preset.key}
            label={preset.label}
            variant="outlined"
            clickable
            onClick={() => applyPreset(preset.filters)}
          />
        ))}
      </Stack>

      <Card variant="outlined">
        <CardContent>
          <Stack spacing={2}>
            <TextField
              label="Search by document number, record number, title, person or sponsor"
              value={draft.query}
              onChange={(event) =>
                setDraft((current) => ({ ...current, query: event.target.value }))
              }
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  runSearch();
                }
              }}
              slotProps={{
                input: {
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchOutlined fontSize="small" />
                    </InputAdornment>
                  ),
                },
              }}
              fullWidth
            />

            <Stack direction="row" spacing={1.5} sx={{ flexWrap: "wrap" }}>
              <FormControl sx={{ minWidth: 160 }}>
                <InputLabel id="explorer-module-label">Module</InputLabel>
                <Select
                  labelId="explorer-module-label"
                  label="Module"
                  value={draft.module}
                  onChange={(event: SelectChangeEvent) =>
                    setDraft((current) => ({ ...current, module: event.target.value }))
                  }
                >
                  <MenuItem value="">All modules</MenuItem>
                  {EXPLORER_MODULES.map((moduleValue) => (
                    <MenuItem key={moduleValue} value={moduleValue}>
                      {explorerModuleLabel(moduleValue)}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>

              <FormControl sx={{ minWidth: 160 }}>
                <InputLabel id="explorer-status-label">Status</InputLabel>
                <Select
                  labelId="explorer-status-label"
                  label="Status"
                  value={draft.normalizedStatus}
                  onChange={(event: SelectChangeEvent) =>
                    setDraft((current) => ({
                      ...current,
                      normalizedStatus: event.target.value,
                    }))
                  }
                >
                  <MenuItem value="">Any status</MenuItem>
                  {NORMALIZED_STATUSES.map((status) => (
                    <MenuItem key={status} value={status}>
                      {normalizedStatusLabel(status)}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>

              <TextField
                label="Native status (exact)"
                value={draft.nativeStatus}
                onChange={(event) =>
                  setDraft((current) => ({ ...current, nativeStatus: event.target.value }))
                }
                sx={{ minWidth: 180 }}
              />

              <TextField
                label="Lead unit number"
                value={draft.unitNumber}
                onChange={(event) =>
                  setDraft((current) => ({ ...current, unitNumber: event.target.value }))
                }
                sx={{ minWidth: 160 }}
              />

              <FormControlLabel
                control={
                  <Checkbox
                    checked={draft.includeAnyUnit}
                    onChange={(event) =>
                      setDraft((current) => ({
                        ...current,
                        includeAnyUnit: event.target.checked,
                      }))
                    }
                  />
                }
                label="Include any associated unit"
              />
            </Stack>

            <Stack direction="row" spacing={1.5} sx={{ flexWrap: "wrap" }}>
              <TextField
                label="Person name"
                value={draft.personName}
                onChange={(event) =>
                  setDraft((current) => ({ ...current, personName: event.target.value }))
                }
                sx={{ minWidth: 180 }}
              />

              <TextField
                label="Person role"
                value={draft.personRole}
                onChange={(event) =>
                  setDraft((current) => ({ ...current, personRole: event.target.value }))
                }
                sx={{ minWidth: 160 }}
              />

              <FormControlLabel
                control={
                  <Checkbox
                    checked={draft.piOnly}
                    onChange={(event) =>
                      setDraft((current) => ({ ...current, piOnly: event.target.checked }))
                    }
                  />
                }
                label="PI only"
              />

              <TextField
                label="Sponsor code"
                value={draft.sponsorCode}
                onChange={(event) =>
                  setDraft((current) => ({ ...current, sponsorCode: event.target.value }))
                }
                sx={{ minWidth: 160 }}
              />

              <TextField
                label="Subrecipient organization"
                value={draft.subrecipientOrganizationId}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    subrecipientOrganizationId: event.target.value,
                  }))
                }
                sx={{ minWidth: 200 }}
              />
            </Stack>

            <Stack direction="row" spacing={1.5} sx={{ flexWrap: "wrap" }}>
              <TextField
                label="Date from"
                type="date"
                value={draft.dateFrom}
                onChange={(event) =>
                  setDraft((current) => ({ ...current, dateFrom: event.target.value }))
                }
                slotProps={{ inputLabel: { shrink: true } }}
                sx={{ minWidth: 160 }}
              />
              <TextField
                label="Date to"
                type="date"
                value={draft.dateTo}
                onChange={(event) =>
                  setDraft((current) => ({ ...current, dateTo: event.target.value }))
                }
                slotProps={{ inputLabel: { shrink: true } }}
                sx={{ minWidth: 160 }}
              />

              <FormControl sx={{ minWidth: 180 }}>
                <InputLabel id="explorer-sort-label">Sort by</InputLabel>
                <Select
                  labelId="explorer-sort-label"
                  label="Sort by"
                  value={draft.sort}
                  onChange={(event: SelectChangeEvent) =>
                    setDraft((current) => ({ ...current, sort: event.target.value }))
                  }
                >
                  <MenuItem value="">Document number</MenuItem>
                  <MenuItem value="title">Title</MenuItem>
                  <MenuItem value="documentDate">Date</MenuItem>
                  <MenuItem value="module">Module</MenuItem>
                </Select>
              </FormControl>
            </Stack>

            <Stack direction="row" spacing={1}>
              <Chip label="Search" color="primary" onClick={runSearch} clickable />
              <Chip label="Clear filters" variant="outlined" onClick={clearFilters} clickable />
            </Stack>
          </Stack>
        </CardContent>
      </Card>

      {searchQuery.isLoading && <LoadingState mode="spinner" />}

      {searchQuery.isError && (
        <ErrorState
          message={documentSearchErrorMessage(
            searchQuery.error instanceof ApiRequestError
              ? searchQuery.error.status
              : undefined,
          )}
        />
      )}

      {data && (
        <Stack spacing={2}>
          <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", alignItems: "center" }}>
            <Typography variant="overline" color="text.secondary">
              {documentSearchResultsCountLabel(data.results.totalElements)}
            </Typography>
            {data.moduleFacets.map((facet) => (
              <Chip key={facet.value} label={moduleFacetLabel(facet)} size="small" />
            ))}
          </Stack>

          {data.results.content.length === 0 && (
            <EmptyState
              variant="text"
              message="No documents match these filters."
            />
          )}

          <Stack spacing={1.25}>
            {data.results.content.map((result) => (
              <Card
                key={`${result.module}-${result.documentNumber}`}
                variant="outlined"
                sx={{
                  cursor: isNavigable(result) ? "pointer" : "default",
                  transition: "border-color .12s ease",
                  "&:hover": isNavigable(result)
                    ? { borderColor: "primary.main" }
                    : undefined,
                }}
                role={isNavigable(result) ? "button" : undefined}
                tabIndex={isNavigable(result) ? 0 : undefined}
                onClick={() => openResult(result)}
                onKeyDown={(event) => {
                  if (
                    isNavigable(result) &&
                    (event.key === "Enter" || event.key === " ")
                  ) {
                    event.preventDefault();
                    openResult(result);
                  }
                }}
              >
                <CardContent sx={{ "&:last-child": { pb: 2 } }}>
                  <Stack
                    direction="row"
                    spacing={1}
                    sx={{ alignItems: "center", flexWrap: "wrap" }}
                  >
                    <Chip
                      label={explorerModuleLabel(result.module)}
                      size="small"
                      color="primary"
                      variant="outlined"
                    />
                    <Typography sx={{ fontWeight: 700 }}>
                      Document {result.documentNumber}
                    </Typography>
                    <Chip
                      label={normalizedStatusLabel(result.normalizedStatus)}
                      size="small"
                    />
                    {result.nativeStatusDescription && (
                      <Typography variant="caption" color="text.secondary">
                        ({result.nativeStatusDescription})
                      </Typography>
                    )}
                  </Stack>

                  <Typography
                    variant="body2"
                    color="text.secondary"
                    sx={{ mt: 0.5 }}
                  >
                    {result.title ?? "Untitled"}
                  </Typography>

                  <Typography
                    variant="caption"
                    color="text.secondary"
                    sx={{ display: "block", mt: 0.25 }}
                  >
                    Record {result.businessRecordNumber}
                    {result.versionOrSequence
                      ? ` · version ${result.versionOrSequence}`
                      : ""}
                    {result.documentDate ? ` · ${result.documentDate}` : ""}
                  </Typography>

                  {result.leadUnitNumber && (
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      sx={{ display: "block", mt: 0.25 }}
                    >
                      {unitSourceLabel(result.module)}: {result.leadUnitName ?? result.leadUnitNumber}
                      {" "}
                      {additionalRelationshipsLabel(result.unitCount, "unit")}
                    </Typography>
                  )}

                  {result.primaryPersonName && (
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      sx={{ display: "block", mt: 0.25 }}
                    >
                      {result.primaryPersonName}
                      {result.primaryPersonRole ? ` — ${result.primaryPersonRole}` : ""}
                      {" "}
                      {additionalRelationshipsLabel(result.personCount - 1, "person")}
                    </Typography>
                  )}

                  {result.sponsorName && (
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      sx={{ display: "block", mt: 0.25 }}
                    >
                      Sponsor: {result.sponsorName}
                      {" "}
                      {additionalRelationshipsLabel(result.sponsorCount - 1, "sponsor")}
                    </Typography>
                  )}

                  {result.subrecipientOrganizationId && (
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      sx={{ display: "block", mt: 0.25 }}
                    >
                      Subrecipient organization: {result.subrecipientOrganizationName ?? result.subrecipientOrganizationId}
                    </Typography>
                  )}
                </CardContent>
              </Card>
            ))}
          </Stack>

          <PaginationFooter
            totalPages={data.results.totalPages}
            page={page}
            onPageChange={setPage}
          />
        </Stack>
      )}
    </Stack>
  );
}
