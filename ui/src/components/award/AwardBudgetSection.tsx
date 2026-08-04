import {
  Alert,
  Box,
  Chip,
  Pagination,
  Skeleton,
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
  getAwardBudgetLineItemsV1,
  getAwardBudgetPeriodsV1,
  getAwardBudgetPersonnelV1,
  getAwardBudgetSummaryV1,
  getAwardBudgetVersionsV1,
} from "../../api/client";
import { formatCurrencyAmount } from "../../features/award/awardSectionsPresentation.mjs";
import {
  budgetScopeNote,
  hasAnyBudgetVersions,
  selectedBudgetLabel,
} from "../../features/award/budgetPresentation.mjs";
import type {
  AwardBudgetLineItemV1,
  AwardBudgetPersonnelV1,
  AwardBudgetVersionV1,
} from "../../types/api";

const PAGE_SIZE = 25;

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <Box
      sx={{
        border: "1px solid",
        borderColor: "divider",
        borderRadius: 2,
        p: 2,
        minWidth: 160,
      }}
    >
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography sx={{ fontWeight: 700, fontSize: 18 }}>{value}</Typography>
    </Box>
  );
}

function BudgetSummaryPanel({ awardId }: { awardId: number }) {
  const summaryQuery = useQuery({
    queryKey: ["award-budget-summary-v1", awardId],
    queryFn: ({ signal }) => getAwardBudgetSummaryV1(awardId, signal),
  });

  if (summaryQuery.isLoading) {
    return <Skeleton variant="rounded" height={120} />;
  }

  if (summaryQuery.isError || !summaryQuery.data) {
    return <Alert severity="error">Unable to load the Budget summary.</Alert>;
  }

  const summary = summaryQuery.data;
  const scopeNote = budgetScopeNote(summary);

  if (!summary.selectedBudgetId) {
    return (
      <Stack spacing={1.5}>
        <Typography color="text.secondary">
          {selectedBudgetLabel(summary)}
        </Typography>
        {scopeNote && (
          <Typography variant="caption" color="text.disabled">
            {scopeNote}
          </Typography>
        )}
      </Stack>
    );
  }

  return (
    <Stack spacing={2}>
      <Box>
        <Typography sx={{ fontWeight: 700 }}>
          {selectedBudgetLabel(summary)}
        </Typography>
        <Stack direction="row" spacing={1} sx={{ mt: 0.5, alignItems: "center" }}>
          {summary.statusDescription && (
            <Chip size="small" label={summary.statusDescription} />
          )}
          {summary.workflowDocumentNumber && (
            <Typography variant="caption" color="text.secondary">
              Workflow Document {summary.workflowDocumentNumber}
            </Typography>
          )}
        </Stack>
      </Box>

      <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap" }}>
        <StatCard
          label="Total Direct"
          value={formatCurrencyAmount(summary.totalDirectCost)}
        />
        <StatCard
          label="Total Indirect"
          value={formatCurrencyAmount(summary.totalIndirectCost)}
        />
        <StatCard
          label="Total Cost"
          value={formatCurrencyAmount(summary.totalCost)}
        />
      </Stack>

      <Typography variant="caption" color="text.secondary">
        {summary.startDate ?? "—"} – {summary.endDate ?? "—"}
      </Typography>

      {scopeNote && (
        <Typography variant="caption" color="text.disabled">
          {scopeNote}
        </Typography>
      )}
    </Stack>
  );
}

function BudgetVersionRow({ version }: { version: AwardBudgetVersionV1 }) {
  return (
    <TableRow hover selected={version.selected}>
      <TableCell>
        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
          <Typography sx={{ fontWeight: version.selected ? 700 : 400 }}>
            {version.budgetVersionNumber}
          </Typography>
          {version.selected && <Chip size="small" color="primary" label="Selected" />}
        </Stack>
      </TableCell>
      <TableCell>{version.workflowDocumentNumber ?? "—"}</TableCell>
      <TableCell>{version.statusDescription ?? "—"}</TableCell>
      <TableCell>
        <Typography variant="body2" title={`award_id ${version.owningAwardId}`}>
          Sequence {version.owningAwardSequenceNumber}
        </Typography>
      </TableCell>
      <TableCell align="right">
        {formatCurrencyAmount(version.totalCost)}
      </TableCell>
    </TableRow>
  );
}

