import { DownloadOutlined, VisibilityOutlined } from "@mui/icons-material";
import {
  Chip,
  CircularProgress,
  IconButton,
  Stack,
  Tooltip,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

import {
  ApiRequestError,
  downloadAwardAttachmentV1,
  downloadNegotiationAttachment,
  downloadProposalAttachmentV1,
  searchArchivedFiles,
} from "../api/client";
import { EmptyState } from "../components/common/EmptyState";
import { ErrorState } from "../components/common/ErrorState";
import { LoadingState } from "../components/common/LoadingState";
import { PaginationFooter } from "../components/common/PaginationFooter";
import { FilteredSearchBar } from "../components/common/search/FilteredSearchBar";
import { ResultSurface } from "../components/common/search/ResultSurface";
import { SearchPageLayout } from "../components/common/search/SearchPageLayout";
import { formatByteSize } from "../features/award/awardSectionsPresentation.mjs";
import {
  ARCHIVED_FILE_FILTER_FIELDS,
  archivedFileDisplayFields,
  archivedFileResultKey,
  archivedFileResultsCountLabel,
  archivedFileSearchErrorMessage,
  dispatchArchivedFileDownload,
  formatSourceDateLabel,
  hasAnyIdentifierSupplied,
  hiddenIdentifierFields,
  recordTypeLabel,
  resolveAvailabilityChipColor,
  resolveRecordViewPath,
} from "../features/archivedFiles/archivedFileFinderPresentation.mjs";
import type { ArchivedFileFilterKey } from "../features/archivedFiles/archivedFileFinderPresentation.d.mts";
import { useFilteredSearch } from "../hooks/useFilteredSearch";
import type { ArchivedFileSearchResult } from "../types/api";

const PAGE_SIZE = 25;

// Archived File Finder - exact-identifier search across archived Award,
// Proposal and Negotiation attachment files via
// GET /api/v1/attachments/search, deliberately separate from Kuali
// Documents (DocumentsPage), which searches business RECORDS by
// free-text query. Requires the ArchiveAttachmentViewer group,
// enforced server-side.
//
// It now uses the shared filter panel (open by default - there is no
// free-text box on this identifier-only page). Applied state lives in the
// URL, so a search is refreshable/shareable and survives Back/Forward;
// the draft only applies on Apply Filters or Enter, and still requires
// at least one identifier.
export function ArchivedFileFinderPage() {
  const navigate = useNavigate();
  const search = useFilteredSearch<ArchivedFileFilterKey>({
    fields: ARCHIVED_FILE_FILTER_FIELDS,
    initialPanelOpen: true,
  });
  const { appliedFilters: applied, page, draftFilters, setDraftFilters } = search;

  const [validationError, setValidationError] = useState<string | null>(null);
  const [downloadingKey, setDownloadingKey] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  // Identifiers that do not apply to the chosen record type are dropped
  // from the draft, so they are never sent hidden or shown as chips.
  const draftRecordType = draftFilters.recordType;
  useEffect(() => {
    const hidden = hiddenIdentifierFields(draftRecordType);
    setDraftFilters((current) => {
      if (hidden.every((key) => !current[key])) {
        return current;
      }
      const next = { ...current };
      for (const key of hidden) {
        next[key] = "";
      }
      return next;
    });
  }, [draftRecordType, setDraftFilters]);

  // The "enter an identifier" message describes the draft: drop it as soon
  // as the draft has one again, or is reset (Clear All, Back/Forward).
  const draftHasIdentifier = hasAnyIdentifierSupplied(draftFilters);
  useEffect(() => {
    if (draftHasIdentifier) {
      setValidationError(null);
    }
  }, [draftHasIdentifier]);
  useEffect(() => {
    setValidationError(null);
  }, [applied]);

  const hasSearched = hasAnyIdentifierSupplied(applied);

  const searchQuery = useQuery({
    queryKey: ["archived-file-finder", applied, page],
    queryFn: ({ signal }) =>
      searchArchivedFiles(
        {
          recordType: applied.recordType as
            | "ALL"
            | "AWARD"
            | "PROPOSAL"
            | "NEGOTIATION",
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
    if (!hasAnyIdentifierSupplied(draftFilters)) {
      setValidationError("Enter at least one identifier before searching.");
      return;
    }
    setValidationError(null);
    search.apply();
  }

  async function handleDownload(result: ArchivedFileSearchResult) {
    if (result.parentId === null || result.attachmentId === null) {
      return;
    }
    const key = archivedFileResultKey(result);
    setDownloadError(null);
    setDownloadingKey(key);
    try {
      await dispatchArchivedFileDownload(
        result.recordType,
        result.parentId,
        result.attachmentId,
        (parentId, attachmentId) =>
          downloadAwardAttachmentV1(
            parentId,
            attachmentId,
            result.fileName ?? "attachment",
          ),
        (parentId, attachmentId) =>
          downloadProposalAttachmentV1(
            parentId,
            attachmentId,
            result.fileName ?? "attachment",
          ),
        (parentId, attachmentId) =>
          downloadNegotiationAttachment(
            parentId,
            attachmentId,
            result.fileName ?? "attachment",
          ),
      );
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
    <SearchPageLayout
      title="Find an Archived File"
      subtitle="Search for archived Award, Proposal and Negotiation attachment files by exact identifier. This is separate from Kuali Documents, which searches business records rather than files."
      search={
        <FilteredSearchBar
          search={search}
          fields={ARCHIVED_FILE_FILTER_FIELDS}
          displayFields={archivedFileDisplayFields(draftRecordType)}
          chipFields={archivedFileDisplayFields(applied.recordType)}
          showSearchBox={false}
          onSubmit={runSearch}
          panelId="archived-file-filters"
          belowChips={
            validationError && (
              <Typography
                role="alert"
                variant="body2"
                color="error"
                sx={{ mt: 2 }}
              >
                {validationError}
              </Typography>
            )
          }
        />
      }
    >
      {!hasSearched && !validationError && (
        <EmptyState
          variant="text"
          message="Enter at least one identifier in the filters above and select Apply Filters to find archived files."
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
                    <ResultSurface
                      key={key}
                      // Deliberately NOT a ResultCard. This result's
                      // business action is Download, not navigation, so
                      // it gets the shared surface and its own explicit
                      // actions rather than an href it would be wrong to
                      // Cmd-click or copy.
                      identifier={result.fileName ?? "Unnamed file"}
                      secondaryIdentifier={
                        <Chip
                          size="small"
                          variant="outlined"
                          label={result.recordType ?? "UNKNOWN"}
                        />
                      }
                      status={
                        <Chip
                          size="small"
                          color={resolveAvailabilityChipColor(
                            result.availabilityStatus,
                          )}
                          label={result.availabilityStatus}
                        />
                      }
                      title={`${result.parentNumber ?? "Unknown record"}${
                        result.sequenceNumber !== null
                          ? ` · Sequence ${result.sequenceNumber}`
                          : ""
                      }${
                        result.workflowDocumentNumber
                          ? ` · Document ${result.workflowDocumentNumber}`
                          : ""
                      }`}
                      metadata={`${result.documentType ?? "Unknown type"} · ${formatByteSize(
                        result.fileSizeBytes,
                      )} · ${formatSourceDateLabel(result.sourceDate)}`}
                      rightSlot={
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
                              canDownload ? "Download" : result.availabilityStatus
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
                      }
                    />
                  );
                })}
              </Stack>

              <PaginationFooter
                totalPages={data.totalPages}
                page={page}
                onPageChange={search.goToPage}
              />
            </Stack>
          )}
        </>
      )}
    </SearchPageLayout>
  );
}
