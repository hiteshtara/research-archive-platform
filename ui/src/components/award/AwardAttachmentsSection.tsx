import {
  DownloadOutlined,
  FolderZipOutlined,
  ImageOutlined,
  InsertDriveFileOutlined,
  PictureAsPdfOutlined,
  TableChartOutlined,
  TextSnippetOutlined,
} from "@mui/icons-material";
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  IconButton,
  Link,
  Stack,
  Tooltip,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";

import { downloadAwardAttachmentV1, getAwardAttachmentsV1 } from "../../api/client";
import {
  classifyAttachmentType,
  downloadUnavailableReason,
  formatAttachmentCountLabel,
  formatByteSize as formatSize,
  hasAnyAttachments,
} from "../../features/award/awardSectionsPresentation.mjs";
import type { AttachmentTypeCategory } from "../../features/award/awardSectionsPresentation.mjs";
import type { AwardAttachmentV1 } from "../../types/api";
import { EmptyState } from "../common/EmptyState";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";

const TYPE_ICONS: Record<AttachmentTypeCategory, typeof InsertDriveFileOutlined> = {
  pdf: PictureAsPdfOutlined,
  word: TextSnippetOutlined,
  excel: TableChartOutlined,
  powerpoint: TextSnippetOutlined,
  archive: FolderZipOutlined,
  image: ImageOutlined,
  text: TextSnippetOutlined,
  file: InsertDriveFileOutlined,
};

const PAGE_SIZE = 25;

