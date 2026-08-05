import {
  AttachFileOutlined,
  DownloadOutlined,
  EmojiEventsOutlined,
  ExpandMoreOutlined,
  PaidOutlined,
  SearchOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Collapse,
  FormControl,
  InputLabel,
  Link as MuiLink,
  MenuItem,
  Paper,
  Select,
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
import {
  SAVED_SEARCHES,
  effectiveAwardAmount,
  effectiveAwardAmountBasis,
  formatCompactCurrency,
} from "../features/proposal/proposalDiscoveryPresentation.mjs";
import type { ExplorerProposalDiscoveryFilters } from "../types/api";

type TriState = "any" | "yes" | "no";

const PROPOSAL_TYPES = [
  "New",
  "Renewal",
  "Continuation",
  "Resubmission",
  "Supplement (Including NIH Revisions)",
  "Other Supplement",
  "Diversity Supplement",
  "Pre-Proposal",
];

const ACTIVITY_TYPES = [
  "Research",
  "Training",
  "Research Training",
  "Other Sponsored Activity",
  "Financial Aid",
];

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

function triStateToBoolean(value: TriState): boolean | undefined {
  if (value === "yes") return true;
  if (value === "no") return false;
  return undefined;
}

function booleanToTriState(value: boolean | undefined): TriState {
  if (value === true) return "yes";
  if (value === false) return "no";
  return "any";
}

// Proposal Explorer - "Archive Explorer → Proposals" in spirit (see
// ExplorerPage.tsx for the sibling identifier-lookup tool this is
// positioned alongside). Organized around the questions a research
// administrator actually asks - who was PI, who funded it, did it
// become an Award, is documentation available, how large was the
// Award - rather than around the query's own filter parameters.
export function ProposalDiscoveryPage() {
  const [hasAttachments, setHasAttachments] = useState<TriState>("any");
  const [hasFundedAward, setHasFundedAward] = useState<TriState>("any");
  const [minimumAwardAmount, setMinimumAwardAmount] = useState("");
  const [proposalStatus, setProposalStatus] = useState("");

  const [showAdvanced, setShowAdvanced] = useState(false);
  const [sponsorName, setSponsorName] = useState("");
  const [leadUnitNumber, setLeadUnitNumber] = useState("");
  const [personName, setPersonName] = useState("");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [proposalType, setProposalType] = useState("");
  const [activityType, setActivityType] = useState("");

  // Set only by a saved search - not reflected in any visible input,
  // since the friendly "Sponsor" field below always searches by name.
  const [presetSponsorCode, setPresetSponsorCode] = useState<
    string | undefined
  >(undefined);
  const [activePreset, setActivePreset] = useState<string | null>(null);

  const [appliedFilters, setAppliedFilters] =
    useState<ExplorerProposalDiscoveryFilters>({ page: 0, size: 50 });

  const discoveryQuery = useQuery({
    queryKey: ["explorer-proposal-discovery", appliedFilters],
    queryFn: ({ signal }) =>
      getExplorerProposalDiscovery(appliedFilters, signal),
  });

  function currentFormFilters(): ExplorerProposalDiscoveryFilters {
    return {
      hasAttachments: triStateToBoolean(hasAttachments),
      hasFundedAward: triStateToBoolean(hasFundedAward),
      minimumAwardAmount: minimumAwardAmount.trim()
        ? Number(minimumAwardAmount)
        : undefined,
      proposalStatus: proposalStatus.trim() || undefined,
      sponsorCode: presetSponsorCode,
      sponsorName: sponsorName.trim() || undefined,
      leadUnitNumber: leadUnitNumber.trim() || undefined,
      personName: personName.trim() || undefined,
      dateFrom: dateFrom.trim() || undefined,
      dateTo: dateTo.trim() || undefined,
      proposalType: proposalType.trim() || undefined,
      activityType: activityType.trim() || undefined,
      page: 0,
      size: 50,
    };
  }

  function runSearch() {
    setActivePreset(null);
    setAppliedFilters(currentFormFilters());
  }

  function applySavedSearch(preset: (typeof SAVED_SEARCHES)[number]) {
    setHasAttachments(booleanToTriState(preset.filters.hasAttachments));
    setHasFundedAward(booleanToTriState(preset.filters.hasFundedAward));
    setMinimumAwardAmount(
      preset.filters.minimumAwardAmount !== undefined
        ? String(preset.filters.minimumAwardAmount)
        : "",
    );
    setProposalStatus("");
    setSponsorName(preset.filters.sponsorName ?? "");
    setPresetSponsorCode(preset.filters.sponsorCode);
    setLeadUnitNumber("");
    setPersonName("");
    setDateFrom("");
    setDateTo("");
    setProposalType("");
    setActivityType("");
    setActivePreset(preset.key);
    setAppliedFilters({ ...preset.filters, page: 0, size: 50 });
  }

  const rows = discoveryQuery.data ?? [];

  return (
    <Stack spacing={3}>
      <Box>
        <Typography
          variant="caption"
          color="text.secondary"
          sx={{ textTransform: "uppercase", letterSpacing: "0.06em" }}
        >
          Archive Explorer
        </Typography>
        <Typography sx={{ fontSize: 20, fontWeight: 700 }}>
          Proposal Explorer
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
          Search active Institutional Proposals using business filters.
        </Typography>
      </Box>

      <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap" }}>
        {SAVED_SEARCHES.map((preset) => (
          <Chip
            key={preset.key}
            label={preset.label}
            clickable
            color={activePreset === preset.key ? "primary" : "default"}
            variant={activePreset === preset.key ? "filled" : "outlined"}
            onClick={() => applySavedSearch(preset)}
          />
        ))}
      </Stack>

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
              label="Award amount ≥"
              placeholder="e.g. 1000000"
              value={minimumAwardAmount}
              onChange={(event) => setMinimumAwardAmount(event.target.value)}
              type="number"
              sx={{ width: 200 }}
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

          <Button
            size="small"
            variant="text"
            endIcon={
              <ExpandMoreOutlined
                sx={{
                  transform: showAdvanced ? "rotate(180deg)" : "none",
                  transition: "transform 0.15s",
                }}
              />
            }
            onClick={() => setShowAdvanced((current) => !current)}
            sx={{ alignSelf: "flex-start" }}
          >
            Advanced Search
          </Button>

          <Collapse in={showAdvanced}>
            <Stack
              direction="row"
              spacing={2}
              sx={{ flexWrap: "wrap", pt: 1 }}
            >
              <TextField
                size="small"
                label="Sponsor"
                placeholder="e.g. NSF, National Institutes"
                value={sponsorName}
                onChange={(event) => {
                  setSponsorName(event.target.value);
                  setPresetSponsorCode(undefined);
                }}
                sx={{ width: 220 }}
              />
              <TextField
                size="small"
                label="Lead unit"
                value={leadUnitNumber}
                onChange={(event) => setLeadUnitNumber(event.target.value)}
                sx={{ width: 160 }}
              />
              <TextField
                size="small"
                label="PI"
                placeholder="Principal investigator name"
                value={personName}
                onChange={(event) => setPersonName(event.target.value)}
                sx={{ width: 200 }}
              />
              <TextField
                size="small"
                label="From"
                type="date"
                value={dateFrom}
                onChange={(event) => setDateFrom(event.target.value)}
                slotProps={{ inputLabel: { shrink: true } }}
                sx={{ width: 160 }}
              />
              <TextField
                size="small"
                label="To"
                type="date"
                value={dateTo}
                onChange={(event) => setDateTo(event.target.value)}
                slotProps={{ inputLabel: { shrink: true } }}
                sx={{ width: 160 }}
              />
              <FormControl size="small" sx={{ width: 180 }}>
                <InputLabel id="proposal-type-label">Proposal type</InputLabel>
                <Select
                  labelId="proposal-type-label"
                  label="Proposal type"
                  value={proposalType}
                  onChange={(event) => setProposalType(event.target.value)}
                >
                  <MenuItem value="">
                    <em>Any</em>
                  </MenuItem>
                  {PROPOSAL_TYPES.map((option) => (
                    <MenuItem key={option} value={option}>
                      {option}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
              <FormControl size="small" sx={{ width: 180 }}>
                <InputLabel id="activity-type-label">Activity type</InputLabel>
                <Select
                  labelId="activity-type-label"
                  label="Activity type"
                  value={activityType}
                  onChange={(event) => setActivityType(event.target.value)}
                >
                  <MenuItem value="">
                    <em>Any</em>
                  </MenuItem>
                  {ACTIVITY_TYPES.map((option) => (
                    <MenuItem key={option} value={option}>
                      {option}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Stack>
          </Collapse>

          <Stack direction="row" spacing={1.5}>
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
              onClick={() => downloadCsv(rows, "proposal-explorer")}
            >
              Download CSV
            </Button>
          </Stack>
        </Stack>
      </Paper>

      {discoveryQuery.isLoading && (
        <Box sx={{ display: "grid", placeItems: "center", py: 6 }}>
          <CircularProgress />
        </Box>
      )}

      {discoveryQuery.isError && (
        <Alert severity="error">Unable to load Proposals.</Alert>
      )}

      {!discoveryQuery.isLoading && !discoveryQuery.isError && (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Proposal</TableCell>
                <TableCell>Title</TableCell>
                <TableCell>Sponsor</TableCell>
                <TableCell>PI</TableCell>
                <TableCell align="center">Attachments</TableCell>
                <TableCell>Award</TableCell>
                <TableCell align="right">Amount</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7}>
                    <Typography color="text.secondary" sx={{ py: 2 }}>
                      No Proposals match these filters.
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
              {rows.map((row) => {
                const amount = effectiveAwardAmount(row);
                const amountBasis = effectiveAwardAmountBasis(row);
                return (
                  <TableRow key={row.proposalId} hover>
                    <TableCell>
                      <MuiLink
                        component={RouterLink}
                        to={`/proposals/dashboard/${row.proposalId}`}
                      >
                        {row.proposalNumber}
                      </MuiLink>
                    </TableCell>
                    <TableCell sx={{ maxWidth: 260 }}>
                      <Typography variant="body2" noWrap title={row.proposalTitle ?? ""}>
                        {row.proposalTitle ?? "—"}
                      </Typography>
                    </TableCell>
                    <TableCell sx={{ maxWidth: 180 }}>
                      <Typography variant="body2" noWrap title={row.sponsorName ?? ""}>
                        {row.sponsorName ?? "—"}
                      </Typography>
                    </TableCell>
                    <TableCell sx={{ maxWidth: 160 }}>
                      <Typography variant="body2" noWrap>
                        {row.principalInvestigatorName ?? "Not listed"}
                      </Typography>
                    </TableCell>
                    <TableCell align="center">
                      <MuiLink
                        component={RouterLink}
                        to={`/proposals/dashboard/${row.proposalId}?section=attachments`}
                        underline="hover"
                        color={
                          row.attachmentCount > 0
                            ? "text.primary"
                            : "text.disabled"
                        }
                      >
                        <Stack
                          direction="row"
                          spacing={0.5}
                          sx={{ alignItems: "center", justifyContent: "center" }}
                        >
                          <AttachFileOutlined fontSize="small" />
                          <Typography variant="body2">
                            {row.attachmentCount}
                          </Typography>
                        </Stack>
                      </MuiLink>
                    </TableCell>
                    <TableCell>
                      {row.navigableCurrentAwardId !== null ? (
                        <Stack spacing={0.25}>
                          <Stack
                            direction="row"
                            spacing={0.5}
                            sx={{ alignItems: "center" }}
                          >
                            <EmojiEventsOutlined
                              fontSize="small"
                              sx={{ color: "success.main" }}
                            />
                            <Typography variant="body2" sx={{ fontWeight: 600 }}>
                              {row.linkedAwardNumber}
                            </Typography>
                          </Stack>
                          <MuiLink
                            component={RouterLink}
                            to={`/awards/${row.navigableCurrentAwardId}`}
                            variant="caption"
                          >
                            Open →
                          </MuiLink>
                        </Stack>
                      ) : (
                        <Typography variant="body2" color="text.disabled">
                          Not yet funded
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell align="right">
                      {amount !== null ? (
                        <Stack
                          direction="row"
                          spacing={0.5}
                          sx={{ alignItems: "center", justifyContent: "flex-end" }}
                        >
                          <PaidOutlined
                            fontSize="small"
                            sx={{ color: "text.secondary" }}
                          />
                          <Typography variant="body2">
                            {formatCompactCurrency(amount)}
                            {amountBasis === "anticipated" && (
                              <Typography
                                component="span"
                                variant="caption"
                                color="text.secondary"
                              >
                                {" "}
                                (anticipated)
                              </Typography>
                            )}
                          </Typography>
                        </Stack>
                      ) : (
                        <Typography variant="body2" color="text.disabled">
                          —
                        </Typography>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
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
