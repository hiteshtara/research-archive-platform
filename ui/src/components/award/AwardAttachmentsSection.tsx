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
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  IconButton,
  Link,
  Skeleton,
  Stack,
  Tooltip,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { downloadAwardAttachmentV1, getAwardAttachmentsV1 } from "../../api/client";
import {
  classifyAttachmentType,
  downloadUnavailableReason,
  formatAttachmentCountLabel,
  formatByteSize as formatSize,
  hasAnyAttachments,
} from "../../features/award/awardSectionsPresentation.mjs";
import type { AttachmentTypeCategory } from "../../features/award/awardSectionsPresentation.mjs";

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

// Attachments - lazy-loads metadata from GET
// /api/v1/awards/{awardId}/attachments. Downloads stream through the
// existing authenticated proxy endpoint - the underlying S3 bucket/key
// is never exposed to this component or the network tab.
export function AwardAttachmentsSection({ awardId }: { awardId: number }) {
  const [downloadingId, setDownloadingId] = useState<number | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const attachmentsQuery = useQuery({
    queryKey: ["award-attachments-v1", awardId],
    queryFn: ({ signal }) => getAwardAttachmentsV1(awardId, signal),
  });

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
    return (
      <Stack spacing={1.5}>
        <Skeleton variant="rounded" height={72} />
        <Skeleton variant="rounded" height={72} />
      </Stack>
    );
  }

  if (attachmentsQuery.isError) {
    return <Alert severity="error">Unable to load Attachments.</Alert>;
  }

  const attachments = attachmentsQuery.data ?? [];

  if (!hasAnyAttachments(attachments)) {
    return (
      <Typography color="text.secondary">
        No attachments are recorded for this Award.
      </Typography>
    );
  }

  return (
    <Stack spacing={1.5}>
      <Box>
        <Chip
          size="small"
          color="primary"
          variant="outlined"
          label={formatAttachmentCountLabel(attachments.length)}
        />
      </Box>

      {downloadError && <Alert severity="error">{downloadError}</Alert>}

      {attachments.map((attachment) => {
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
    </Stack>
  );
}