function BudgetVersionsPanel({ awardId }: { awardId: number }) {
  const [page, setPage] = useState(0);

  const versionsQuery = useQuery({
    queryKey: ["award-budget-versions-v1", awardId, page],
    queryFn: ({ signal }) =>
      getAwardBudgetVersionsV1(awardId, { page, size: PAGE_SIZE }, signal),
  });

  if (versionsQuery.isLoading) {
    return <Skeleton variant="rounded" height={200} />;
  }

  if (versionsQuery.isError || !versionsQuery.data) {
    return <Alert severity="error">Unable to load Budget versions.</Alert>;
  }

  const { content, totalElements, totalPages } = versionsQuery.data;

  if (!hasAnyBudgetVersions(content) && page === 0) {
    return (
      <Typography color="text.secondary">
        No Budget versions recorded for this Award.
      </Typography>
    );
  }

  return (
    <Stack spacing={1.5}>
      <Typography variant="caption" color="text.secondary">
        {totalElements.toLocaleString()} Budget version
        {totalElements === 1 ? "" : "s"}
      </Typography>

      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Version</TableCell>
              <TableCell>Workflow Document</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Owning Award Sequence</TableCell>
              <TableCell align="right">Total Cost</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {content.map((version) => (
              <BudgetVersionRow key={version.budgetId} version={version} />
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      {totalPages > 1 && (
        <Stack sx={{ alignItems: "center", mt: 1 }}>
          <Pagination
            count={totalPages}
            page={page + 1}
            onChange={(_event, value) => setPage(value - 1)}
          />
        </Stack>
      )}
    </Stack>
  );
}

function BudgetPeriodsPanel({ awardId }: { awardId: number }) {
  const periodsQuery = useQuery({
    queryKey: ["award-budget-periods-v1", awardId],
    queryFn: ({ signal }) => getAwardBudgetPeriodsV1(awardId, signal),
  });

  if (periodsQuery.isLoading) {
    return <Skeleton variant="rounded" height={160} />;
  }

  if (periodsQuery.isError || !periodsQuery.data) {
    return <Alert severity="error">Unable to load Budget periods.</Alert>;
  }

  const periods = periodsQuery.data;

  if (periods.length === 0) {
    return (
      <Typography color="text.secondary">
        No Budget periods recorded for this Award's selected Budget.
      </Typography>
    );
  }

  return (
    <TableContainer>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Budget Period</TableCell>
            <TableCell>Start</TableCell>
            <TableCell>End</TableCell>
            <TableCell align="right">Direct</TableCell>
            <TableCell align="right">Indirect</TableCell>
            <TableCell align="right">Total</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {periods.map((period) => (
            <TableRow key={period.budgetPeriodId} hover>
              <TableCell>{period.periodNumber ?? "—"}</TableCell>
              <TableCell>{period.startDate ?? "—"}</TableCell>
              <TableCell>{period.endDate ?? "—"}</TableCell>
              <TableCell align="right">
                {formatCurrencyAmount(period.totalDirectCost)}
              </TableCell>
              <TableCell align="right">
                {formatCurrencyAmount(period.totalIndirectCost)}
              </TableCell>
              <TableCell align="right">
                {formatCurrencyAmount(period.totalCost)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

function BudgetLineItemRow({ lineItem }: { lineItem: AwardBudgetLineItemV1 }) {
  return (
    <TableRow hover>
      <TableCell>{lineItem.lineItemNumber ?? "—"}</TableCell>
      <TableCell>{lineItem.description ?? "—"}</TableCell>
      <TableCell>{lineItem.costElement ?? "—"}</TableCell>
      <TableCell>
        {lineItem.startDate ?? "—"} – {lineItem.endDate ?? "—"}
      </TableCell>
      <TableCell align="right">
        {formatCurrencyAmount(lineItem.lineItemCost)}
      </TableCell>
      <TableCell align="right">
        {formatCurrencyAmount(lineItem.costSharingAmount)}
      </TableCell>
    </TableRow>
  );
}

function BudgetLineItemsPanel({ awardId }: { awardId: number }) {
  const [page, setPage] = useState(0);

  const lineItemsQuery = useQuery({
    queryKey: ["award-budget-line-items-v1", awardId, page],
    queryFn: ({ signal }) =>
      getAwardBudgetLineItemsV1(awardId, { page, size: PAGE_SIZE }, signal),
  });

  if (lineItemsQuery.isLoading) {
    return <Skeleton variant="rounded" height={200} />;
  }

  if (lineItemsQuery.isError || !lineItemsQuery.data) {
    return <Alert severity="error">Unable to load Budget line items.</Alert>;
  }

  const { content, totalElements, totalPages } = lineItemsQuery.data;

  if (content.length === 0 && page === 0) {
    return (
      <Typography color="text.secondary">
        No Budget line items recorded for this Award's selected Budget.
      </Typography>
    );
  }

  return (
    <Stack spacing={1.5}>
      <Typography variant="caption" color="text.secondary">
        {totalElements.toLocaleString()} line item
        {totalElements === 1 ? "" : "s"}
      </Typography>

      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>#</TableCell>
              <TableCell>Description</TableCell>
              <TableCell>Cost Element</TableCell>
              <TableCell>Period</TableCell>
              <TableCell align="right">Cost</TableCell>
              <TableCell align="right">Cost Sharing</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {content.map((lineItem) => (
              <BudgetLineItemRow
                key={lineItem.budgetLineItemId}
                lineItem={lineItem}
              />
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      {totalPages > 1 && (
        <Stack sx={{ alignItems: "center", mt: 1 }}>
          <Pagination
            count={totalPages}
            page={page + 1}
            onChange={(_event, value) => setPage(value - 1)}
          />
        </Stack>
      )}
    </Stack>
  );
}

function BudgetPersonnelRow({
  person,
}: {
  person: AwardBudgetPersonnelV1;
}) {
  return (
    <TableRow hover>
      <TableCell>{person.fullName ?? "—"}</TableCell>
      <TableCell>{person.jobCode ?? "—"}</TableCell>
      <TableCell>{person.appointmentType ?? "—"}</TableCell>
      <TableCell align="right">
        {formatCurrencyAmount(person.baseSalary)}
      </TableCell>
      <TableCell align="right">
        {formatCurrencyAmount(person.calculatedSalary)}
      </TableCell>
    </TableRow>
  );
}

function BudgetPersonnelPanel({ awardId }: { awardId: number }) {
  const [page, setPage] = useState(0);

  const personnelQuery = useQuery({
    queryKey: ["award-budget-personnel-v1", awardId, page],
    queryFn: ({ signal }) =>
      getAwardBudgetPersonnelV1(awardId, { page, size: PAGE_SIZE }, signal),
  });

  if (personnelQuery.isLoading) {
    return <Skeleton variant="rounded" height={200} />;
  }

  if (personnelQuery.isError || !personnelQuery.data) {
    return <Alert severity="error">Unable to load Budget personnel.</Alert>;
  }

  const { content, totalElements, totalPages } = personnelQuery.data;

  if (content.length === 0 && page === 0) {
    return (
      <Typography color="text.secondary">
        No personnel recorded for this Award's selected Budget.
      </Typography>
    );
  }

  return (
    <Stack spacing={1.5}>
      <Typography variant="caption" color="text.secondary">
        {totalElements.toLocaleString()} personnel entr
        {totalElements === 1 ? "y" : "ies"}
      </Typography>

      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>Job Code</TableCell>
              <TableCell>Appointment Type</TableCell>
              <TableCell align="right">Base Salary</TableCell>
              <TableCell align="right">Calculated Salary</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {content.map((person) => (
              <BudgetPersonnelRow
                key={person.budgetPersonId}
                person={person}
              />
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      {totalPages > 1 && (
        <Stack sx={{ alignItems: "center", mt: 1 }}>
          <Pagination
            count={totalPages}
            page={page + 1}
            onChange={(_event, value) => setPage(value - 1)}
          />
        </Stack>
      )}
    </Stack>
  );
}

const CHILD_TABS = [
  { key: "versions", label: "Budget Versions" },
  { key: "periods", label: "Budget Periods" },
  { key: "lineItems", label: "Line Items" },
  { key: "personnel", label: "Personnel" },
] as const;

type ChildTabKey = (typeof CHILD_TABS)[number]["key"];

// Award Budget - see docs/kuali-business-rules/Budget.md. Every child
// view (versions/periods/line items/personnel) is scoped to this
// Award's own award_number family, bounded to sequences <= the
// version being viewed - never the exact awardId alone. Never renders
// hierarchy-wide totals; the selected Budget's totals are Oracle's own
// persisted values, never recalculated here.
export function AwardBudgetSection({ awardId }: { awardId: number }) {
  const [activeTab, setActiveTab] = useState<ChildTabKey>("versions");

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="overline" color="text.secondary">
          Budget Summary
        </Typography>
        <Box sx={{ mt: 1 }}>
          <BudgetSummaryPanel awardId={awardId} />
        </Box>
      </Box>

      <Box>
        <Tabs
          value={activeTab}
          onChange={(_event, value: ChildTabKey) => setActiveTab(value)}
          sx={{ minHeight: 36, mb: 2 }}
        >
          {CHILD_TABS.map((tab) => (
            <Tab
              key={tab.key}
              value={tab.key}
              label={tab.label}
              sx={{ minHeight: 36, py: 0.5, fontSize: 13 }}
            />
          ))}
        </Tabs>

        {activeTab === "versions" && <BudgetVersionsPanel awardId={awardId} />}
        {activeTab === "periods" && <BudgetPeriodsPanel awardId={awardId} />}
        {activeTab === "lineItems" && (
          <BudgetLineItemsPanel awardId={awardId} />
        )}
        {activeTab === "personnel" && (
          <BudgetPersonnelPanel awardId={awardId} />
        )}
      </Box>
    </Stack>
  );
}
