import { DownloadOutlined, SearchOutlined, VisibilityOutlined } from "@mui/icons-material";
import {
  Box,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  FormControl,
  IconButton,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import type { SelectChangeEvent } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";

import {
  ApiRequestError,
  downloadAwardAttachmentV1,
  searchArchivedFiles,
} from "../api/client";
import { EmptyState } from "../components/common/EmptyState";
import { ErrorState } from "../components/common/ErrorState";
import { LoadingState } from "../components/common/LoadingState";
import { PaginationFooter } from "../components/common/PaginationFooter";
import { formatByteSize } from "../features/award/awardSectionsPresentation.mjs";
import {
  archivedFileResultKey,
  archivedFileResultsCountLabel,
  archivedFileSearchErrorMessage,
  formatSourceDateLabel,
  hasAnyIdentifierSupplied,
  resolveAvailabilityChipColor,
} from "../features/archivedFiles/archivedFileFinderPresentation.mjs";
import type { ArchivedFileSearchResult } from "../types/api";

const PAGE_SIZE = 25;

const EMPTY_FILTERS = {
  awardNumber: "",
  documentNumber: "",
  awardId: "",
  attachmentId: "",
  fileId: "",
  versionFilter: "all" as "all" | "current" | "historical",
};

// Archived File Finder (Phase 1: Award only, exact-identifier only) -
// finds archived attachment FILES via GET /api/v1/attachments/search,
// deliberately separate from Kuali Documents (DocumentsPage), which
// searches business RECORDS by free-text query and never touches
// attachment tables. Phase 1 is available only under the application's
// existing authenticated archive-staff access model - the same flat
// "any authenticated user" rule every other page already uses; this is
// not a researcher/PI-facing access tier.
export function ArchivedFileFinderPage() {
  const navigate = useNavigate();

  const [draft, setDraft] = useState(EMPTY_FILTERS);
  const [applied, setApplied] = useState(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [downloadingKey, setDownloadingKey] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const hasSearched = hasAnyIdentifierSupplied(applied);

  const searchQuery = useQuery({
    queryKey: ["archived-file-finder", applied, page],
    queryFn: ({ signal }) =>
      searchArchivedFiles({ ...applied, page, size: PAGE_SIZE }, signal),
    enabled: hasSearched,
  });

  function runSearch() {
    if (!hasAnyIdentifierSupplied(draft)) {
      setValidationError(
        "Enter at least one identifier (Award number, workflow document " +
          "number, Award ID, Attachment ID, or File ID) before searching.",
      );
      return;
    }
    setValidationError(null);
    setApplied(draft);
    setPage(0);
  }

  function clearFilters() {
    setDraft(EMPTY_FILTERS);
    setApplied(EMPTY_FILTERS);
    setValidationError(null);
    setPage(0);
  }

  async function handleDownload(result: ArchivedFileSearchResult) {
    if (result.parentId === null || result.attachmentId === null) {
      return;
    }
    const key = archivedFileResultKey(result);
    setDownloadError(null);
    setDownloadingKey(key);
    try {
      await downloadAwardAttachmentV1(
        result.parentId,
        result.attachmentId,
        result.fileName ?? "attachment",
      );
    } catch (error) {
      setDownloadError(
        error instanceof Error ? error.message : "Download failed.",
      );
    } finally {
      setDownloadingKey(null);
    }
  }

  function viewAward(result: ArchivedFileSearchResult) {
    if (result.parentId !== null) {
      navigate(`/awards/${result.parentId}`);
    }
  }

  const data = searchQuery.data;

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h4">Archived File Finder</Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5 }}>
          Search for archived Award attachment files by exact identifier.
          This is separate from Kuali Documents, which searches business
          records rather than files.
        </Typography>
      </Box>

      <Card variant="outlined">
        <CardContent>
          <Stack spacing={2}>
            <Stack direction="row" spacing={1.5} sx={{ flexWrap: "wrap" }}>
              <TextField
                label="Award number"
                value={draft.awardNumber}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    awardNumber: event.target.value,
                  }))
                }
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    runSearch();
                  }
                }}
                sx={{ minWidth: 180 }}
                slotProps={{
                  input: {
                    startAdornment: (
                      <SearchOutlined fontSize="small" sx={{ mr: 1, color: "action.active" }} />
                    ),
                  },
                }}
              />

              <TextField
                label="Workflow document number"
                value={draft.documentNumber}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    documentNumber: event.target.value,
                  }))
                }
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    runSearch();
                  }
                }}
                sx={{ minWidth: 200 }}
              />

              <TextField
                label="Award ID"
                value={draft.awardId}
                onChange={(event) =>
                  setDraft((current) => ({ ...current, awardId: event.target.value }))
                }
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    runSearch();
                  }
                }}
                sx={{ minWidth: 140 }}
              />

              <TextField
                label="Attachment ID"
                value={draft.attachmentId}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    attachmentId: event.target.value,
                  }))
                }
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    runSearch();
                  }
                }}
                sx={{ minWidth: 140 }}
              />

              <TextField
                label="File ID"
                value={draft.fileId}
                onChange={(event) =>
                  setDraft((current) => ({ ...current, fileId: event.target.value }))
                }
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    runSearch();
                  }
                }}
                sx={{ minWidth: 140 }}
              />

              <FormControl sx={{ minWidth: 160 }}>
                <InputLabel id="archived-file-version-filter-label">
                  Version
                </InputLabel>
                <Select
                  labelId="archived-file-version-filter-label"
                  label="Version"
                  value={draft.versionFilter}
                  onChange={(event: SelectChangeEvent) =>
                    setDraft((current) => ({
                      ...current,
                      versionFilter: event.target.value as
                        | "all"
                        | "current"
                        | "historical",
                    }))
                  }
                >
                  <MenuItem value="all">All versions</MenuItem>
                  <MenuItem value="current">Current version only</MenuItem>
                  <MenuItem value="historical">Historical versions only</MenuItem>
                </Select>
              </FormControl>
            </Stack>

            <Stack direction="row" spacing={1}>
              <Chip label="Search" color="primary" onClick={runSearch} clickable />
              <Chip label="Clear filters" variant="outlined" onClick={clearFilters} clickable />
            </Stack>

            {validationError && <ErrorState message={validationError} />}
          </Stack>
        </CardContent>
      </Card>

      {!hasSearched && !validationError && (
        <EmptyState
          variant="text"
          message="Enter at least one identifier above and select Search to find archived files."
        />
      )}

      {hasSearched && (
        <>
          {searchQuery.isLoading && <LoadingState mode="spinner" />}

          {searchQuery.isError && (
            <ErrorState
              message={archivedFileSearchErrorMessage(
                searchQuery.error instanceof ApiRequestError
                  ? searchQuery.error.status
                  : undefined,
              )}
            />
          )}

          {downloadError && <ErrorState message={downloadError} onClose={() => setDownloadError(null)} />}

          {data && (
            <Stack spacing={2}>
              <Typography variant="overline" color="text.secondary">
                {archivedFileResultsCountLabel(data.totalElements)}
              </Typography>

              {data.content.length === 0 && (
                <EmptyState
                  variant="text"
                  message="No archived files match these identifiers."
                />
              )}

              <Stack spacing={1.25}>
                {data.content.map((result) => {
                  const key = archivedFileResultKey(result);
                  const isDownloading = downloadingKey === key;
                  const canDownload =
                    result.downloadable && result.attachmentId !== null;
                  const canViewAward = result.parentId !== null;

                  return (
                    <Card key={key} variant="outlined">
                      <CardContent
                        sx={{
                          display: "flex",
                          justifyContent: "space-between",
                          alignItems: "center",
                          gap: 2,
                          "&:last-child": { pb: 2 },
                        }}
                      >
                        <Box sx={{ minWidth: 0 }}>
                          <Typography sx={{ fontWeight: 700 }} noWrap>
                            {result.fileName ?? "Unnamed file"}
                          </Typography>

                          <Typography
                            variant="body2"
                            color="text.secondary"
                            sx={{ mt: 0.25 }}
                          >
                            {result.parentNumber ?? "Unknown Award"}
                            {result.sequenceNumber !== null
                              ? ` · Sequence ${result.sequenceNumber}`
                              : ""}
                            {result.workflowDocumentNumber
                              ? ` · Document ${result.workflowDocumentNumber}`
                              : ""}
                          </Typography>

                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{ display: "block", mt: 0.25 }}
                          >
                            {result.documentType ?? "Unknown type"}
                            {" · "}
                            {formatByteSize(result.fileSizeBytes)}
                            {" · "}
                            {formatSourceDateLabel(result.sourceDate)}
                          </Typography>

                          <Chip
                            size="small"
                            sx={{ mt: 0.75 }}
                            color={resolveAvailabilityChipColor(
                              result.availabilityStatus,
                            )}
                            label={result.availabilityStatus}
                          />
                        </Box>

                        <Stack direction="row" spacing={0.5}>
                          <Tooltip
                            title={canViewAward ? "View Award" : "Award unavailable"}
                          >
                            <span>
                              <IconButton
                                size="small"
                                disabled={!canViewAward}
                                onClick={() => viewAward(result)}
                                aria-label={
                                  canViewAward
                                    ? `View Award ${result.parentNumber ?? ""}`
                                    : "View Award unavailable"
                                }
                              >
                                <VisibilityOutlined fontSize="small" />
                              </IconButton>
                            </span>
                          </Tooltip>

                          <Tooltip
                            title={
                              canDownload
                                ? "Download"
                                : result.availabilityStatus
                            }
                          >
                            <span>
                              <IconButton
                                size="small"
                                color="primary"
                                disabled={!canDownload || isDownloading}
                                onClick={() => handleDownload(result)}
                                aria-label={
                                  canDownload
                                    ? `Download ${result.fileName ?? "file"}`
                                    : "Download unavailable"
                                }
                              >
                                {isDownloading ? (
                                  <CircularProgress size={18} />
                                ) : (
                                  <DownloadOutlined fontSize="small" />
                                )}
                              </IconButton>
                            </span>
                          </Tooltip>
                        </Stack>
                      </CardContent>
                    </Card>
                  );
                })}
              </Stack>

              <PaginationFooter
                totalPages={data.totalPages}
                page={page}
                onPageChange={setPage}
              />
            </Stack>
          )}
        </>
      )}
    </Stack>
  );
}
