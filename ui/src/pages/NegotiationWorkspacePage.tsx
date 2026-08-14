import {
  AttachFileOutlined,
  DownloadOutlined,
  OpenInNewOutlined,
  VisibilityOutlined,
} from "@mui/icons-material";
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  Stack,
  Tab,
  Tabs,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link as RouterLink, useParams } from "react-router-dom";

import {
  downloadNegotiationAttachment,
  getNegotiationActivities,
  getNegotiationAssociatedRecord,
  getNegotiationAttachments,
  getNegotiationCustomData,
  getNegotiationNotifications,
  getNegotiationUnassociatedDetails,
  getNegotiationWorkspace,
  previewNegotiationAttachment,
} from "../api/client";
import { EmptyState } from "../components/common/EmptyState";
import { ErrorState } from "../components/common/ErrorState";
import { LoadingState } from "../components/common/LoadingState";
import { RelationshipCard } from "../components/common/RelationshipCard";
import { StatusPill } from "../components/common/StatusPill";
import {
  resolveNegotiationAssociationArchived,
  resolveNegotiationAssociationDisplayKind,
} from "../features/common/relationshipCardPresentation.mjs";
import {
  resolveRestrictedLabel,
  resolveAttachmentDisplayLabel,
  resolveAttachmentIdentifierSummary,
} from "../features/negotiation/negotiationAttachmentPresentation.mjs";
import { useAttachmentAccess } from "../hooks/useAttachmentAccess";
import type {
  NegotiationActivity,
  NegotiationAttachment,
} from "../types/api";

const tabs = [
  "Summary",
  "Associated Record",
  "Activity Timeline",
  "Attachments",
  "Custom Data",
  "Notifications",
];

const ASSOCIATION_ROUTE: Record<string, (id: number) => string> = {
  AWARD: (id) => `/awards/${id}`,
  PROPOSAL: (id) => `/proposals/dashboard/${id}`,
  SUBAWARD: (id) => `/subawards/${id}`,
};

const ASSOCIATION_LABEL: Record<string, string> = {
  AWARD: "Award",
  PROPOSAL: "Proposal",
  SUBAWARD: "Subaward",
};

function display(value: string | number | null | undefined) {
  return value ?? "—";
}

