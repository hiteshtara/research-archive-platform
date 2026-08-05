import { DownloadOutlined, SearchOutlined } from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Checkbox,
  CircularProgress,
  FormControlLabel,
  Link as MuiLink,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link as RouterLink } from "react-router-dom";

import { getExplorerProposalDiscovery } from "../api/client";
import { toCsv } from "../features/explorer/explorerPresentation.mjs";
import type { ExplorerProposalDiscoveryFilters } from "../types/api";

// Tri-state (not just boolean) so "omit this filter" stays a real,
// distinct option, matching the API's own "omitted = no filter"
// contract - a plain checkbox can't express "unset".
type TriState = "any" | "yes" | "no";

function downloadCsv(
  rows: ReadonlyArray<object> | null | undefined,
  filenamePrefix: string,
): void {
  const csv = toCsv(rows);
  if (!csv) {
    return;
  }
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `${filenamePrefix}.csv`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

function formatCurrency(value: number | null): string {
  if (value === null) {
    return "—";
  }
  return value.toLocaleString(undefined, {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: 0,
  });
}

// Proposal Discovery - a relational-joins-only (no embeddings/vector
// search) filter tool over the ACTIVE version of every Institutional
// Proposal family, resolved through archive.proposal_award to its
// linked Award's CURRENT version and that version's latest amount
// snapshot (see AwardArchiveRepository.findProposalDiscoveryRows).
// exactLinkedAwardId is preserved on every row for audit but never
// rendered as a link target - only navigableCurrentAwardId is.
export function ProposalDiscoveryPage() {
  const [hasAttachments, setHasAttachments] = useState<TriState>("any");
  const [hasFundedAward, setHasFundedAward] = useState<TriState>("any");
  const [minimumAwardAmount, setMinimumAwardAmount] = useState("");
  const [sponsorCode, setSponsorCode] = useState("");
  const [leadUnitNumber, setLeadUnitNumber] = useState("");
  const [proposalStatus, setProposalStatus] = useState("");
  const [showAuditIds, setShowAuditIds] = useState(false);

  const [appliedFilters, setAppliedFilters] =
    useState<ExplorerProposalDiscoveryFilters>({ page: 0, size: 50 });

  const discoveryQuery = useQuery({
    queryKey: ["explorer-proposal-discovery", appliedFilters],
    queryFn: ({ signal }) =>
      getExplorerProposalDiscovery(appliedFilters, signal),
  });

  function triStateToBoolean(value: TriState): boolean | undefined {
    if (value === "yes") return true;
    if (value === "no") return false;
    return undefined;
  }

  function runSearch() {
    setAppliedFilters({
      hasAttachments: triStateToBoolean(hasAttachments),
      hasFundedAward: triStateToBoolean(hasFundedAward),
      minimumAwardAmount: minimumAwardAmount.trim()
        ? Number(minimumAwardAmount)
        : undefined,
      sponsorCode: sponsorCode.trim() || undefined,
      leadUnitNumber: leadUnitNumber.trim() || undefined,
      proposalStatus: proposalStatus.trim() || undefined,
      page: 0,
      size: 50,
    });
  }

  const rows = discoveryQuery.data ?? [];

  return (
    <Stack spacing={3}>
      <Box>
        <Typography sx={{ fontSize: 20, fontWeight: 700 }}>
          Proposal Discovery
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
          Active Institutional Proposals, filtered by attachments, funded
          Award linkage, Award amount, sponsor, unit, and status. Relational
          joins only - no embeddings or vector search.
        </Typography>
      </Box>

      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Stack spacing={2}>
          <Stack direction="row" spacing={3} sx={{ flexWrap: "wrap" }}>
            <TriStateFilter
              label="Has attachments"
              value={hasAttachments}
              onChange={setHasAttachments}
            />
            <TriStateFilter
              label="Has funded Award"
              value={hasFundedAward}
              onChange={setHasFundedAward}
            />
          </Stack>

          <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap" }}>
            <TextField
              size="small"
              label="Minimum Award amount"
              placeholder="e.g. 1000000"
              value={minimumAwardAmount}
              onChange={(event) => setMinimumAwardAmount(event.target.value)}
              type="number"
              sx={{ width: 220 }}
            />
            <TextField
              size="small"
              label="Sponsor code"
              value={sponsorCode}
              onChange={(event) => setSponsorCode(event.target.value)}
              sx={{ width: 180 }}
            />
            <TextField
              size="small"
              label="Lead unit number"
              value={leadUnitNumber}
              onChange={(event) => setLeadUnitNumber(event.target.value)}
              sx={{ width: 180 }}
            />
            <TextField
              size="small"
              label="Proposal status"
              placeholder="e.g. Funded"
              value={proposalStatus}
              onChange={(event) => setProposalStatus(event.target.value)}
              sx={{ width: 180 }}
            />
          </Stack>

          <Stack direction="row" spacing={1.5} sx={{ alignItems: "center" }}>
            <Button
              variant="contained"
              startIcon={<SearchOutlined />}
              onClick={runSearch}
            >
              Search
            </Button>
            <Button
              variant="outlined"
              startIcon={<DownloadOutlined />}
              disabled={rows.length === 0}
              onClick={() => downloadCsv(rows, "proposal-discovery")}
            >
              Download CSV
            </Button>
            <FormControlLabel
              sx={{ ml: "auto" }}
              control={
                <Checkbox
                  size="small"
                  checked={showAuditIds}
                  onChange={(event) => setShowAuditIds(event.target.checked)}
                />
              }
              label="Show audit IDs"
            />
          </Stack>
        </Stack>
      </Paper>

      {discoveryQuery.isLoading && (
        <Box sx={{ display: "grid", placeItems: "center", py: 6 }}>
          <CircularProgress />
        </Box>
      )}

      {discoveryQuery.isError && (
        <Alert severity="error">Unable to load Proposal discovery results.</Alert>
      )}

      {!discoveryQuery.isLoading && !discoveryQuery.isError && (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Proposal</TableCell>
                <TableCell>Title</TableCell>
                <TableCell>Workflow #</TableCell>
                <TableCell align="right">Attachments</TableCell>
                <TableCell>Linked Award</TableCell>
                <TableCell align="right">Obligated</TableCell>
                <TableCell align="right">Anticipated</TableCell>
                {showAuditIds && <TableCell>Audit (exact linked ID)</TableCell>}
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.length === 0 && (
                <TableRow>
                  <TableCell colSpan={showAuditIds ? 8 : 7}>
                    <Typography color="text.secondary" sx={{ py: 2 }}>
                      No Proposals match these filters.
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
              {rows.map((row) => (
                <TableRow key={row.proposalId} hover>
                  <TableCell>
                    <MuiLink
                      component={RouterLink}
                      to={`/proposals/dashboard/${row.proposalId}`}
                    >
                      {row.proposalNumber}
                    </MuiLink>
                  </TableCell>
                  <TableCell sx={{ maxWidth: 280 }}>
                    {row.proposalTitle ?? "—"}
                  </TableCell>
                  <TableCell>{row.workflowDocumentNumber ?? "—"}</TableCell>
                  <TableCell align="right">{row.attachmentCount}</TableCell>
                  <TableCell>
                    {row.navigableCurrentAwardId !== null ? (
                      <MuiLink
                        component={RouterLink}
                        to={`/awards/${row.navigableCurrentAwardId}`}
                      >
                        {row.linkedAwardNumber ?? row.navigableCurrentAwardId}
                      </MuiLink>
                    ) : (
                      "—"
                    )}
                  </TableCell>
                  <TableCell align="right">
                    {formatCurrency(row.obligatedAmount)}
                  </TableCell>
                  <TableCell align="right">
                    {formatCurrency(row.anticipatedAmount)}
                  </TableCell>
                  {showAuditIds && (
                    <TableCell>
                      <Typography variant="caption" color="text.secondary">
                        {row.exactLinkedAwardId ?? "—"}
                      </Typography>
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Stack>
  );
}

function TriStateFilter({
  label,
  value,
  onChange,
}: {
  label: string;
  value: TriState;
  onChange: (value: TriState) => void;
}) {
  return (
    <Stack>
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ textTransform: "uppercase", letterSpacing: "0.04em" }}
      >
        {label}
      </Typography>
      <Stack direction="row" spacing={0.5}>
        {(["any", "yes", "no"] as const).map((option) => (
          <Button
            key={option}
            size="small"
            variant={value === option ? "contained" : "outlined"}
            onClick={() => onChange(option)}
            sx={{ minWidth: 56 }}
          >
            {option === "any" ? "Any" : option === "yes" ? "Yes" : "No"}
          </Button>
        ))}
      </Stack>
    </Stack>
  );
}
