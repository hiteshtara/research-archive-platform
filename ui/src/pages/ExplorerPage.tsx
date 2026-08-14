import {
  ContentCopyOutlined,
  DownloadOutlined,
  SearchOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Divider,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";

import {
  ApiRequestError,
  getExplorerAward,
  getExplorerAwardContacts,
  getExplorerAwardVersion,
  getExplorerAttachments,
  getExplorerPerson,
  getExplorerRolodex,
  getExplorerSponsors,
  getExplorerUnit,
  getExplorerUnitAdministrators,
  getExplorerWorkflow,
} from "../api/client";
import type {
  ExplorerAward,
  ExplorerAwardContacts,
  ExplorerPerson,
  ExplorerRolodex,
  ExplorerUnit,
  ExplorerUnitAdministrator,
  AwardAttachmentV1,
  AwardDocumentNumberMatchV1,
} from "../types/api";
import {
  RESOURCE_DEFINITIONS,
  buildCrossLinks,
  resourceDefinition,
  toCsv,
} from "../features/explorer/explorerPresentation.mjs";
import type {
  ExplorerCrossLink,
  ExplorerResourceKey,
} from "../features/explorer/explorerPresentation.d.mts";
import { useAttachmentAccess } from "../hooks/useAttachmentAccess";

type ExplorerData =
  | ExplorerAward
  | AwardDocumentNumberMatchV1
  | ExplorerUnit
  | ExplorerUnitAdministrator[]
  | ExplorerAward[]
  | ExplorerAwardContacts
  | ExplorerPerson
  | ExplorerRolodex
  | AwardAttachmentV1[];

// Fetchers keyed by resource - each is a fixed, predefined lookup by
// natural identifier (award number, unit number, person ID, ...), never
// an arbitrary query. Numeric identifiers are parsed here since every
// text field on this generic shell is a plain string.
const FETCHERS: Record<
  ExplorerResourceKey,
  (identifier: string, signal?: AbortSignal) => Promise<ExplorerData>
> = {
  award: (identifier, signal) => getExplorerAward(identifier, signal),
  "award-version": (identifier, signal) =>
    getExplorerAwardVersion(Number(identifier), signal),
  workflow: (identifier, signal) => getExplorerWorkflow(identifier, signal),
  unit: (identifier, signal) => getExplorerUnit(identifier, signal),
  "unit-administrators": (identifier, signal) =>
    getExplorerUnitAdministrators(identifier, signal),
  "award-contacts": (identifier, signal) =>
    getExplorerAwardContacts(Number(identifier), signal),
  person: (identifier, signal) => getExplorerPerson(identifier, signal),
  rolodex: (identifier, signal) =>
    getExplorerRolodex(Number(identifier), signal),
  sponsor: (identifier, signal) => getExplorerSponsors(identifier, signal),
  attachments: (identifier, signal) =>
    getExplorerAttachments(Number(identifier), signal),
};

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

function CopyIdentifierButton({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);

  return (
    <Button
      size="small"
      variant="outlined"
      startIcon={<ContentCopyOutlined fontSize="small" />}
      onClick={() => {
        void navigator.clipboard.writeText(value).then(() => {
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        });
      }}
    >
      {copied ? "Copied" : "Copy identifier"}
    </Button>
  );
}

function KeyValueGrid({ record }: { record: object }) {
  const entries = Object.entries(record as Record<string, unknown>).filter(
    ([, value]) => !Array.isArray(value) && typeof value !== "object",
  );

  return (
    <Box
      sx={{
        display: "grid",
        gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr", md: "1fr 1fr 1fr" },
        gap: 2,
      }}
    >
      {entries.map(([key, value]) => (
        <Box key={key}>
          <Typography variant="caption" color="text.secondary">
            {key}
          </Typography>
          <Typography sx={{ fontWeight: 600, wordBreak: "break-word" }}>
            {value === null || value === undefined || value === ""
              ? "—"
              : String(value)}
          </Typography>
        </Box>
      ))}
    </Box>
  );
}

function ResultTable({
  title,
  rows,
  filenamePrefix,
}: {
  title: string;
  rows: ReadonlyArray<object> | null | undefined;
  filenamePrefix: string;
}) {
  if (!Array.isArray(rows) || rows.length === 0) {
    return (
      <Box sx={{ py: 2 }}>
        <Typography variant="overline" color="text.secondary">
          {title}
        </Typography>
        <Typography color="text.secondary">No rows.</Typography>
      </Box>
    );
  }

  const dataRows = rows as ReadonlyArray<Record<string, unknown>>;
  const columns = Object.keys(dataRows[0]);

  return (
    <Box>
      <Stack
        direction="row"
        sx={{ alignItems: "center", justifyContent: "space-between", mb: 1 }}
      >
        <Typography variant="overline" color="text.secondary">
          {title} ({rows.length})
        </Typography>
        <Button
          size="small"
          startIcon={<DownloadOutlined fontSize="small" />}
          onClick={() => downloadCsv(rows, filenamePrefix)}
        >
          Download CSV
        </Button>
      </Stack>

      <TableContainer sx={{ maxHeight: 420 }}>
        <Table size="small" stickyHeader>
          <TableHead>
            <TableRow>
              {columns.map((column) => (
                <TableCell key={column}>{column}</TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {dataRows.map((row, index) => (
              // eslint-disable-next-line react/no-array-index-key
              <TableRow key={index} hover>
                {columns.map((column) => (
                  <TableCell key={column}>
                    {row[column] === null || row[column] === undefined
                      ? "—"
                      : String(row[column])}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
}

function CrossLinks({
  links,
  onNavigate,
}: {
  links: ExplorerCrossLink[];
  onNavigate: (resource: ExplorerResourceKey, identifier: string) => void;
}) {
  if (links.length === 0) {
    return null;
  }

  return (
    <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
      {links.map((link) => (
        <Chip
          key={`${link.resource}-${link.identifier}-${link.label}`}
          label={link.label}
          size="small"
          variant="outlined"
          clickable
          onClick={() => onNavigate(link.resource, link.identifier)}
        />
      ))}
    </Stack>
  );
}

function ResourceResult({
  resourceKey,
  data,
  onNavigate,
}: {
  resourceKey: ExplorerResourceKey;
  data: ExplorerData;
  onNavigate: (resource: ExplorerResourceKey, identifier: string) => void;
}) {
  const definition = resourceDefinition(resourceKey);
  const crossLinks = useMemo(
    () => buildCrossLinks(resourceKey, data),
    [resourceKey, data],
  );

  let body: React.ReactNode;
  switch (resourceKey) {
    case "unit-administrators":
    case "sponsor":
    case "attachments":
      body = (
        <ResultTable
          title={definition?.label ?? resourceKey}
          rows={data as ReadonlyArray<object>}
          filenamePrefix={`explorer-${resourceKey}`}
        />
      );
      break;

    case "unit": {
      const unit = data as ExplorerUnit;
      body = (
        <Stack spacing={3}>
          <KeyValueGrid record={unit} />
          <Divider />
          <ResultTable
            title="Administrators"
            rows={unit.administrators}
            filenamePrefix="explorer-unit-administrators"
          />
        </Stack>
      );
      break;
    }

    case "award-contacts": {
      const contacts = data as ExplorerAwardContacts;
      body = (
        <Stack spacing={3}>
          <KeyValueGrid record={contacts.award} />
          <Divider />
          <KeyValueGrid record={contacts.unitDetails} />
          <Divider />
          <ResultTable
            title="Key Personnel"
            rows={contacts.keyPersonnel}
            filenamePrefix="explorer-key-personnel"
          />
          <ResultTable
            title="Unit Contacts"
            rows={contacts.unitContacts}
            filenamePrefix="explorer-unit-contacts"
          />
          <ResultTable
            title="Sponsor Contacts"
            rows={contacts.sponsorContacts}
            filenamePrefix="explorer-sponsor-contacts"
          />
          <ResultTable
            title="Central Administration Contacts"
            rows={contacts.centralAdministrationContacts}
            filenamePrefix="explorer-central-administration-contacts"
          />
        </Stack>
      );
      break;
    }

    default:
      body = <KeyValueGrid record={data as object} />;
  }

  return (
    <Stack spacing={3}>
      {body}
      {crossLinks.length > 0 && (
        <Box>
          <Typography
            variant="overline"
            color="text.secondary"
            sx={{ display: "block", mb: 1 }}
          >
            Related
          </Typography>
          <CrossLinks links={crossLinks} onNavigate={onNavigate} />
        </Box>
      )}
    </Stack>
  );
}

export function ExplorerPage() {
  const attachmentAccess = useAttachmentAccess();
  const [searchParams, setSearchParams] = useSearchParams();

  const resourceKey = (searchParams.get("resource") ||
    "award") as ExplorerResourceKey;
  const urlIdentifier = searchParams.get("identifier") ?? "";
  const isAttachmentsResource = resourceKey === "attachments";
  const attachmentsResourceDenied = isAttachmentsResource && !attachmentAccess;

  const [identifierInput, setIdentifierInput] = useState(urlIdentifier);
  const [view, setView] = useState<"structured" | "json">("structured");

  const definition = resourceDefinition(resourceKey) ?? RESOURCE_DEFINITIONS[0];
  // "attachments" is hidden from the dropdown entirely for a user
  // without the group - a dev-only-tool convenience, same as every
  // other attachment-hiding spot; the real gate is the backend 403 the
  // underlying endpoint already enforces.
  const selectableResourceDefinitions = attachmentAccess
    ? RESOURCE_DEFINITIONS
    : RESOURCE_DEFINITIONS.filter((entry) => entry.key !== "attachments");

  const explorerQuery = useQuery({
    queryKey: ["explorer", resourceKey, urlIdentifier],
    queryFn: ({ signal }) => FETCHERS[resourceKey](urlIdentifier, signal),
    enabled: urlIdentifier.trim().length > 0 && !attachmentsResourceDenied,
  });

  function navigateTo(
    nextResource: ExplorerResourceKey,
    nextIdentifier: string,
  ) {
    setIdentifierInput(nextIdentifier);
    setSearchParams({ resource: nextResource, identifier: nextIdentifier });
  }

  function runSearch() {
    if (!identifierInput.trim()) {
      return;
    }
    setSearchParams({
      resource: resourceKey,
      identifier: identifierInput.trim(),
    });
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Chip label="Development" size="small" color="primary" sx={{ mb: 1.5 }} />
        <Typography variant="h4" sx={{ fontWeight: 700 }}>
          Archive Explorer
        </Typography>
        <Typography color="text.secondary">
          Read-only lookups over already-archived data by fixed identifier -
          no arbitrary SQL. Backs onto the same repositories as the rest of
          the API; see docs/ARCHIVE_EXPLORER.md.
        </Typography>
      </Box>

      <Card>
        <CardContent>
          <Stack
            direction={{ xs: "column", md: "row" }}
            spacing={2}
            sx={{ alignItems: { md: "flex-end" } }}
          >
            <TextField
              select
              label="Resource"
              value={resourceKey}
              onChange={(event) => {
                setSearchParams({
                  resource: event.target.value,
                  identifier: "",
                });
                setIdentifierInput("");
              }}
              sx={{ minWidth: 220 }}
            >
              {selectableResourceDefinitions.map((entry) => (
                <MenuItem key={entry.key} value={entry.key}>
                  {entry.label}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              fullWidth
              label={definition.identifierLabel}
              value={identifierInput}
              onChange={(event) => setIdentifierInput(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  runSearch();
                }
              }}
            />

            <Button
              variant="contained"
              startIcon={<SearchOutlined />}
              onClick={runSearch}
              sx={{ height: 56, px: 3 }}
            >
              Search
            </Button>
          </Stack>
        </CardContent>
      </Card>

      {urlIdentifier.trim().length > 0 && (
        <Card>
          <Box sx={{ px: 3, py: 2 }}>
            <Stack
              direction="row"
              sx={{
                alignItems: "center",
                justifyContent: "space-between",
                flexWrap: "wrap",
                gap: 1,
              }}
            >
              <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <Typography variant="h6">{definition.label}</Typography>
                <Chip label={urlIdentifier} size="small" variant="outlined" />
              </Stack>

              <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <CopyIdentifierButton value={urlIdentifier} />
                <ToggleButtonGroup
                  size="small"
                  exclusive
                  value={view}
                  onChange={(_event, value: "structured" | "json" | null) =>
                    value && setView(value)
                  }
                >
                  <ToggleButton value="structured">Structured</ToggleButton>
                  <ToggleButton value="json">JSON</ToggleButton>
                </ToggleButtonGroup>
              </Stack>
            </Stack>
          </Box>

          <Divider />

          <Box sx={{ p: 3 }}>
            {attachmentsResourceDenied && (
              <Alert severity="warning">
                Access denied. Looking up attachments requires membership
                in the ArchiveAttachmentViewer group - contact an
                administrator if you believe this is wrong.
              </Alert>
            )}

            {!attachmentsResourceDenied && explorerQuery.isLoading && (
              <Box sx={{ display: "grid", placeItems: "center", py: 6 }}>
                <CircularProgress />
              </Box>
            )}

            {!attachmentsResourceDenied && explorerQuery.isError && (
              <Alert severity="error">
                {explorerQuery.error instanceof ApiRequestError &&
                explorerQuery.error.status === 404
                  ? `No ${definition.label} found for ${definition.identifierLabel.toLowerCase()} "${urlIdentifier}".`
                  : "This lookup could not be completed. Try again in a moment."}
              </Alert>
            )}

            {!attachmentsResourceDenied &&
              explorerQuery.data !== undefined &&
              (view === "json" ? (
                <Box
                  component="pre"
                  sx={{
                    m: 0,
                    p: 2,
                    borderRadius: 1,
                    backgroundColor: "grey.100",
                    overflowX: "auto",
                    fontSize: 13,
                  }}
                >
                  {JSON.stringify(explorerQuery.data, null, 2)}
                </Box>
              ) : (
                <ResourceResult
                  resourceKey={resourceKey}
                  data={explorerQuery.data}
                  onNavigate={navigateTo}
                />
              ))}
          </Box>
        </Card>
      )}
    </Stack>
  );
}