// Attachments - lazy-loads metadata from GET
// /api/v1/awards/{awardId}/attachments (paginated - totalElements is
// the authoritative count, never the length of whatever page happens
// to be loaded). Downloads stream through the existing authenticated
// proxy endpoint - the underlying S3 bucket/key is never exposed to
// this component or the network tab.
export function AwardAttachmentsSection({ awardId }: { awardId: number }) {
  const [downloadingId, setDownloadingId] = useState<number | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const [page, setPage] = useState(0);
  const [items, setItems] = useState<AwardAttachmentV1[]>([]);
  const [totalElements, setTotalElements] = useState<number | null>(null);
  const mergedThroughPage = useRef(-1);

  // A new Award may be opened without this component unmounting (tab
  // switches don't remount it), so accumulated pages must reset
  // whenever the Award itself changes.
  useEffect(() => {
    setPage(0);
    setItems([]);
    setTotalElements(null);
    mergedThroughPage.current = -1;
  }, [awardId]);

  const attachmentsQuery = useQuery({
    queryKey: ["award-attachments-v1", awardId, page],
    queryFn: ({ signal }) =>
      getAwardAttachmentsV1(awardId, { page, size: PAGE_SIZE }, signal),
  });

  useEffect(() => {
    if (!attachmentsQuery.data) {
      return;
    }
    setTotalElements(attachmentsQuery.data.totalElements);
    if (page === 0) {
      setItems(attachmentsQuery.data.content);
      mergedThroughPage.current = 0;
    } else if (page > mergedThroughPage.current) {
      setItems((previous) => [...previous, ...attachmentsQuery.data!.content]);
      mergedThroughPage.current = page;
    }
  }, [attachmentsQuery.data, page]);

  async function handleDownload(attachmentId: number, fileName: string) {
    setDownloadError(null);
    setDownloadingId(attachmentId);
    try {
      await downloadAwardAttachmentV1(awardId, attachmentId, fileName);
    } catch (error) {
      setDownloadError(
        error instanceof Error ? error.message : "Download failed.",
      );
    } finally {
      setDownloadingId(null);
    }
  }

  if (attachmentsQuery.isLoading) {
    return <LoadingState mode="skeleton" height={72} count={2} />;
  }

  if (attachmentsQuery.isError) {
    return <ErrorState message="Unable to load Attachments." />;
  }

  if (!hasAnyAttachments(items)) {
    return (
      <EmptyState
        variant="text"
        message="No attachments are recorded for this Award."
      />
    );
  }

  const isLastPage = attachmentsQuery.data?.last ?? true;
  const isLoadingMore = attachmentsQuery.isFetching && page > 0;

  return (
    <Stack spacing={1.5}>
      <Box>
        <Chip
          size="small"
          color="primary"
          variant="outlined"
          label={formatAttachmentCountLabel(totalElements ?? items.length)}
        />
      </Box>

      {downloadError && <ErrorState message={downloadError} />}

      {items.map((attachment) => {
        const fileName = attachment.fileName ?? "attachment";
        const isDownloading = downloadingId === attachment.awardAttachmentId;
        const { category, label: typeLabel } = classifyAttachmentType(
          attachment.contentType,
          attachment.fileName,
        );
        const TypeIcon = TYPE_ICONS[category];
        const canDownload =
          attachment.downloadable && attachment.awardAttachmentId !== null;

        function triggerDownload() {
          if (attachment.awardAttachmentId !== null) {
            handleDownload(attachment.awardAttachmentId, fileName);
          }
        }

        const downloadButton = (
          <span>
            <IconButton
              size="small"
              color="primary"
              disabled={!canDownload || isDownloading}
              onClick={triggerDownload}
              aria-label={canDownload ? `Download ${fileName}` : "Download unavailable"}
            >
              {isDownloading ? (
                <CircularProgress size={18} />
              ) : (
                <DownloadOutlined fontSize="small" />
              )}
            </IconButton>
          </span>
        );

        return (
          <Card key={attachment.awardAttachmentId} variant="outlined">
            <CardContent
              sx={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
                gap: 2,
                "&:last-child": { pb: 2 },
              }}
            >
              <Stack direction="row" spacing={1.5} sx={{ minWidth: 0, alignItems: "flex-start" }}>
                <Tooltip title={typeLabel}>
                  <TypeIcon color="action" sx={{ mt: 0.25 }} />
                </Tooltip>

                <Stack sx={{ minWidth: 0 }}>
                  {canDownload ? (
                    <Link
                      component="button"
                      type="button"
                      onClick={triggerDownload}
                      underline="hover"
                      sx={{
                        fontWeight: 700,
                        textAlign: "left",
                        overflow: "hidden",
                        textOverflow: "ellipsis",
                        whiteSpace: "nowrap",
                        maxWidth: "100%",
                      }}
                    >
                      {fileName}
                    </Link>
                  ) : (
                    <Typography sx={{ fontWeight: 700 }} noWrap>
                      {fileName}
                    </Typography>
                  )}

                  <Typography variant="body2" color="text.secondary">
                    {attachment.description ?? "No description"}
                  </Typography>

                  <Stack
                    direction="row"
                    spacing={1}
                    sx={{ mt: 0.5, flexWrap: "wrap", alignItems: "center" }}
                  >
                    <Chip size="small" variant="outlined" label={typeLabel} />
                    {attachment.typeCode && (
                      <Chip size="small" variant="outlined" label={attachment.typeCode} />
                    )}
                    {attachment.documentStatusCode && (
                      <Chip size="small" label={attachment.documentStatusCode} />
                    )}
                    <Typography variant="caption" color="text.secondary">
                      {formatSize(attachment.fileSizeBytes)}
                      {attachment.oracleUpdateTimestamp
                        ? ` · Updated ${attachment.oracleUpdateTimestamp}`
                        : ""}
                    </Typography>
                  </Stack>
                </Stack>
              </Stack>

              {canDownload ? (
                <Tooltip title="Download">{downloadButton}</Tooltip>
              ) : (
                <Tooltip
                  title={downloadUnavailableReason(attachment.uploadStatus)}
                >
                  {downloadButton}
                </Tooltip>
              )}
            </CardContent>
          </Card>
        );
      })}

      {!isLastPage && (
        <Box sx={{ display: "flex", justifyContent: "center", pt: 0.5 }}>
          <Button
            variant="outlined"
            size="small"
            disabled={isLoadingMore}
            startIcon={isLoadingMore ? <CircularProgress size={16} /> : null}
            onClick={() => setPage((current) => current + 1)}
          >
            {isLoadingMore
              ? "Loading more..."
              : `Load more (${items.length} of ${totalElements ?? items.length})`}
          </Button>
        </Box>
      )}
    </Stack>
  );
}