function formatBytes(bytes: number | null) {
  if (bytes === null || bytes === undefined) {
    return "—";
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function DetailTable({
  rows,
}: {
  rows: Array<[string, string | number | null | undefined]>;
}) {
  return (
    <Table size="small">
      <TableBody>
        {rows.map(([label, value]) => (
          <TableRow key={label}>
            <TableCell sx={{ width: 280, fontWeight: 600 }}>{label}</TableCell>
            <TableCell sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
              {display(value)}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

export function NegotiationWorkspacePage() {
  const { negotiationId } = useParams();

  return (
    <NegotiationWorkspaceContent
      key={negotiationId}
      negotiationId={negotiationId}
    />
  );
}

function NegotiationWorkspaceContent({
  negotiationId,
}: {
  negotiationId: string | undefined;
}) {
  const [activeTab, setActiveTab] = useState(0);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingAttachmentId, setPendingAttachmentId] = useState<
    number | null
  >(null);
  const attachmentAccess = useAttachmentAccess();
  const parsedNegotiationId = Number(negotiationId);
  const validNegotiationId =
    Number.isSafeInteger(parsedNegotiationId) && parsedNegotiationId > 0;

  const workspaceQuery = useQuery({
    queryKey: ["negotiation-workspace", parsedNegotiationId],
    enabled: validNegotiationId,
    queryFn: () => getNegotiationWorkspace(parsedNegotiationId),
  });

  const associatedRecordQuery = useQuery({
    queryKey: ["negotiation-associated-record", parsedNegotiationId],
    enabled: validNegotiationId,
    queryFn: () => getNegotiationAssociatedRecord(parsedNegotiationId),
  });

  const unassociatedQuery = useQuery({
    queryKey: ["negotiation-unassociated", parsedNegotiationId],
    enabled:
      validNegotiationId &&
      activeTab === 1 &&
      associatedRecordQuery.data?.kind === "NONE",
    queryFn: () => getNegotiationUnassociatedDetails(parsedNegotiationId),
  });

  const activitiesQuery = useQuery({
    queryKey: ["negotiation-activities", parsedNegotiationId],
    enabled: validNegotiationId,
    queryFn: () => getNegotiationActivities(parsedNegotiationId),
  });

  const attachmentsQuery = useQuery({
    queryKey: ["negotiation-attachments", parsedNegotiationId],
    enabled: validNegotiationId && activeTab === 3 && attachmentAccess,
    queryFn: () => getNegotiationAttachments(parsedNegotiationId),
  });

  const customDataQuery = useQuery({
    queryKey: ["negotiation-custom-data", parsedNegotiationId],
    enabled: validNegotiationId && activeTab === 4,
    queryFn: () => getNegotiationCustomData(parsedNegotiationId),
  });

  const notificationsQuery = useQuery({
    queryKey: ["negotiation-notifications", parsedNegotiationId],
    enabled: validNegotiationId && activeTab === 5,
    queryFn: () => getNegotiationNotifications(parsedNegotiationId),
  });

  if (!validNegotiationId) {
    return <ErrorState message="Invalid Negotiation ID." />;
  }

  if (workspaceQuery.isLoading) {
    return <LoadingState />;
  }

  if (workspaceQuery.isError || !workspaceQuery.data) {
    return <ErrorState message="Unable to load Negotiation workspace." />;
  }

  const current = workspaceQuery.data.current;
  const association = associatedRecordQuery.data;

  const activityLookup = new Map<number, NegotiationActivity>(
    (activitiesQuery.data ?? []).map((activity) => [
      activity.negotiationActivityId,
      activity,
    ]),
  );

  async function handleDownload(attachment: NegotiationAttachment) {
    setActionError(null);
    setPendingAttachmentId(attachment.attachmentId);
    try {
      await downloadNegotiationAttachment(
        parsedNegotiationId,
        attachment.attachmentId,
        attachment.fileName ?? `attachment-${attachment.attachmentId}`,
      );
    } catch (error) {
      setActionError(
        error instanceof Error
          ? error.message
          : "Unable to download this attachment.",
      );
    } finally {
      setPendingAttachmentId(null);
    }
  }

  async function handlePreview(attachment: NegotiationAttachment) {
    setActionError(null);
    setPendingAttachmentId(attachment.attachmentId);
    try {
      await previewNegotiationAttachment(
        parsedNegotiationId,
        attachment.attachmentId,
      );
    } catch (error) {
      setActionError(
        error instanceof Error
          ? error.message
          : "Unable to preview this attachment.",
      );
    } finally {
      setPendingAttachmentId(null);
    }
  }

  const generalRows: Array<[string, string | number | null]> = [
    ["Negotiation ID", current.negotiationId],
    ["Document Number", current.documentNumber],
    ["Status", current.negotiationStatusDescription],
    ["Agreement Type", current.negotiationAgreementTypeDescription],
    ["Negotiator", current.negotiatorFullName],
    ["Start Date", current.negotiationStartDate],
    ["End Date", current.negotiationEndDate],
    ["Anticipated Award Date", current.anticipatedAwardDate],
    ["Document Folder", current.documentFolder],
    ["Last Updated", current.sourceUpdateTimestamp],
  ];

  const attachmentsByActivity = new Map<
    number | null,
    NegotiationAttachment[]
  >();
  for (const attachment of attachmentsQuery.data ?? []) {
    const key = attachment.activityId;
    const bucket = attachmentsByActivity.get(key) ?? [];
    bucket.push(attachment);
    attachmentsByActivity.set(key, bucket);
  }

  return (
    <Stack spacing={3}>
      <Card>
        <CardContent>
          <Typography variant="h4">
            Negotiation {current.negotiationId}
          </Typography>

          <Typography variant="h6" sx={{ mt: 1 }}>
            {current.negotiationAgreementTypeDescription ??
              "Unspecified agreement type"}
          </Typography>

          <Typography color="text.secondary" sx={{ mt: 1 }}>
            Negotiator: {current.negotiatorFullName ?? "Unknown"}
          </Typography>

          <Stack
            sx={{
              mt: 3,
              flexDirection: "row",
              alignItems: "center",
              flexWrap: "wrap",
              gap: 1,
            }}
          >
            <StatusPill
              status={current.negotiationStatusDescription}
              domain="negotiation"
            />
            {association?.clickable && association.navigableId ? (
              <Chip
                size="small"
                variant="outlined"
                icon={<OpenInNewOutlined />}
                component={RouterLink}
                to={ASSOCIATION_ROUTE[association.kind](
                  association.navigableId,
                )}
                clickable
                label={`${ASSOCIATION_LABEL[association.kind]} ${association.associatedDocumentId}`}
              />
            ) : association &&
              ASSOCIATION_LABEL[association.kind] &&
              association.associatedDocumentId ? (
              <Chip
                size="small"
                variant="outlined"
                label={`${ASSOCIATION_LABEL[association.kind]} ${association.associatedDocumentId} · Not yet archived`}
              />
            ) : (
              <Chip
                size="small"
                variant="outlined"
                label={`Association: ${
                  association?.associationTypeDescription ??
                  current.negotiationAssociationTypeDescription ??
                  "Unknown"
                }`}
              />
            )}
          </Stack>
        </CardContent>
      </Card>

      <Card>
        <Tabs
          value={activeTab}
          onChange={(_, nextTab) => setActiveTab(nextTab)}
          variant="scrollable"
          scrollButtons="auto"
          sx={{ px: 2, borderBottom: "1px solid", borderColor: "divider" }}
        >
          {tabs.map((tab) => (
            <Tab key={tab} label={tab} />
          ))}
        </Tabs>

        <CardContent>
          {activeTab === 0 && <DetailTable rows={generalRows} />}

          {activeTab === 1 && (
            <Stack spacing={2}>
              {associatedRecordQuery.isLoading && <LoadingState />}
              {associatedRecordQuery.isError && (
                <ErrorState message="Unable to load the associated record." />
              )}
              {association && (
                <>
                  {resolveNegotiationAssociationDisplayKind(association) ===
                  "none" ? (
                    <EmptyState message="This Negotiation is not associated with a Proposal, Award, or Subaward." />
                  ) : resolveNegotiationAssociationDisplayKind(association) ===
                    "unsupported" ? (
                    <EmptyState
                      message={`This Negotiation's association type (${
                        association.associationTypeDescription ??
                        association.associationTypeCode
                      }) is not one the archive recognizes yet.`}
                    />
                  ) : (
                    <RelationshipCard
                      title={`${ASSOCIATION_LABEL[association.kind] ?? "Record"} ${association.associatedDocumentId}`}
                      archived={resolveNegotiationAssociationArchived(
                        association,
                      )}
                      buttonLabel={`Open ${ASSOCIATION_LABEL[association.kind]} dashboard`}
                      href={
                        resolveNegotiationAssociationArchived(association)
                          ? ASSOCIATION_ROUTE[association.kind](
                              association.navigableId!,
                            )
                          : undefined
                      }
                    />
                  )}

                  {association.kind === "NONE" && (
                    <>
                      <Divider />
                      {unassociatedQuery.isLoading && <LoadingState />}
                      {unassociatedQuery.data &&
                        (unassociatedQuery.data.length === 0 ? (
                          <EmptyState
                            variant="text"
                            message="No additional detail is archived for this Negotiation."
                          />
                        ) : (
                          unassociatedQuery.data.map((detail) => (
                            <Card
                              key={detail.negotiationUnassocDetailId}
                              variant="outlined"
                            >
                              <CardContent>
                                <Typography variant="h6">
                                  {detail.title ?? "Untitled"}
                                </Typography>
                                <DetailTable
                                  rows={[
                                    ["Principal Investigator", detail.piName],
                                    ["Lead Unit", detail.leadUnit],
                                    ["Sponsor Code", detail.sponsorCode],
                                    [
                                      "Sponsor Award Number",
                                      detail.sponsorAwardNumber,
                                    ],
                                  ]}
                                />
                              </CardContent>
                            </Card>
                          ))
                        ))}
                    </>
                  )}
                </>
              )}
            </Stack>
          )}

          {activeTab === 2 && (
            <Stack spacing={2}>
              {activitiesQuery.isLoading && <LoadingState />}
              {activitiesQuery.isError && (
                <ErrorState message="Unable to load Negotiation activities." />
              )}
              {activitiesQuery.data && (
                <>
                  <Typography sx={{ fontWeight: 700 }}>
                    {activitiesQuery.data.length.toLocaleString()} activities
                  </Typography>
                  {activitiesQuery.data.length === 0 ? (
                    <EmptyState message="No activities are archived for this Negotiation." />
                  ) : (
                    <Stack
                      sx={{
                        borderLeft: "2px solid",
                        borderColor: "divider",
                        pl: 3,
                        ml: 1,
                      }}
                      spacing={3}
                    >
                      {activitiesQuery.data.map((activity) => (
                        <Box
                          key={activity.negotiationActivityId}
                          sx={{ position: "relative" }}
                        >
                          <Box
                            sx={{
                              position: "absolute",
                              left: -31,
                              top: 4,
                              width: 10,
                              height: 10,
                              borderRadius: "50%",
                              bgcolor: "primary.main",
                            }}
                          />
                          <Typography
                            variant="caption"
                            color="text.secondary"
                          >
                            {display(activity.startDate)}
                          </Typography>
                          <Typography sx={{ fontWeight: 700 }}>
                            {display(activity.activityTypeDescription)}
                          </Typography>
                          <Typography
                            variant="body2"
                            color="text.secondary"
                          >
                            {display(activity.locationDescription)}
                            {activity.followupDate
                              ? ` · Follow-up ${activity.followupDate}`
                              : ""}
                          </Typography>
                          {activity.description && (
                            <Typography
                              variant="body2"
                              sx={{ mt: 0.5, whiteSpace: "pre-wrap" }}
                            >
                              {activity.description}
                            </Typography>
                          )}
                          {activity.restricted === "Y" && (
                            <Chip
                              size="small"
                              color="warning"
                              variant="outlined"
                              label="Restricted"
                              sx={{ mt: 0.5 }}
                            />
                          )}
                        </Box>
                      ))}
                    </Stack>
                  )}
                </>
              )}
            </Stack>
          )}

          {activeTab === 3 && (
            <Stack spacing={2}>
              {!attachmentAccess && (
                <ErrorState message="Access denied. Viewing Negotiation attachments requires membership in the ArchiveAttachmentViewer group - contact an administrator if you believe this is wrong." />
              )}
              {actionError && (
                <ErrorState
                  message={actionError}
                  onClose={() => setActionError(null)}
                />
              )}
              {attachmentAccess && attachmentsQuery.isLoading && (
                <LoadingState />
              )}
              {attachmentAccess && attachmentsQuery.isError && (
                <ErrorState message="Unable to load Negotiation attachments." />
              )}
              {attachmentAccess && attachmentsQuery.data && (
                <>
                  <Typography sx={{ fontWeight: 700 }}>
                    {attachmentsQuery.data.length.toLocaleString()} attachments
                  </Typography>
                  {attachmentsQuery.data.length === 0 ? (
                    <EmptyState message="No attachments are archived for this Negotiation." />
                  ) : (
                    Array.from(attachmentsByActivity.entries()).map(
                      ([activityId, activityAttachments]) => {
                        const activity =
                          activityId !== null
                            ? activityLookup.get(activityId)
                            : undefined;
                        return (
                          <Box key={activityId ?? "unassigned"}>
                            <Typography
                              variant="overline"
                              color="text.secondary"
                            >
                              {activity
                                ? `${display(activity.activityTypeDescription)} · ${display(activity.startDate)}`
                                : activityId
                                  ? `Activity ${activityId}`
                                  : "Unassigned activity"}
                            </Typography>
                            <TableContainer>
                              <Table size="small">
                                <TableHead>
                                  <TableRow>
                                    <TableCell>
                                      <AttachFileOutlined
                                        fontSize="small"
                                        sx={{ verticalAlign: "middle" }}
                                      />
                                    </TableCell>
                                    <TableCell>Description</TableCell>
                                    <TableCell>Type</TableCell>
                                    <TableCell>Size</TableCell>
                                    <TableCell>Status</TableCell>
                                    <TableCell>Restricted</TableCell>
                                    <TableCell>Updated</TableCell>
                                    <TableCell align="right">
                                      Actions
                                    </TableCell>
                                  </TableRow>
                                </TableHead>
                                <TableBody>
                                  {activityAttachments.map((attachment) => (
                                    <TableRow
                                      key={attachment.attachmentId}
                                      hover
                                    >
                                      <TableCell />
                                      <TableCell>
                                        <Typography variant="body2">
                                          {resolveAttachmentDisplayLabel(
                                            attachment,
                                          )}
                                        </Typography>
                                        {attachment.fileName && (
                                          <Typography
                                            variant="caption"
                                            color="text.secondary"
                                            sx={{ display: "block" }}
                                          >
                                            {attachment.fileName}
                                          </Typography>
                                        )}
                                        {resolveAttachmentIdentifierSummary(
                                          attachment,
                                        ) && (
                                          <Typography
                                            variant="caption"
                                            color="text.secondary"
                                            sx={{ display: "block" }}
                                          >
                                            {resolveAttachmentIdentifierSummary(
                                              attachment,
                                            )}
                                          </Typography>
                                        )}
                                      </TableCell>
                                      <TableCell>
                                        {display(attachment.contentType)}
                                      </TableCell>
                                      <TableCell>
                                        {formatBytes(attachment.fileSize)}
                                      </TableCell>
                                      <TableCell>
                                        <Chip
                                          size="small"
                                          variant="outlined"
                                          color={
                                            attachment.archiveStatus ===
                                            "ARCHIVED"
                                              ? "success"
                                              : "default"
                                          }
                                          label={attachment.archiveStatus}
                                        />
                                      </TableCell>
                                      <TableCell>
                                        <Typography
                                          variant="body2"
                                          color="text.secondary"
                                        >
                                          {resolveRestrictedLabel(
                                            attachment.restrictedFlag,
                                          )}
                                        </Typography>
                                      </TableCell>
                                      <TableCell>
                                        {display(
                                          attachment.sourceUpdateTimestamp,
                                        )}
                                        {attachment.sourceUpdateUser && (
                                          <Typography
                                            variant="caption"
                                            color="text.secondary"
                                            sx={{ display: "block" }}
                                          >
                                            {attachment.sourceUpdateUser}
                                          </Typography>
                                        )}
                                      </TableCell>
                                      <TableCell align="right">
                                        {attachment.downloadable ? (
                                          <Stack
                                            sx={{
                                              flexDirection: "row",
                                              gap: 1,
                                              justifyContent: "flex-end",
                                            }}
                                          >
                                            <Button
                                              size="small"
                                              startIcon={
                                                <VisibilityOutlined />
                                              }
                                              disabled={
                                                pendingAttachmentId ===
                                                attachment.attachmentId
                                              }
                                              onClick={() =>
                                                handlePreview(attachment)
                                              }
                                            >
                                              Preview
                                            </Button>
                                            <Button
                                              size="small"
                                              startIcon={<DownloadOutlined />}
                                              disabled={
                                                pendingAttachmentId ===
                                                attachment.attachmentId
                                              }
                                              onClick={() =>
                                                handleDownload(attachment)
                                              }
                                            >
                                              {pendingAttachmentId ===
                                              attachment.attachmentId
                                                ? "Working…"
                                                : "Download"}
                                            </Button>
                                          </Stack>
                                        ) : (
                                          <Typography
                                            variant="body2"
                                            color="text.secondary"
                                          >
                                            Not available
                                          </Typography>
                                        )}
                                      </TableCell>
                                    </TableRow>
                                  ))}
                                </TableBody>
                              </Table>
                            </TableContainer>
                          </Box>
                        );
                      },
                    )
                  )}
                </>
              )}
            </Stack>
          )}

          {activeTab === 4 && (
            <Stack spacing={2}>
              {customDataQuery.isLoading && <LoadingState />}
              {customDataQuery.isError && (
                <ErrorState message="Unable to load Negotiation custom data." />
              )}
              {customDataQuery.data && (
                <>
                  <Typography sx={{ fontWeight: 700 }}>
                    {customDataQuery.data.length.toLocaleString()} custom values
                  </Typography>
                  {customDataQuery.data.length === 0 ? (
                    <EmptyState message="No custom data is archived for this Negotiation." />
                  ) : (
                    <TableContainer>
                      <Table size="small">
                        <TableHead>
                          <TableRow>
                            <TableCell>Field</TableCell>
                            <TableCell>Value</TableCell>
                            <TableCell>Updated</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {customDataQuery.data.map((item) => (
                            <TableRow key={item.negotiationCustomDataId} hover>
                              <TableCell>
                                {item.label ??
                                  item.name ??
                                  `Custom Field ${item.customAttributeId}`}
                              </TableCell>
                              <TableCell sx={{ whiteSpace: "pre-wrap" }}>
                                {item.value === null || item.value === ""
                                  ? item.value === ""
                                    ? "(blank)"
                                    : "—"
                                  : item.value}
                              </TableCell>
                              <TableCell>
                                {display(item.sourceUpdateTimestamp)}
                              </TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  )}
                </>
              )}
            </Stack>
          )}

          {activeTab === 5 && (
            <Stack spacing={2}>
              {notificationsQuery.isLoading && <LoadingState />}
              {notificationsQuery.isError && (
                <ErrorState message="Unable to load Negotiation notifications." />
              )}
              {notificationsQuery.data &&
                (notificationsQuery.data.length === 0 ? (
                  <EmptyState message="No notifications are archived for this Negotiation." />
                ) : (
                  <TableContainer>
                    <Table size="small">
                      <TableHead>
                        <TableRow>
                          <TableCell>Recipients</TableCell>
                          <TableCell>Subject</TableCell>
                          <TableCell>Message</TableCell>
                          <TableCell>Sent</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {notificationsQuery.data.map((notification) => (
                          <TableRow key={notification.notificationId} hover>
                            <TableCell>
                              {display(notification.recipients)}
                            </TableCell>
                            <TableCell>{display(notification.subject)}</TableCell>
                            <TableCell sx={{ whiteSpace: "pre-wrap" }}>
                              {display(notification.message)}
                            </TableCell>
                            <TableCell>
                              {display(notification.sourceUpdateTimestamp)}
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                ))}
            </Stack>
          )}
        </CardContent>
      </Card>
    </Stack>
  );
}
