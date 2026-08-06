import { ArrowForwardOutlined } from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
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
import { Link as RouterLink, useParams } from "react-router-dom";

import {
  downloadSubawardAttachment,
  getSubawardAmounts,
  getSubawardAttachments,
  getSubawardCloseout,
  getSubawardContacts,
  getSubawardCustomData,
  getSubawardFunding,
  getSubawardNotepad,
  getSubawardNotifications,
  getSubawardReports,
  getSubawardTemplateInfo,
  getSubawardWorkspace,
} from "../api/client";
import { formatCurrencyAmount } from "../features/award/awardSectionsPresentation.mjs";
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

const tabs = [
  "General",
  "Amounts",
  "Contacts",
  "Funding",
  "Attachments",
  "Custom Data",
  "Template Info",
  "Closeout",
  "Reports",
  "Notepad",
  "Notifications",
];

type DisplayValue = string | number | null;

function display(value: DisplayValue) {
  return value ?? "—";
}

function LoadingState() {
  return (
    <Box sx={{ display: "grid", placeItems: "center", py: 8 }}>
      <CircularProgress />
    </Box>
  );
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
    return <Alert severity="error">{errorMessage}</Alert>;
  }

  if (!data || data.length === 0) {
    return <Alert severity="info">{emptyMessage}</Alert>;
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
  const contactsQuery = useQuery({
    queryKey: ["subaward-contacts", parsedSubawardId],
    enabled: validSubawardId && activeTab === 2,
    queryFn: () => getSubawardContacts(parsedSubawardId),
  });
  const fundingQuery = useQuery({
    queryKey: ["subaward-funding", parsedSubawardId],
    enabled: validSubawardId && activeTab === 3,
    queryFn: () => getSubawardFunding(parsedSubawardId),
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
  const templateInfoQuery = useQuery({
    queryKey: ["subaward-template-info", parsedSubawardId],
    enabled: validSubawardId && activeTab === 6,
    queryFn: () => getSubawardTemplateInfo(parsedSubawardId),
  });
  const closeoutQuery = useQuery({
    queryKey: ["subaward-closeout", parsedSubawardId],
    enabled: validSubawardId && activeTab === 7,
    queryFn: () => getSubawardCloseout(parsedSubawardId),
  });
  const reportsQuery = useQuery({
    queryKey: ["subaward-reports", parsedSubawardId],
    enabled: validSubawardId && activeTab === 8,
    queryFn: () => getSubawardReports(parsedSubawardId),
  });
  const notepadQuery = useQuery({
    queryKey: ["subaward-notepad", parsedSubawardId],
    enabled: validSubawardId && activeTab === 9,
    queryFn: () => getSubawardNotepad(parsedSubawardId),
  });
  const notificationsQuery = useQuery({
    queryKey: ["subaward-notifications", parsedSubawardId],
    enabled: validSubawardId && activeTab === 10,
    queryFn: () => getSubawardNotifications(parsedSubawardId),
  });

  if (!validSubawardId) {
    return <Alert severity="error">Invalid Subaward ID.</Alert>;
  }

  if (workspaceQuery.isLoading) {
    return <LoadingState />;
  }

  if (workspaceQuery.isError || !workspaceQuery.data) {
    return <Alert severity="error">Unable to load Subaward workspace.</Alert>;
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

  const template = templateInfoQuery.data;
  const templateRows: Array<[string, DisplayValue]> = template
    ? [
        ["Subaward ID", template.subawardId],
        ["Subaward Code", template.subawardCode],
        ["Sequence Number", template.sequenceNumber],
        ["SOW or Sub-proposal Budget", template.sowOrSubProposalBudget],
        ["Sub-proposal Date", template.subProposalDate],
        ["Invoice or Payment Contact", template.invoiceOrPaymentContact],
        ["IRB/IACUC Contact", template.irbIacucContact],
        ["Final Statement of Costs Contact", template.finalStmtOfCostsContact],
        ["Change Requests Contact", template.changeRequestsContact],
        ["Sub Change Requests Contact", template.subChangeRequestsContact],
        ["Termination Contact", template.terminationContact],
        ["Sub Termination Contact", template.subTerminationContact],
        ["No-cost Extension Contact", template.noCostExtensionContact],
        ["Performance Site Differs from Organization", template.perfSiteDiffFromOrgAddr],
        ["Performance Site Same as Sub PI", template.perfSiteSameAsSubPiAddr],
        ["Registered in CCR", template.subRegisteredInCcr],
        ["Exempt from Reporting Compensation", template.subExemptFromReportingComp],
        ["Parent DUNS Number", template.parentDunsNumber],
        ["Parent Congressional District", template.parentCongressionalDistrict],
        ["Exempt from Executive Compensation Reporting", template.exemptFromRprtgExecComp],
        ["Copyright Type", template.copyrightType],
        ["Automatic Carry Forward", template.automaticCarryForward],
        ["Carry Forward Requests Sent To", template.carryForwardRequestsSentTo],
        ["Program Income Additive", template.treatmentPrgmIncomeAdditive],
        ["Applicable Program Regulations", template.applicableProgramRegulations],
        ["Applicable Program Regulations Date", template.applicableProgramRegsDate],
        ["MPI Award", template.mpiAward],
        ["MPI Leadership Plan", template.mpiLeadershipPlan],
        ["R&D", template.rAndD],
        ["Includes Cost Sharing", template.includesCostSharing],
        ["FCIO", template.fcio],
        ["Invoices Emailed", template.invoicesEmailed],
        ["Invoice Address Different", template.invoiceAddressDiff],
        ["Invoice Email Different", template.invoiceEmailDiff],
        ["FCIO Subrecipient Policy Code", template.fcioSubrecPolicyCd],
        ["Animal Flag", template.animalFlag],
        ["Animal PTE Send Code", template.animalPteSendCd],
        ["Animal PTE Not Required Code", template.animalPteNrCd],
        ["Human Flag", template.humanFlag],
        ["Human Subjects", template.humanSubjects],
        ["Human Exempt Documentation", template.humanExemptDocs],
        ["Human PTE Send Code", template.humanPteSendCd],
        ["Human PTE Not Required Code", template.humanPteNrCd],
        ["Human Data Exchange Agreement Code", template.humanDataExchangeAgreeCd],
        ["Human Data Exchange Terms Code", template.humanDataExchangeTermsCd],
        ["Includes Clinical Trials", template.humanIncludesClinicalTrials],
        ["Additional Terms", template.additionalTerms],
        ["Treatment of Income", template.treatmentOfIncome],
        ["Data Sharing Attachment", template.dataSharingAttachment],
        ["Data Sharing Code", template.dataSharingCd],
        ["Final Statement Due Code", template.finalStatementDueCd],
        ["Source Update Timestamp", template.sourceUpdateTimestamp],
        ["Source Update User", template.sourceUpdateUser],
      ]
    : [];

  return (
    <Stack spacing={3}>
      <Card>
        <CardContent>
          <Typography variant="h4">
            Subaward {current.subawardCode}
          </Typography>
          <Typography variant="h6" sx={{ mt: 1 }}>
            {current.title ?? "Untitled Subaward"}
          </Typography>
          <Typography color="text.secondary" sx={{ mt: 1 }}>
            Physical source record {current.subawardId}
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
            <Chip
              size="small"
              variant="outlined"
              label={`Subaward Code ${current.subawardCode}`}
            />
            <Chip
              size="small"
              color="primary"
              label={current.statusDescription ?? "Unknown status"}
            />
            <Chip
              size="small"
              variant="outlined"
              label={`Version ${current.sequenceNumber}`}
            />
            <Chip
              size="small"
              variant="outlined"
              label={`Workflow ${current.documentNumber ?? "—"}`}
            />
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
              <ToggleButtonGroup
                size="small"
                exclusive
                value={amountsViewMode}
                onChange={(_, next) => next && setAmountsViewMode(next)}
              >
                <ToggleButton value="timeline">
                  History of Changes
                </ToggleButton>
                <ToggleButton value="technical">Technical View</ToggleButton>
              </ToggleButtonGroup>

              {amountsQuery.isLoading && <LoadingState />}
              {amountsQuery.isError && (
                <Alert severity="error">
                  Unable to load Subaward amounts.
                </Alert>
              )}
              {!amountsQuery.isLoading &&
                !amountsQuery.isError &&
                (amountsQuery.data?.length ?? 0) === 0 && (
                  <Typography color="text.secondary">
                    No amount rows are archived for this Subaward record.
                  </Typography>
                )}

              {amountsViewMode === "timeline" &&
                amountsQuery.data &&
                amountsQuery.data.length > 0 && (
                  <Stack spacing={2}>
                    {(() => {
                      const totals = sumAmendmentTotals(amountsQuery.data);
                      return (
                        <Card variant="outlined">
                          <CardContent>
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
                          </CardContent>
                        </Card>
                      );
                    })()}

                    {buildAmendmentTimeline(amountsQuery.data).map((card) => (
                      <Card key={card.subawardAmountInfoId} variant="outlined">
                        <CardContent>
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
                        </CardContent>
                      </Card>
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

          {activeTab === 3 && (
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
                  <Card
                    key={funding.subawardFundingSourceId}
                    variant="outlined"
                  >
                    <CardContent>
                      <Typography sx={{ fontWeight: 700 }}>
                        Award {cardState.awardNumber}
                      </Typography>
                      {cardState.kind === "LOADED" ? (
                        <>
                          {cardState.awardTitle && (
                            <Typography variant="body2">
                              {cardState.awardTitle}
                            </Typography>
                          )}
                          <Stack
                            direction="row"
                            spacing={1}
                            sx={{ mt: 0.5, alignItems: "center", flexWrap: "wrap" }}
                          >
                            {cardState.awardStatus && (
                              <Chip
                                size="small"
                                color="primary"
                                label={cardState.awardStatus}
                              />
                            )}
                            {cardState.awardSponsor && (
                              <Typography
                                variant="body2"
                                color="text.secondary"
                              >
                                {cardState.awardSponsor}
                              </Typography>
                            )}
                            {cardState.awardAmount !== null && (
                              <Typography
                                variant="body2"
                                color="text.secondary"
                              >
                                {formatCurrencyAmount(cardState.awardAmount)}
                              </Typography>
                            )}
                          </Stack>
                          <Button
                            sx={{ mt: 1 }}
                            variant="outlined"
                            size="small"
                            endIcon={<ArrowForwardOutlined />}
                            component={RouterLink}
                            to={`/awards/${cardState.navigableCurrentAwardId}`}
                          >
                            Open Award
                          </Button>
                        </>
                      ) : (
                        <Chip
                          sx={{ mt: 1 }}
                          size="small"
                          variant="outlined"
                          label="Not currently archived"
                        />
                      )}
                    </CardContent>
                  </Card>
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

          {activeTab === 4 && (
            <Stack spacing={2}>
              {downloadError && (
                <Alert severity="error" onClose={() => setDownloadError(null)}>
                  {downloadError}
                </Alert>
              )}
              <TableSection
                data={attachmentsQuery.data}
                isLoading={attachmentsQuery.isLoading}
                isError={attachmentsQuery.isError}
                errorMessage="Unable to load Subaward attachment metadata."
                emptyMessage="No attachment metadata is archived for this Subaward record."
                rowKey={(row) => row.attachmentId}
                columns={[
                  { label: "Attachment ID", render: (row) => row.attachmentId },
                  { label: "Type", render: (row) => display(row.attachmentTypeDescription ?? row.attachmentTypeCode) },
                  { label: "File Name", render: (row) => display(row.fileName) },
                  { label: "MIME Type", render: (row) => display(row.mimeType) },
                  { label: "Description", render: (row) => display(row.description) },
                  { label: "Date", render: (row) => display(row.lastUpdateTimestamp) },
                  { label: "User", render: (row) => display(row.lastUpdateUser) },
                  {
                    label: "Action",
                    render: (row) =>
                      row.archived ? (
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
                      ),
                  },
                ]}
              />
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
            <>
              {templateInfoQuery.isLoading && <LoadingState />}
              {templateInfoQuery.isError && (
                <Alert severity="error">
                  Unable to load Subaward template information.
                </Alert>
              )}
              {templateInfoQuery.data && <DetailTable rows={templateRows} />}
            </>
          )}

          {activeTab === 7 && (
            <TableSection
              data={closeoutQuery.data}
              isLoading={closeoutQuery.isLoading}
              isError={closeoutQuery.isError}
              errorMessage="Unable to load Subaward closeout records."
              emptyMessage="No closeout records are archived for this Subaward. The current source dataset is empty."
              rowKey={(row) => row.subawardCloseoutId}
              columns={[
                { label: "Closeout ID", render: (row) => row.subawardCloseoutId },
                { label: "Number", render: (row) => display(row.closeoutNumber) },
                { label: "Type Code", render: (row) => display(row.closeoutTypeCode) },
                { label: "Requested", render: (row) => display(row.dateRequested) },
                { label: "Follow-up", render: (row) => display(row.dateFollowup) },
                { label: "Received", render: (row) => display(row.dateReceived) },
                { label: "Comments", render: (row) => display(row.comments) },
              ]}
            />
          )}

          {activeTab === 8 && (
            <TableSection
              data={reportsQuery.data}
              isLoading={reportsQuery.isLoading}
              isError={reportsQuery.isError}
              errorMessage="Unable to load Subaward reports."
              emptyMessage="No reports are archived for this Subaward. The current source dataset is empty."
              rowKey={(row) => row.subawardReportId}
              columns={[
                { label: "Report ID", render: (row) => row.subawardReportId },
                { label: "Type Code", render: (row) => display(row.reportTypeCode) },
                { label: "Report Type", render: (row) => display(row.reportTypeDescription) },
                { label: "Updated", render: (row) => display(row.sourceUpdateTimestamp) },
                { label: "Update User", render: (row) => display(row.sourceUpdateUser) },
              ]}
            />
          )}

          {activeTab === 9 && (
            <TableSection
              data={notepadQuery.data}
              isLoading={notepadQuery.isLoading}
              isError={notepadQuery.isError}
              errorMessage="Unable to load Subaward notepad records."
              emptyMessage="No notepad records are archived for this Subaward. The current source dataset is empty."
              rowKey={(row) => row.subawardNotepadId}
              columns={[
                { label: "Notepad ID", render: (row) => row.subawardNotepadId },
                { label: "Entry", render: (row) => display(row.entryNumber) },
                { label: "Topic", render: (row) => display(row.noteTopic) },
                { label: "Comments", render: (row) => display(row.comments) },
                { label: "Restricted", render: (row) => display(row.restrictedView) },
                { label: "Created", render: (row) => display(row.createTimestamp) },
                { label: "Create User", render: (row) => display(row.createUser) },
              ]}
            />
          )}

          {activeTab === 10 && (
            <TableSection
              data={notificationsQuery.data}
              isLoading={notificationsQuery.isLoading}
              isError={notificationsQuery.isError}
              errorMessage="Unable to load Subaward notifications."
              emptyMessage="No notifications are archived for this Subaward. The current source dataset is empty."
              rowKey={(row) => row.notificationId}
              columns={[
                { label: "Notification ID", render: (row) => row.notificationId },
                { label: "Type ID", render: (row) => display(row.notificationTypeId) },
                { label: "Document Number", render: (row) => display(row.documentNumber) },
                { label: "Recipients", render: (row) => display(row.recipients) },
                { label: "Subject", render: (row) => display(row.subject) },
                { label: "Message", render: (row) => display(row.message) },
                { label: "Created", render: (row) => display(row.createTimestamp) },
              ]}
            />
          )}
        </CardContent>
      </Card>
    </Stack>
  );
}
