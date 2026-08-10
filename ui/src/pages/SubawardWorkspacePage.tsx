import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Stack,
  Tab,
  Tabs,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useState } from "react";
import { useParams } from "react-router-dom";

import {
  downloadSubawardAttachment,
  getSubawardAmounts,
  getSubawardAttachments,
  getSubawardCloseout,
  getSubawardContacts,
  getSubawardCustomData,
  getSubawardFunding,
  getSubawardWorkspace,
} from "../api/client";
import { EmptyState } from "../components/common/EmptyState";
import { ErrorState } from "../components/common/ErrorState";
import { LoadingState } from "../components/common/LoadingState";
import { RelationshipCard } from "../components/common/RelationshipCard";
import { SectionCard } from "../components/common/SectionCard";
import { StatusPill } from "../components/common/StatusPill";
import { WorkspaceHeader } from "../components/common/WorkspaceHeader";
import { formatCurrencyAmount } from "../features/award/awardSectionsPresentation.mjs";
import { groupAttachmentsByType } from "../features/subaward/subawardAttachmentsPresentation.mjs";
import { resolveContactDisplay } from "../features/subaward/subawardContactsPresentation.mjs";
import {
  resolveAssociatedAwardCardState,
  resolveAssociatedAwardsSectionLabel,
} from "../features/subaward/subawardFundingPresentation.mjs";
import {
  buildAmendmentTimeline,
  resolveAttachmentLabel,
  sumAmendmentTotals,
} from "../features/subaward/subawardAmountsPresentation.mjs";

// Template Info, Reports, Notepad, and Notifications are intentionally not
// in the primary tab bar (Subaward v1 scope decision) - their ETL/API/DTOs
// remain fully intact and reachable directly, ready to back a future
// secondary "More" menu without further backend work.
const tabs = [
  "General",
  "History of Changes",
  "Funding",
  "Contacts",
  "Attachments",
  "Custom Data",
  "Closeout",
];

type DisplayValue = string | number | null;

function display(value: DisplayValue) {
  return value ?? "—";
}

