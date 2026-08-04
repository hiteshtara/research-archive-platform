import {
  DownloadOutlined,
  FolderZipOutlined,
  ImageOutlined,
  InsertDriveFileOutlined,
  PictureAsPdfOutlined,
  TableChartOutlined,
  TextSnippetOutlined,
  VisibilityOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  IconButton,
  Skeleton,
  Stack,
  Tooltip,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import {
  downloadProposalAttachmentV1,
  getProposalAttachmentsV1,
} from "../../api/client";
import { accessToken } from "../../auth";
import {
  classifyAttachmentType,
  downloadUnavailableReason,
  formatByteSize as formatSize,
} from "../../features/award/awardSectionsPresentation.mjs";
import type { AttachmentTypeCategory } from "../../features/award/awardSectionsPresentation.mjs";
import type { ProposalAttachmentV1 } from "../../types/api";

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

// Attachments, grouped by attachment type - fed from GET
// /api/v1/proposals/{proposalId}/attachments. Grouped by Oracle's real
// PROPOSAL_ATTACHMENT_TYPE taxonomy (Internal Documents, Budget,
// Subaward Documents, Proposal Package, Communications, Required
// Signature Page, Other) - never by attachmentTitle; a file titled
// "...Guidelines" is still filed under the real "Other" type. Preview
// opens the PDF/image inline in a new tab (no server round trip beyond
// the same authenticated download stream); Download triggers a save.
export function ProposalAttachmentsSection({
  proposalId,
}: {
  proposalId: number;
}) {
  const [downloadingId, setDownloadingId] = useState<number | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [previewingId, setPreviewingId] = useState<number | null>(null);

  const attachmentsQuery = useQuery({
    queryKey: ["proposal-attachments-v1", proposalId],
    queryFn: ({ signal }) => getProposalAttachmentsV1(proposalId, signal),
  });

  async function handleDownload(attachmentId: number, fileName: string) {
    setDownloadError(null);
    setDownloadingId(attachmentId);
    try {
      await downloadProposalAttachmentV1(proposalId, attachmentId, fileName);
    } catch (error) {
      setDownloadError(
        error instanceof Error ? error.message : "Download failed.",
      );
    } finally {
      setDownloadingId(null);
    }
  }

  async function handlePreview(attachmentId: number, fileName: string) {
    setDownloadError(null);
    setPreviewingId(attachmentId);
    try {
      const token = await accessToken();
      if (!token) {
        throw new Error("No Cognito access token is available.");
      }
      const response = await fetch(
        `${import.meta.env.VITE_API_BASE_URL}/api/v1/proposals/${proposalId}` +
          `/attachments/${attachmentId}/download`,
        { headers: { Authorization: `Bearer ${token}` } },
      );
      if (!response.ok) {
        throw new Error("This attachment is not available for preview.");
      }
      const blobUrl = URL.createObjectURL(await response.blob());
      window.open(blobUrl, "_blank", "noopener,noreferrer");
    } catch (error) {
      setDownloadError(
        error instanceof Error ? error.message : `Unable to preview ${fileName}.`,
      );
    } finally {
      setPreviewingId(null);
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

  const groups = attachmentsQuery.data?.groups ?? [];
  const totalCount = groups.reduce(
    (sum, group) => sum + group.attachments.length,
    0,
  );

  if (totalCount === 0) {
    return (
      <Typography color="text.secondary">
        No attachments are recorded for this Proposal.
      </Typography>
    );
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Chip
          size="small"
          color="primary"
          variant="outlined"
          label={`${totalCount} attachment${totalCount === 1 ? "" : "s"}`}
        />
      </Box>

      {downloadError && <Alert severity="error">{downloadError}</Alert>}

      {groups.map((group) => (
        <Box key={group.attachmentTypeCode ?? "untyped"}>
          <Typography variant="overline" color="text.secondary">
            {group.attachmentTypeDescription ?? "Other"}
          </Typography>

          <Stack spacing={1} sx={{ mt: 0.5 }}>
            {group.attachments.map((attachment) => (
              <AttachmentCard
                key={attachment.proposalAttachmentId}
                attachment={attachment}
                isDownloading={downloadingId === attachment.proposalAttachmentId}
                isPreviewing={previewingId === attachment.proposalAttachmentId}
                onDownload={handleDownload}
                onPreview={handlePreview}
              />
            ))}
          </Stack>
        </Box>
      ))}
    </Stack>
  );
}

function AttachmentCard({
  attachment,
  isDownloading,
  isPreviewing,
  onDownload,
  onPreview,
}: {
  attachment: ProposalAttachmentV1;
  isDownloading: boolean;
  isPreviewing: boolean;
  onDownload: (attachmentId: number, fileName: string) => void;
  onPreview: (attachmentId: number, fileName: string) => void;
}) {
  const fileName = attachment.fileName ?? "attachment";
  const { category, label: typeLabel } = classifyAttachmentType(
    attachment.contentType,
    attachment.fileName,
  );
  const TypeIcon = TYPE_ICONS[category];
  const canOpen = attachment.downloadable;

  return (
    <Card variant="outlined">
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
            <Typography sx={{ fontWeight: 700 }} noWrap>
              {attachment.attachmentTitle ?? fileName}
            </Typography>

            <Typography variant="body2" color="text.secondary">
              {attachment.comments ?? "No description"}
            </Typography>

            <Stack
              direction="row"
              spacing={1}
              sx={{ mt: 0.5, flexWrap: "wrap", alignItems: "center" }}
            >
              <Typography variant="caption" color="text.secondary">
                {formatSize(attachment.fileSizeBytes)}
                {attachment.sourceUpdateTimestamp
                  ? ` · Updated ${attachment.sourceUpdateTimestamp}`
                  : ""}
              </Typography>
            </Stack>
          </Stack>
        </Stack>

        <Stack direction="row" spacing={0.5}>
          <Tooltip title={canOpen ? "Preview" : "Preview unavailable"}>
            <span>
              <IconButton
                size="small"
                disabled={!canOpen || isPreviewing}
                onClick={() => onPreview(attachment.proposalAttachmentId, fileName)}
                aria-label={canOpen ? `Preview ${fileName}` : "Preview unavailable"}
              >
                {isPreviewing ? (
                  <CircularProgress size={18} />
                ) : (
                  <VisibilityOutlined fontSize="small" />
                )}
              </IconButton>
            </span>
          </Tooltip>

          <Tooltip
            title={
              canOpen
                ? "Download"
                : downloadUnavailableReason(attachment.uploadStatus)
            }
          >
            <span>
              <IconButton
                size="small"
                color="primary"
                disabled={!canOpen || isDownloading}
                onClick={() => onDownload(attachment.proposalAttachmentId, fileName)}
                aria-label={canOpen ? `Download ${fileName}` : "Download unavailable"}
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
}
