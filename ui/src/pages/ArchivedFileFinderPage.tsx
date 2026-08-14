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
import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

import {
  ApiRequestError,
  downloadAwardAttachmentV1,
  downloadProposalAttachmentV1,
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
  parseRecordTypeParam,
  parseVersionFilterParam,
  RECORD_TYPE_OPTIONS,
  recordIdFieldLabel,
  recordNumberFieldLabel,
  recordTypeLabel,
  resolveAvailabilityChipColor,
  resolveRecordViewPath,
  visibleFieldsForRecordType,
} from "../features/archivedFiles/archivedFileFinderPresentation.mjs";
import type { ArchivedFileSearchResult } from "../types/api";

const PAGE_SIZE = 25;

function filtersFromParams(searchParams: URLSearchParams) {
  return {
    recordType: parseRecordTypeParam(searchParams.get("recordType")),
    recordNumber: searchParams.get("recordNumber") ?? "",
    documentNumber: searchParams.get("documentNumber") ?? "",
    recordId: searchParams.get("recordId") ?? "",
    attachmentId: searchParams.get("attachmentId") ?? "",
    fileId: searchParams.get("fileId") ?? "",
    versionFilter: parseVersionFilterParam(searchParams.get("versionFilter")),
  };
}

type Filters = ReturnType<typeof filtersFromParams>;

const EMPTY_FILTERS: Filters = {
  recordType: "ALL",
  recordNumber: "",
  documentNumber: "",
  recordId: "",
  attachmentId: "",
  fileId: "",
  versionFilter: "all",
};