function DetailTable({
  rows,
}: {
  rows: Array<[string, DisplayValue]>;
}) {
  return (
    <Table size="small">
      <TableBody>
        {rows.map(([label, value]) => (
          <TableRow key={label}>
            <TableCell sx={{ width: 300, fontWeight: 600 }}>{label}</TableCell>
            <TableCell sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
              {display(value)}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

interface TableColumn<T> {
  label: string;
  render: (row: T) => ReactNode;
}

function TableSection<T>({
  data,
  isLoading,
  isError,
  errorMessage,
  emptyMessage,
  columns,
  rowKey,
}: {
  data: T[] | undefined;
  isLoading: boolean;
  isError: boolean;
  errorMessage: string;
  emptyMessage: string;
  columns: Array<TableColumn<T>>;
  rowKey: (row: T) => string | number;
}) {
  if (isLoading) {
    return <LoadingState />;
  }

  if (isError) {
    return <ErrorState message={errorMessage} />;
  }

  if (!data || data.length === 0) {
    return <EmptyState message={emptyMessage} />;
  }

  return (
    <TableContainer>
      <Table size="small">
        <TableHead>
          <TableRow>
            {columns.map((column) => (
              <TableCell key={column.label}>{column.label}</TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {data.map((row) => (
            <TableRow key={rowKey(row)} hover>
              {columns.map((column) => (
                <TableCell
                  key={column.label}
                  sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
                >
                  {column.render(row)}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

export function SubawardWorkspacePage() {
  const { subawardId } = useParams();

  return (
    <SubawardWorkspaceContent key={subawardId} subawardId={subawardId} />
  );
}

function SubawardWorkspaceContent({
  subawardId,
}: {
  subawardId: string | undefined;
}) {
  const [activeTab, setActiveTab] = useState(0);
  const [amountsViewMode, setAmountsViewMode] = useState<
    "timeline" | "technical"
  >("timeline");
  const [downloadingAttachmentId, setDownloadingAttachmentId] =
    useState<number | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const parsedSubawardId = Number(subawardId);
  const validSubawardId =
    Number.isSafeInteger(parsedSubawardId) && parsedSubawardId > 0;

  const workspaceQuery = useQuery({
    queryKey: ["subaward-workspace", parsedSubawardId],
    enabled: validSubawardId,
    queryFn: () => getSubawardWorkspace(parsedSubawardId),
  });
  const amountsQuery = useQuery({
    queryKey: ["subaward-amounts", parsedSubawardId],
    enabled: validSubawardId && activeTab === 1,
    queryFn: () => getSubawardAmounts(parsedSubawardId),
  });
  const fundingQuery = useQuery({
    queryKey: ["subaward-funding", parsedSubawardId],
    enabled: validSubawardId && activeTab === 2,
    queryFn: () => getSubawardFunding(parsedSubawardId),
  });
  const contactsQuery = useQuery({
    queryKey: ["subaward-contacts", parsedSubawardId],
    enabled: validSubawardId && activeTab === 3,
    queryFn: () => getSubawardContacts(parsedSubawardId),
  });
  const attachmentsQuery = useQuery({
    queryKey: ["subaward-attachments", parsedSubawardId],
    enabled: validSubawardId && activeTab === 4,
    queryFn: () => getSubawardAttachments(parsedSubawardId),
  });
  const customDataQuery = useQuery({
    queryKey: ["subaward-custom-data", parsedSubawardId],
    enabled: validSubawardId && activeTab === 5,
    queryFn: () => getSubawardCustomData(parsedSubawardId),
  });
  const closeoutQuery = useQuery({
    queryKey: ["subaward-closeout", parsedSubawardId],
    enabled: validSubawardId && activeTab === 6,
    queryFn: () => getSubawardCloseout(parsedSubawardId),
  });

  if (!validSubawardId) {
    return <ErrorState message="Invalid Subaward ID." />;
  }

  if (workspaceQuery.isLoading) {
    return <LoadingState />;
  }

  if (workspaceQuery.isError || !workspaceQuery.data) {
    return <ErrorState message="Unable to load Subaward workspace." />;
  }

  const current = workspaceQuery.data.current;
  const generalRows: Array<[string, DisplayValue]> = [
    ["Subaward ID", current.subawardId],
    ["Subaward Code", current.subawardCode],
    ["Sequence Number", current.sequenceNumber],
    ["Document Number", current.documentNumber],
    ["Title", current.title],
    ["Organization ID", current.organizationId],
    ["Status Code", current.statusCode],
    ["Status", current.statusDescription],
    ["Sequence Status", current.subawardSequenceStatus],
    ["Subaward Type Code", current.subawardTypeCode],
    ["Start Date", current.startDate],
    ["End Date", current.endDate],
    ["Date Fully Executed", current.dateOfFullyExecuted],
    ["Closeout Date", current.closeoutDate],
    ["Extension Date Received", current.extensionDateReceived],
    ["Account Number", current.accountNumber],
    ["Purchase Order Number", current.purchaseOrderNum],
    ["Vendor Number", current.vendorNumber],
    ["Requisition Number", current.requisitionNumber],
    ["Requisitioner ID", current.requisitionerId],
    ["Requisitioner Unit", current.requisitionerUnit],
    ["Site Investigator", current.siteInvestigator],
    ["Archive Location", current.archiveLocation],
    ["Cost Type", current.costType],
    ["F&A Rate", current.fAndARate],
    ["De Minimus", current.deMinimus],
    ["FFATA Required", current.ffataRequired],
    ["FSRS Subaward Number", current.fsrsSubawardNumber],
    ["Award Sponsor Name", current.awardSponsorName],
    ["Award Prime Sponsor Name", current.awardPrimeSponsorName],
    ["Federal Award Project Description", current.fedAwardProjDesc],
    ["Comments", current.comments],
    ["Source Update Timestamp", current.sourceUpdateTimestamp],
    ["Source Update User", current.sourceUpdateUser],
    ["Source Version Number", current.sourceVersionNumber],
    ["Source Object ID", current.sourceObjectId],
    ["Document Update Timestamp", current.documentSourceUpdateTimestamp],
    ["Document Update User", current.documentSourceUpdateUser],
    ["Document Version Number", current.documentSourceVersionNumber],
    ["Document Object ID", current.documentSourceObjectId],
  ];

  return (
    <Stack spacing={3}>
      <WorkspaceHeader
        title={`Subaward ${current.subawardCode}`}
        subtitle={current.title ?? "Untitled Subaward"}
        eyebrow={`Physical source record ${current.subawardId}`}
        chips={[
          { label: `Subaward Code ${current.subawardCode}` },
          {
            element: (
              <StatusPill
                status={current.statusDescription}
                domain="subaward"
              />
            ),
          },
          { label: `Version ${current.sequenceNumber}` },
          { label: `Workflow ${current.documentNumber ?? "—"}` },
        ]}
      />

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
              <ToggleButtonGroup
                size="small"
                exclusive
                value={amountsViewMode}
                onChange={(_, next) => next && setAmountsViewMode(next)}
              >
                <ToggleButton value="timeline">Timeline</ToggleButton>
                <ToggleButton value="technical">Technical View</ToggleButton>
              </ToggleButtonGroup>

              {amountsQuery.isLoading && <LoadingState />}
              {amountsQuery.isError && (
                <ErrorState message="Unable to load Subaward amounts." />
              )}
              {!amountsQuery.isLoading &&
                !amountsQuery.isError &&
                (amountsQuery.data?.length ?? 0) === 0 && (
                  <EmptyState
                    variant="text"
                    message="No amount rows are archived for this Subaward record."
                  />
                )}

              {amountsViewMode === "timeline" &&
                amountsQuery.data &&
                amountsQuery.data.length > 0 && (
                  <Stack spacing={2}>
                    {(() => {
                      const totals = sumAmendmentTotals(amountsQuery.data);
                      return (
                        <SectionCard>
                          <Stack
                            direction="row"
                            spacing={4}
                            sx={{ flexWrap: "wrap" }}
                          >
                            <Box>
                              <Typography
                                variant="overline"
                                color="text.secondary"
                              >
                                Total Obligated
                              </Typography>
                              <Typography variant="h6">
                                {display(totals.totalObligatedChange)}
                              </Typography>
                            </Box>
                            <Box>
                              <Typography
                                variant="overline"
                                color="text.secondary"
                              >
                                Total Anticipated
                              </Typography>
                              <Typography variant="h6">
                                {display(totals.totalAnticipatedChange)}
                              </Typography>
                            </Box>
                          </Stack>
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{ mt: 1, display: "block" }}
                          >
                            Amount Released and Available Amount require
                            SUBAWARD_AMOUNT_RELEASED, not yet archived by
                            this platform.
                          </Typography>
                        </SectionCard>
                      );
                    })()}

                    {buildAmendmentTimeline(amountsQuery.data).map((card) => (
                      <SectionCard key={card.subawardAmountInfoId}>
                        <Stack
                          direction="row"
                          spacing={1}
                          sx={{ alignItems: "center", flexWrap: "wrap" }}
                        >
                          <Typography sx={{ fontWeight: 700 }}>
                            Amendment {card.amendmentNumber ?? "—"}
                          </Typography>
                          {card.modificationType && (
                            <Chip
                              size="small"
                              variant="outlined"
                              label={card.modificationType}
                            />
                          )}
                        </Stack>
                        <Typography
                          variant="body2"
                          color="text.secondary"
                          sx={{ mt: 0.5 }}
                        >
                          Effective {display(card.effectiveDate)}
                          {" · Budget period "}
                          {display(card.budgetPeriodStart)} –{" "}
                          {display(card.budgetPeriodEnd)}
                        </Typography>
                        <Stack
                          direction="row"
                          spacing={3}
                          sx={{ mt: 1, flexWrap: "wrap" }}
                        >
                          <Typography variant="body2">
                            Obligated change:{" "}
                            {display(card.obligatedChange)}
                          </Typography>
                          <Typography variant="body2">
                            Anticipated change:{" "}
                            {display(card.anticipatedChange)}
                          </Typography>
                        </Stack>
                        {card.comments && (
                          <Typography variant="body2" sx={{ mt: 1 }}>
                            {card.comments}
                          </Typography>
                        )}
                        {card.attachmentLabel && (
                          <Chip
                            sx={{ mt: 1 }}
                            size="small"
                            variant="outlined"
                            label={card.attachmentLabel}
                          />
                        )}
                      </SectionCard>
                    ))}
                  </Stack>
                )}

              {amountsViewMode === "technical" && (
                <TableSection
                  data={amountsQuery.data}
                  isLoading={amountsQuery.isLoading}
                  isError={amountsQuery.isError}
                  errorMessage="Unable to load Subaward amounts."
                  emptyMessage="No amount rows are archived for this Subaward record."
                  rowKey={(row) => row.subawardAmountInfoId}
                  columns={[
                    { label: "Amount ID", render: (row) => row.subawardAmountInfoId },
                    { label: "Effective Date", render: (row) => display(row.effectiveDate) },
                    { label: "Modification", render: (row) => display(row.modificationNumber) },
                    { label: "Modification Type", render: (row) => display(row.modificationTypeDescription ?? row.modificationTypeCode) },
                    { label: "Obligated", render: (row) => display(row.obligatedAmount) },
                    { label: "Obligated Change", render: (row) => display(row.obligatedChange) },
                    { label: "Anticipated", render: (row) => display(row.anticipatedAmount) },
                    { label: "Anticipated Change", render: (row) => display(row.anticipatedChange) },
                    { label: "Performance Period", render: (row) => `${display(row.performanceStartDate)} – ${display(row.performanceEndDate)}` },
                    { label: "File Metadata", render: (row) => resolveAttachmentLabel(row) ?? "—" },
                    { label: "Updated", render: (row) => display(row.sourceUpdateTimestamp) },
                  ]}
                />
              )}
            </Stack>
          )}

          {activeTab === 2 && (
            <Stack spacing={2}>
              <Typography variant="h6">
                {resolveAssociatedAwardsSectionLabel(fundingQuery.data ?? [])}
              </Typography>

              {fundingQuery.data?.map((funding) => {
                const cardState = resolveAssociatedAwardCardState(funding);
                if (cardState.kind === "NONE") {
                  return null;
                }
                return (
                  <RelationshipCard
                    key={funding.subawardFundingSourceId}
                    title={`Award ${cardState.awardNumber}`}
                    archived={cardState.kind === "LOADED"}
                    subtitle={
                      cardState.kind === "LOADED"
                        ? cardState.awardTitle
                        : null
                    }
                    statusLabel={
                      cardState.kind === "LOADED" && (
                        <StatusPill
                          status={cardState.awardStatus}
                          domain="award"
                        />
                      )
                    }
                    metaText={
                      cardState.kind === "LOADED"
                        ? cardState.awardSponsor
                        : null
                    }
                    amountText={
                      cardState.kind === "LOADED" &&
                      cardState.awardAmount !== null
                        ? formatCurrencyAmount(cardState.awardAmount)
                        : null
                    }
                    buttonLabel="Open Award"
                    href={
                      cardState.kind === "LOADED"
                        ? `/awards/${cardState.navigableCurrentAwardId}`
                        : undefined
                    }
                  />
                );
              })}
              <TableSection
                data={fundingQuery.data}
                isLoading={fundingQuery.isLoading}
                isError={fundingQuery.isError}
                errorMessage="Unable to load Subaward funding."
                emptyMessage="No funding associations are archived for this Subaward record."
                rowKey={(row) => row.subawardFundingSourceId}
                columns={[
                  { label: "Funding Source ID", render: (row) => row.subawardFundingSourceId },
                  { label: "Subaward Code", render: (row) => row.subawardCode },
                  { label: "Sequence", render: (row) => row.sequenceNumber },
                  { label: "Updated", render: (row) => display(row.sourceUpdateTimestamp) },
                  { label: "Source Version", render: (row) => display(row.sourceVersionNumber) },
                ]}
              />
            </Stack>
          )}

          {activeTab === 3 && (
            <TableSection
              data={contactsQuery.data}
              isLoading={contactsQuery.isLoading}
              isError={contactsQuery.isError}
              errorMessage="Unable to load Subaward contacts."
              emptyMessage="No contacts are archived for this Subaward record."
              rowKey={(row) => row.subawardContactId}
              columns={[
                {
                  label: "Name",
                  render: (row) => {
                    const contactDisplay = resolveContactDisplay(row);
                    return contactDisplay.hasIdentity
                      ? contactDisplay.name
                      : "Contact not archived";
                  },
                },
                {
                  label: "Role",
                  render: (row) => display(resolveContactDisplay(row).role),
                },
                {
                  label: "Organization",
                  render: (row) => display(resolveContactDisplay(row).organization),
                },
                {
                  label: "Phone",
                  render: (row) => display(resolveContactDisplay(row).phone),
                },
                {
                  label: "Email",
                  render: (row) => display(resolveContactDisplay(row).email),
                },
                {
                  label: "ID",
                  render: (row) => (
                    <Typography variant="caption" color="text.secondary">
                      {row.rolodexId
                        ? `Rolodex ${row.rolodexId}`
                        : row.requisitionerId
                          ? `Person ${row.requisitionerId}`
                          : "—"}
                    </Typography>
                  ),
                },
              ]}
            />
          )}

          {activeTab === 4 && (
            <Stack spacing={3}>
              {downloadError && (
                <ErrorState
                  message={downloadError}
                  onClose={() => setDownloadError(null)}
                />
              )}
              {attachmentsQuery.isLoading && <LoadingState />}
              {attachmentsQuery.isError && (
                <ErrorState message="Unable to load Subaward attachment metadata." />
              )}
              {!attachmentsQuery.isLoading &&
                !attachmentsQuery.isError &&
                (attachmentsQuery.data?.length ?? 0) === 0 && (
                  <EmptyState message="No attachment metadata is archived for this Subaward record." />
                )}

              {attachmentsQuery.data &&
                attachmentsQuery.data.length > 0 &&
                groupAttachmentsByType(attachmentsQuery.data).map((group) => (
                  <Box key={group.typeLabel}>
                    <Typography variant="h6" sx={{ mb: 1 }}>
                      {group.typeLabel}
                    </Typography>
                    <TableContainer>
                      <Table size="small">
                        <TableHead>
                          <TableRow>
                            <TableCell>File Name</TableCell>
                            <TableCell>Description</TableCell>
                            <TableCell>Updated</TableCell>
                            <TableCell>Updated By</TableCell>
                            <TableCell>Download</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {group.attachments.map((row) => (
                            <TableRow key={row.attachmentId} hover>
                              <TableCell sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
                                {display(row.fileName)}
                              </TableCell>
                              <TableCell sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
                                {display(row.description)}
                              </TableCell>
                              <TableCell>{display(row.lastUpdateTimestamp)}</TableCell>
                              <TableCell>{display(row.lastUpdateUser)}</TableCell>
                              <TableCell>
                                {row.archived ? (
                                  <Button
                                    size="small"
                                    disabled={downloadingAttachmentId === row.attachmentId}
                                    onClick={async () => {
                                      setDownloadError(null);
                                      setDownloadingAttachmentId(row.attachmentId);
                                      try {
                                        await downloadSubawardAttachment(
                                          parsedSubawardId,
                                          row.attachmentId,
                                          row.fileName ?? `attachment-${row.attachmentId}`,
                                        );
                                      } catch (error) {
                                        setDownloadError(
                                          error instanceof Error
                                            ? error.message
                                            : "Unable to download this attachment.",
                                        );
                                      } finally {
                                        setDownloadingAttachmentId(null);
                                      }
                                    }}
                                  >
                                    {downloadingAttachmentId === row.attachmentId
                                      ? "Downloading…"
                                      : "Download"}
                                  </Button>
                                ) : (
                                  <Typography variant="body2" color="text.secondary">
                                    Not archived
                                  </Typography>
                                )}
                              </TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  </Box>
                ))}
            </Stack>
          )}

          {activeTab === 5 && (
            <TableSection
              data={customDataQuery.data}
              isLoading={customDataQuery.isLoading}
              isError={customDataQuery.isError}
              errorMessage="Unable to load Subaward custom data."
              emptyMessage="No custom data is archived for this Subaward record."
              rowKey={(row) => row.subawardCustomDataId}
              columns={[
                { label: "Custom Data ID", render: (row) => row.subawardCustomDataId },
                { label: "Attribute ID", render: (row) => display(row.customAttributeId) },
                { label: "Value", render: (row) => display(row.value) },
                { label: "Updated", render: (row) => display(row.sourceUpdateTimestamp) },
                { label: "Update User", render: (row) => display(row.sourceUpdateUser) },
                { label: "Source Version", render: (row) => display(row.sourceVersionNumber) },
              ]}
            />
          )}

          {activeTab === 6 && (
            <TableSection
              data={closeoutQuery.data}
              isLoading={closeoutQuery.isLoading}
              isError={closeoutQuery.isError}
              errorMessage="Unable to load Subaward closeout records."
              emptyMessage="No closeout records are archived for this Subaward. The current source dataset is empty."
              rowKey={(row) => row.subawardCloseoutId}
              columns={[
                { label: "Number", render: (row) => display(row.closeoutNumber) },
                {
                  label: "Type",
                  render: (row) =>
                    // CLOSEOUT_TYPE's live-Oracle presence is unconfirmed
                    // (docs/SUBAWARD_ORACLE_VALIDATION.md) - show a neutral
                    // label, never a fabricated description, until verified.
                    row.closeoutTypeCode !== null
                      ? `Closeout Type ${row.closeoutTypeCode}`
                      : "—",
                },
                { label: "Requested", render: (row) => display(row.dateRequested) },
                { label: "Follow-up", render: (row) => display(row.dateFollowup) },
                { label: "Received", render: (row) => display(row.dateReceived) },
                { label: "Comments", render: (row) => display(row.comments) },
              ]}
            />
          )}
        </CardContent>
      </Card>
    </Stack>
  );
}
