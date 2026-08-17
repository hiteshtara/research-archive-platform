import { InfoOutlined } from "@mui/icons-material";
import {
  Alert,
  Box,
  Chip,
  Dialog,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import {
  getAwardTimeAndMoneyActionsV1,
  getAwardTimeAndMoneyDocumentV1,
  getAwardTimeAndMoneyHistoryV1,
  getAwardTimeAndMoneySummaryV1,
  getAwardTimeAndMoneyTransactionV1,
} from "../../api/client";
import { formatCurrencyAmount } from "../../features/award/awardSectionsPresentation.mjs";
import {
  describeHistoryEntry,
  describeLastAction,
  fandaDistributionPeriodLabel,
} from "../../features/award/timeAndMoneyPresentation.mjs";
import type {
  TimeAndMoneyActionV1,
  TimeAndMoneyHistoryEntryV1,
} from "../../types/api";
import { EmptyState } from "../common/EmptyState";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";
import { PaginationFooter } from "../common/PaginationFooter";
import { StatCard } from "../common/StatCard";

const PAGE_SIZE = 25;

function WorkflowDocumentDialog({
  awardId,
  timeAndMoneyDocumentNumber,
  onClose,
}: {
  awardId: number;
  timeAndMoneyDocumentNumber: string | null;
  onClose: () => void;
}) {
  const documentQuery = useQuery({
    queryKey: [
      "award-time-and-money-document-v1",
      awardId,
      timeAndMoneyDocumentNumber,
    ],
    queryFn: ({ signal }) =>
      getAwardTimeAndMoneyDocumentV1(
        awardId,
        timeAndMoneyDocumentNumber ?? "",
        signal,
      ),
    enabled: Boolean(timeAndMoneyDocumentNumber),
  });

  return (
    <Dialog
      open={timeAndMoneyDocumentNumber !== null}
      onClose={onClose}
      fullWidth
      maxWidth="sm"
    >
      <DialogTitle>Workflow Details</DialogTitle>
      <DialogContent>
        {documentQuery.isLoading && <LoadingState mode="compact" />}

        {documentQuery.isError && (
          <ErrorState message="This Time and Money document could not be loaded." />
        )}

        {documentQuery.data && (
          <Stack spacing={1.5} sx={{ py: 1 }}>
            <Box>
              <Typography variant="caption" color="text.secondary">
                Document Number
              </Typography>
              <Typography sx={{ fontWeight: 700, fontFamily: "monospace" }}>
                {documentQuery.data.timeAndMoneyDocumentNumber}
              </Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">
                Root Award
              </Typography>
              <Typography>{documentQuery.data.rootAwardNumber}</Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">
                Status
              </Typography>
              <Typography>{documentQuery.data.documentStatus ?? "—"}</Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">
                Created
              </Typography>
              <Typography>{documentQuery.data.creationDate ?? "—"}</Typography>
            </Box>
            <Alert severity="info" icon={<InfoOutlined fontSize="small" />}>
              This is a Time and Money workflow document, a different KEW
              document from this Award&rsquo;s own workflow document number.
              There is no live workflow connection in this archive - status
              and creation date are the only information available.
            </Alert>
          </Stack>
        )}
      </DialogContent>
    </Dialog>
  );
}

function TransactionDetailsDialog({
  awardId,
  pendingTransactionId,
  onClose,
}: {
  awardId: number;
  pendingTransactionId: number | null;
  onClose: () => void;
}) {
  const transactionQuery = useQuery({
    queryKey: [
      "award-time-and-money-transaction-v1",
      awardId,
      pendingTransactionId,
    ],
    queryFn: ({ signal }) =>
      getAwardTimeAndMoneyTransactionV1(
        awardId,
        pendingTransactionId ?? 0,
        signal,
      ),
    enabled: pendingTransactionId !== null,
  });

  return (
    <Dialog
      open={pendingTransactionId !== null}
      onClose={onClose}
      fullWidth
      maxWidth="md"
    >
      <DialogTitle>Transaction Details</DialogTitle>
      <DialogContent>
        {transactionQuery.isLoading && <LoadingState mode="compact" />}

        {transactionQuery.isError && (
          <ErrorState message="This transaction could not be loaded." />
        )}

        {transactionQuery.data && (
          <Stack spacing={2} sx={{ py: 1 }}>
            <Stack
              direction="row"
              spacing={1}
              sx={{ flexWrap: "wrap", gap: 1 }}
            >
              <Chip
                size="small"
                label={`Document ${
                  transactionQuery.data.timeAndMoneyDocumentNumber ?? "—"
                }`}
              />
              {transactionQuery.data.sourceAwardNumber && (
                <Chip
                  size="small"
                  variant="outlined"
                  label={`From ${transactionQuery.data.sourceAwardNumber}`}
                />
              )}
              {transactionQuery.data.destinationAwardNumber && (
                <Chip
                  size="small"
                  variant="outlined"
                  label={`To ${transactionQuery.data.destinationAwardNumber}`}
                />
              )}
            </Stack>

            {transactionQuery.data.sourceAwardNumber === null && (
              <Alert severity="info">
                This transaction&rsquo;s working-state details are no longer
                available (Kuali does not retain them indefinitely once
                processed). The permanent transaction ledger below is still
                complete.
              </Alert>
            )}

            <Box
              sx={{
                display: "grid",
                gridTemplateColumns: "1fr 1fr",
                gap: 2,
              }}
            >
              <Box>
                <Typography variant="caption" color="text.secondary">
                  Obligated (direct / indirect)
                </Typography>
                <Typography>
                  {formatCurrencyAmount(
                    transactionQuery.data.obligatedDirectAmount,
                  )}{" "}
                  /{" "}
                  {formatCurrencyAmount(
                    transactionQuery.data.obligatedIndirectAmount,
                  )}
                </Typography>
              </Box>
              <Box>
                <Typography variant="caption" color="text.secondary">
                  Anticipated (direct / indirect)
                </Typography>
                <Typography>
                  {formatCurrencyAmount(
                    transactionQuery.data.anticipatedDirectAmount,
                  )}{" "}
                  /{" "}
                  {formatCurrencyAmount(
                    transactionQuery.data.anticipatedIndirectAmount,
                  )}
                </Typography>
              </Box>
              <Box>
                <Typography variant="caption" color="text.secondary">
                  F&amp;A distribution period
                </Typography>
                <Typography>
                  {fandaDistributionPeriodLabel(
                    transactionQuery.data.fandaDistributionPeriod,
                  )}
                </Typography>
              </Box>
              <Box>
                <Typography variant="caption" color="text.secondary">
                  Comments
                </Typography>
                <Typography>
                  {transactionQuery.data.comments ?? "—"}
                </Typography>
              </Box>
            </Box>

            <Divider />

            <Typography variant="overline" color="text.secondary">
              Ledger rows ({transactionQuery.data.details.length})
            </Typography>

            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Type</TableCell>
                    <TableCell>Award</TableCell>
                    <TableCell>Source</TableCell>
                    <TableCell>Destination</TableCell>
                    <TableCell>Obligated</TableCell>
                    <TableCell>Anticipated</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {transactionQuery.data.details.map((detail) => (
                    <TableRow key={detail.transactionDetailId}>
                      <TableCell>
                        <Chip size="small" label={detail.transactionDetailType ?? "—"} />
                      </TableCell>
                      <TableCell>
                        {detail.awardNumber} (v{detail.sequenceNumber})
                      </TableCell>
                      <TableCell>{detail.sourceAwardNumber}</TableCell>
                      <TableCell>{detail.destinationAwardNumber}</TableCell>
                      <TableCell>
                        {formatCurrencyAmount(detail.obligatedAmount)}
                      </TableCell>
                      <TableCell>
                        {formatCurrencyAmount(detail.anticipatedAmount)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </Stack>
        )}
      </DialogContent>
    </Dialog>
  );
}

// Time & Money - Summary, Action Summary, History Timeline, Transaction
// Details, and Workflow Details (the last two as drill-down dialogs),
// all reading only already-archived data (no live KEW connection). See
// docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md. Budget is a
// separate, sibling concept under the Award - not linked here beyond a
// plain note, since Budget has no API of its own yet.
export function AwardTimeAndMoneySection({ awardId }: { awardId: number }) {
  const [tab, setTab] = useState<"actions" | "history">("actions");
  const [actionsPage, setActionsPage] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);
  const [selectedDocumentNumber, setSelectedDocumentNumber] = useState<
    string | null
  >(null);
  const [selectedTransactionId, setSelectedTransactionId] = useState<
    number | null
  >(null);

  const summaryQuery = useQuery({
    queryKey: ["award-time-and-money-summary-v1", awardId],
    queryFn: ({ signal }) => getAwardTimeAndMoneySummaryV1(awardId, signal),
  });

  const actionsQuery = useQuery({
    queryKey: ["award-time-and-money-actions-v1", awardId, actionsPage],
    queryFn: ({ signal }) =>
      getAwardTimeAndMoneyActionsV1(
        awardId,
        { page: actionsPage, size: PAGE_SIZE },
        signal,
      ),
    enabled: tab === "actions",
  });

  const historyQuery = useQuery({
    queryKey: ["award-time-and-money-history-v1", awardId, historyPage],
    queryFn: ({ signal }) =>
      getAwardTimeAndMoneyHistoryV1(
        awardId,
        { page: historyPage, size: PAGE_SIZE },
        signal,
      ),
    enabled: tab === "history",
  });

  if (summaryQuery.isLoading) {
    return <LoadingState mode="skeleton" height={280} />;
  }

  if (summaryQuery.isError || !summaryQuery.data) {
    return (
      <ErrorState message="Unable to load this Award's Time and Money summary." />
    );
  }

  const summary = summaryQuery.data;

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap", gap: 2 }}>
        <StatCard
          variant="outlined"
          minWidth={160}
          label="Obligated total"
          value={formatCurrencyAmount(summary.obligatedTotalAmount)}
        />
        <StatCard
          variant="outlined"
          minWidth={160}
          label="Anticipated total"
          value={formatCurrencyAmount(summary.anticipatedTotalAmount)}
        />
        <StatCard
          variant="outlined"
          minWidth={160}
          label="Time and Money actions (all versions)"
          value={String(summary.familyTransactionCount)}
        />
      </Stack>

      <Typography variant="caption" color="text.secondary">
        Totals above reflect this Award version (sequence{" "}
        {summary.sequenceNumber}) only.
      </Typography>

      <Typography color="text.secondary">
        {describeLastAction(summary)}
      </Typography>

      <Alert severity="info" variant="outlined">
        Budget history will be available in a future release.
      </Alert>

      <Divider />

      <Typography variant="caption" color="text.secondary">
        Actions and history below include all versions of Award{" "}
        {summary.awardNumber}, not just the one you&rsquo;re viewing.
      </Typography>

      <Tabs
        value={tab}
        onChange={(_event, value) => setTab(value)}
        sx={{ minHeight: 36 }}
      >
        <Tab
          value="actions"
          label="Action Summary"
          sx={{ minHeight: 36, py: 0.5 }}
        />
        <Tab
          value="history"
          label="History Timeline"
          sx={{ minHeight: 36, py: 0.5 }}
        />
      </Tabs>

      {tab === "actions" && (
        <ActionsTable
          actions={actionsQuery.data?.content ?? []}
          isLoading={actionsQuery.isLoading}
          isError={actionsQuery.isError}
          totalPages={actionsQuery.data?.totalPages ?? 0}
          page={actionsPage}
          onPageChange={setActionsPage}
          onSelectDocument={setSelectedDocumentNumber}
        />
      )}

      {tab === "history" && (
        <HistoryTable
          entries={historyQuery.data?.content ?? []}
          isLoading={historyQuery.isLoading}
          isError={historyQuery.isError}
          totalPages={historyQuery.data?.totalPages ?? 0}
          page={historyPage}
          onPageChange={setHistoryPage}
          onSelectTransaction={setSelectedTransactionId}
        />
      )}

      <WorkflowDocumentDialog
        awardId={awardId}
        timeAndMoneyDocumentNumber={selectedDocumentNumber}
        onClose={() => setSelectedDocumentNumber(null)}
      />

      <TransactionDetailsDialog
        awardId={awardId}
        pendingTransactionId={selectedTransactionId}
        onClose={() => setSelectedTransactionId(null)}
      />
    </Stack>
  );
}

function ActionsTable({
  actions,
  isLoading,
  isError,
  totalPages,
  page,
  onPageChange,
  onSelectDocument,
}: {
  actions: TimeAndMoneyActionV1[];
  isLoading: boolean;
  isError: boolean;
  totalPages: number;
  page: number;
  onPageChange: (page: number) => void;
  onSelectDocument: (documentNumber: string) => void;
}) {
  if (isLoading) {
    return <LoadingState mode="skeleton" height={220} />;
  }

  if (isError) {
    return <ErrorState message="Unable to load Time and Money actions." />;
  }

  if (actions.length === 0) {
    return (
      <EmptyState
        variant="text"
        message="No Time and Money actions are recorded for this Award."
      />
    );
  }

  return (
    <Stack spacing={2}>
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Document Number</TableCell>
              <TableCell>Type</TableCell>
              <TableCell>Notice Date</TableCell>
              <TableCell>Comments</TableCell>
              <TableCell>Updated By</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {actions.map((action) => (
              <TableRow
                key={action.awardAmountTransactionId}
                hover
                onClick={() =>
                  onSelectDocument(action.timeAndMoneyDocumentNumber)
                }
                sx={{ cursor: "pointer" }}
              >
                <TableCell sx={{ fontFamily: "monospace" }}>
                  {action.timeAndMoneyDocumentNumber}
                </TableCell>
                <TableCell>
                  {action.transactionTypeDescription ?? "—"}
                </TableCell>
                <TableCell>{action.noticeDate ?? "—"}</TableCell>
                <TableCell
                  sx={{
                    maxWidth: 280,
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                    whiteSpace: "nowrap",
                  }}
                >
                  {action.comments ?? "—"}
                </TableCell>
                <TableCell>{action.sourceUpdateUser ?? "—"}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      <PaginationFooter
        totalPages={totalPages}
        page={page}
        onPageChange={onPageChange}
      />
    </Stack>
  );
}

function HistoryTable({
  entries,
  isLoading,
  isError,
  totalPages,
  page,
  onPageChange,
  onSelectTransaction,
}: {
  entries: TimeAndMoneyHistoryEntryV1[];
  isLoading: boolean;
  isError: boolean;
  totalPages: number;
  page: number;
  onPageChange: (page: number) => void;
  onSelectTransaction: (pendingTransactionId: number) => void;
}) {
  if (isLoading) {
    return <LoadingState mode="skeleton" height={220} />;
  }

  if (isError) {
    return <ErrorState message="Unable to load Time and Money history." />;
  }

  if (entries.length === 0) {
    return (
      <EmptyState
        variant="text"
        message="No amount history is recorded for this Award."
      />
    );
  }

  return (
    <Stack spacing={2}>
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Version</TableCell>
              <TableCell>Obligated total</TableCell>
              <TableCell>Anticipated total</TableCell>
              <TableCell>Effective Date</TableCell>
              <TableCell>Document Number</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {entries.map((entry) => {
              const described = describeHistoryEntry(entry);
              const clickable =
                described.timeAndMoneyCreated &&
                entry.pendingTransactionId !== null;

              return (
                <TableRow
                  key={entry.awardAmountInfoId}
                  hover={clickable}
                  onClick={() =>
                    clickable &&
                    onSelectTransaction(entry.pendingTransactionId as number)
                  }
                  sx={clickable ? { cursor: "pointer" } : undefined}
                >
                  <TableCell>
                    <Stack spacing={0.25}>
                      <Stack
                        direction="row"
                        spacing={0.5}
                        sx={{ alignItems: "center" }}
                      >
                        <Chip
                          size="small"
                          label={`Seq ${entry.sequenceNumber}`}
                          title={`award_id ${entry.awardId}`}
                        />
                        {!described.versionsAgree && (
                          <Chip
                            size="small"
                            variant="outlined"
                            color="warning"
                            label={described.versionNote}
                          />
                        )}
                      </Stack>
                      {entry.originatingAwardVersion !== null && (
                        <Typography
                          variant="caption"
                          color="text.secondary"
                        >
                          Recorded against version{" "}
                          {entry.originatingAwardVersion}
                        </Typography>
                      )}
                    </Stack>
                  </TableCell>
                  <TableCell>
                    {formatCurrencyAmount(entry.obligatedTotalAmount)}
                  </TableCell>
                  <TableCell>
                    {formatCurrencyAmount(entry.anticipatedTotalAmount)}
                  </TableCell>
                  <TableCell>{entry.awardEffectiveDate ?? "—"}</TableCell>
                  <TableCell sx={{ fontFamily: "monospace" }}>
                    {entry.timeAndMoneyDocumentNumber ?? "—"}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>

      <PaginationFooter
        totalPages={totalPages}
        page={page}
        onPageChange={onPageChange}
      />
    </Stack>
  );
}