// Archived File Finder - exact-identifier search across archived Award
// and Proposal attachment files via GET /api/v1/attachments/search,
// deliberately separate from Kuali Documents (DocumentsPage), which
// searches business RECORDS by free-text query, never touching
// attachment tables. One page, one nav item, for every recordType -
// Subaward/Negotiation are not part of this phase. Phase 1/2 alike are
// available only under the application's existing authenticated
// archive-staff access model - the same flat "any authenticated user"
// rule every other page already uses; this is not a researcher/
// PI-facing access tier.
//
// Applied search state lives in the URL's own search params (mirroring
// AwardVersionSearchPage's convention) so a search stays refreshable/
// shareable and survives browser back/forward; draft state is local
// until Search is clicked, matching this page's own Phase 1 "explicit
// Search action" requirement rather than AwardVersionSearchPage's
// live-as-you-type behavior.
export function ArchivedFileFinderPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const applied = filtersFromParams(searchParams);
  const page = Number(searchParams.get("page") ?? "0") || 0;

  const [draft, setDraft] = useState<Filters>(applied);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [downloadingKey, setDownloadingKey] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  // Keeps the form fields in sync with browser back/forward, not just
  // the query that runs - otherwise navigating back would silently
  // restore old results without visibly restoring the filters that
  // produced them.
  useEffect(() => {
    setDraft(filtersFromParams(searchParams));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  const hasSearched = hasAnyIdentifierSupplied(applied);
  const visibleFields = visibleFieldsForRecordType(draft.recordType);

  const searchQuery = useQuery({
    queryKey: ["archived-file-finder", applied, page],
    queryFn: ({ signal }) =>
      searchArchivedFiles(
        {
          recordType: applied.recordType as "ALL" | "AWARD" | "PROPOSAL",
          recordNumber: applied.recordNumber,
          documentNumber: applied.documentNumber,
          recordId: applied.recordId,
          attachmentId: applied.attachmentId,
          fileId: applied.fileId,
          versionFilter: applied.versionFilter as "all" | "current" | "historical",
          page,
          size: PAGE_SIZE,
        },
        signal,
      ),
    enabled: hasSearched,
  });

  function runSearch() {
    if (!hasAnyIdentifierSupplied(draft)) {
      setValidationError(
        "Enter at least one identifier before searching.",
      );
      return;
    }
    setValidationError(null);
    const next = new URLSearchParams();
    next.set("recordType", draft.recordType);
    if (draft.recordNumber.trim()) next.set("recordNumber", draft.recordNumber.trim());
    if (draft.documentNumber.trim()) next.set("documentNumber", draft.documentNumber.trim());
    if (draft.recordId.trim()) next.set("recordId", draft.recordId.trim());
    if (draft.attachmentId.trim()) next.set("attachmentId", draft.attachmentId.trim());
    if (draft.fileId.trim()) next.set("fileId", draft.fileId.trim());
    next.set("versionFilter", draft.versionFilter);
    setSearchParams(next);
  }

  function clearFilters() {
    setDraft(EMPTY_FILTERS);
    setValidationError(null);
    setSearchParams(new URLSearchParams());
  }

  function setPage(nextPage: number) {
    const next = new URLSearchParams(searchParams);
    next.set("page", String(nextPage));
    setSearchParams(next);
  }

  function updateDraft<K extends keyof Filters>(field: K, value: Filters[K]) {
    setDraft((current) => ({ ...current, [field]: value }));
  }

  async function handleDownload(result: ArchivedFileSearchResult) {
    if (result.parentId === null || result.attachmentId === null) {
      return;
    }
    const key = archivedFileResultKey(result);
    setDownloadError(null);
    setDownloadingKey(key);
    try {
      if (result.recordType === "PROPOSAL") {
        await downloadProposalAttachmentV1(
          result.parentId,
          result.attachmentId,
          result.fileName ?? "attachment",
        );
      } else {
        await downloadAwardAttachmentV1(
          result.parentId,
          result.attachmentId,
          result.fileName ?? "attachment",
        );
      }
    } catch (error) {
      setDownloadError(
        error instanceof Error ? error.message : "Download failed.",
      );
    } finally {
      setDownloadingKey(null);
    }
  }

  function viewRecord(result: ArchivedFileSearchResult) {
    const path = resolveRecordViewPath(result);
    if (path) {
      navigate(path);
    }
  }

  const data = searchQuery.data;

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h4">Archived File Finder</Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5 }}>
          Search for archived Award and Proposal attachment files by
          exact identifier. This is separate from Kuali Documents,
          which searches business records rather than files.
        </Typography>
      </Box>

      <Card variant="outlined">
        <CardContent>
          <Stack spacing={2}>
            <Stack direction="row" spacing={1.5} sx={{ flexWrap: "wrap" }}>
              <FormControl sx={{ minWidth: 160 }}>
                <InputLabel id="archived-file-record-type-label">
                  Record type
                </InputLabel>
                <Select
                  labelId="archived-file-record-type-label"
                  label="Record type"
                  value={draft.recordType}
                  onChange={(event: SelectChangeEvent) =>
                    updateDraft("recordType", event.target.value as Filters["recordType"])
                  }
                >
                  {RECORD_TYPE_OPTIONS.map((option) => (
                    <MenuItem key={option.value} value={option.value}>
                      {option.label}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>

              <TextField
                label={recordNumberFieldLabel(draft.recordType)}
                value={draft.recordNumber}
                onChange={(event) => updateDraft("recordNumber", event.target.value)}
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
                onChange={(event) => updateDraft("documentNumber", event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    runSearch();
                  }
                }}
                sx={{ minWidth: 200 }}
              />

              {visibleFields.includes("recordId") && (
                <TextField
                  label={recordIdFieldLabel(draft.recordType)}
                  value={draft.recordId}
                  onChange={(event) => updateDraft("recordId", event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") {
                      runSearch();
                    }
                  }}
                  sx={{ minWidth: 140 }}
                />
              )}

              {visibleFields.includes("attachmentId") && (
                <TextField
                  label="Attachment ID"
                  value={draft.attachmentId}
                  onChange={(event) => updateDraft("attachmentId", event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") {
                      runSearch();
                    }
                  }}
                  sx={{ minWidth: 140 }}
                />
              )}

              {visibleFields.includes("fileId") && (
                <TextField
                  label="File ID"
                  value={draft.fileId}
                  onChange={(event) => updateDraft("fileId", event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") {
                      runSearch();
                    }
                  }}
                  sx={{ minWidth: 140 }}
                />
              )}

              <FormControl sx={{ minWidth: 160 }}>
                <InputLabel id="archived-file-version-filter-label">
                  Version
                </InputLabel>
                <Select
                  labelId="archived-file-version-filter-label"
                  label="Version"
                  value={draft.versionFilter}
                  onChange={(event: SelectChangeEvent) =>
                    updateDraft(
                      "versionFilter",
                      event.target.value as Filters["versionFilter"],
                    )
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
                  const viewPath = resolveRecordViewPath(result);
                  const canView = viewPath !== null;

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
                          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                            <Chip
                              size="small"
                              variant="outlined"
                              label={result.recordType ?? "UNKNOWN"}
                            />
                            <Typography sx={{ fontWeight: 700 }} noWrap>
                              {result.fileName ?? "Unnamed file"}
                            </Typography>
                          </Stack>

                          <Typography
                            variant="body2"
                            color="text.secondary"
                            sx={{ mt: 0.25 }}
                          >
                            {result.parentNumber ?? "Unknown record"}
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
                            title={
                              canView
                                ? `View ${recordTypeLabel(result.recordType ?? "")}`
                                : "Record unavailable"
                            }
                          >
                            <span>
                              <IconButton
                                size="small"
                                disabled={!canView}
                                onClick={() => viewRecord(result)}
                                aria-label={
                                  canView
                                    ? `View ${result.parentNumber ?? ""}`
                                    : "View unavailable"
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
