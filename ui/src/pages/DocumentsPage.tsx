import { SearchOutlined } from "@mui/icons-material";
import {
  Box,
  Card,
  CardContent,
  Chip,
  FormControl,
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

import { ApiRequestError, searchDocuments } from "../api/client";
import { EmptyState } from "../components/common/EmptyState";
import { ErrorState } from "../components/common/ErrorState";
import { LoadingState } from "../components/common/LoadingState";
import { PaginationFooter } from "../components/common/PaginationFooter";
import {
  MODULES,
  documentSearchErrorMessage,
  documentSearchResultsCountLabel,
  isNavigable,
  moduleLabel,
} from "../features/documents/documentsPresentation.mjs";
import type { DocumentSearchResult } from "../types/api";

const PAGE_SIZE = 25;

// Kuali Document Search - the five approved core business-record
// modules only (Award, Proposal, Negotiation, Subaward, IRB), per
// docs/architecture/KUALI_DOCUMENT_METRIC_INVESTIGATION.md. Attachments
// are never shown here as documents; they remain reachable from the
// owning Award/Proposal/Negotiation/Subaward/IRB record itself. Lists
// all documents by default (like Award Search's own "blank query lists
// everything" behavior) rather than requiring a filter before showing
// anything.
export function DocumentsPage() {
  const navigate = useNavigate();

  const [documentNumber, setDocumentNumber] = useState("");
  const [module, setModule] = useState("");
  const [title, setTitle] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);

  const [appliedFilters, setAppliedFilters] = useState({
    documentNumber: "",
    module: "",
    title: "",
    status: "",
  });

  const searchQuery = useQuery({
    queryKey: ["document-search", appliedFilters, page],
    queryFn: ({ signal }) =>
      searchDocuments(
        { ...appliedFilters, page, size: PAGE_SIZE },
        signal,
      ),
  });

  function runSearch() {
    setAppliedFilters({
      documentNumber: documentNumber.trim(),
      module,
      title: title.trim(),
      status: status.trim(),
    });
    setPage(0);
  }

  function handleModuleChange(event: SelectChangeEvent) {
    setModule(event.target.value);
  }

  function openResult(result: DocumentSearchResult) {
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
          Search archived workflow and business documents across all
          modules. Attachments are separate files reached from the
          owning record, not shown here.
        </Typography>
      </Box>

      <Card variant="outlined">
        <CardContent>
          <Stack spacing={2}>
            <Stack
              direction={{ xs: "column", sm: "row" }}
              spacing={1.5}
              sx={{ flexWrap: "wrap" }}
            >
              <TextField
                label="Document number"
                value={documentNumber}
                onChange={(event) => setDocumentNumber(event.target.value)}
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
                sx={{ minWidth: 220, flex: 1 }}
              />

              <FormControl sx={{ minWidth: 180 }}>
                <InputLabel id="document-search-module-label">
                  Module
                </InputLabel>
                <Select
                  labelId="document-search-module-label"
                  label="Module"
                  value={module}
                  onChange={handleModuleChange}
                >
                  <MenuItem value="">All modules</MenuItem>
                  {MODULES.map((moduleValue) => (
                    <MenuItem key={moduleValue} value={moduleValue}>
                      {moduleLabel(moduleValue)}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>

              <TextField
                label="Title"
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    runSearch();
                  }
                }}
                sx={{ minWidth: 180, flex: 1 }}
              />

              <TextField
                label="Status"
                value={status}
                onChange={(event) => setStatus(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    runSearch();
                  }
                }}
                sx={{ minWidth: 160 }}
              />
            </Stack>

            <Stack direction="row">
              <Chip
                label="Search"
                color="primary"
                onClick={runSearch}
                clickable
                sx={{ px: 1 }}
              />
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
          <Typography variant="overline" color="text.secondary">
            {documentSearchResultsCountLabel(data.totalElements)}
          </Typography>

          {data.content.length === 0 && (
            <EmptyState
              variant="text"
              message="No documents match these filters."
            />
          )}

          <Stack spacing={1.25}>
            {data.content.map((result) => (
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
                      label={moduleLabel(result.module)}
                      size="small"
                      color="primary"
                      variant="outlined"
                    />
                    <Typography sx={{ fontWeight: 700 }}>
                      Document {result.documentNumber}
                    </Typography>
                    {result.status && (
                      <Chip label={result.status} size="small" />
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
                    {result.relevantDate ? ` · ${result.relevantDate}` : ""}
                  </Typography>
                </CardContent>
              </Card>
            ))}
          </Stack>

          <PaginationFooter
            totalPages={data.totalPages}
            page={page}
            onPageChange={setPage}
          />
        </Stack>
      )}
    </Stack>
  );
}
