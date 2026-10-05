import ExpandMoreOutlined from "@mui/icons-material/ExpandMoreOutlined";
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  Divider,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useState } from "react";

import snapshot from "../features/qa/qaSnapshot.json";
import securityData from "../features/qa/securityRequirements.json";
import type {
  QaCase,
  QaStatusKey,
  SecurityRequirement,
} from "../features/qa/qaStatusPresentation.mjs";
import {
  caseAreas,
  countByStatus,
  deployedEvidenceCount,
  filterCases,
  openCaseCount,
  requirementsWithConflicts,
  resultsLabel,
  securitySummary,
  statusMeta,
  STATUS_META,
} from "../features/qa/qaStatusPresentation.mjs";

// TEMPORARY - QA scaffolding for the current testing round, expected to
// be removed once it ends. docs/QA_STATUS_PAGE.md lists every file and
// line to delete; keep that list correct if this page grows.

// A JSON import widens "status" to string, so the shape is asserted once
// here rather than at every call site. qaStatusPresentation.test.mjs
// checks every case really does carry a status the page has a label for,
// which is what makes this assertion safe.
const cases = snapshot.cases as QaCase[];
const requirements = securityData.requirements as SecurityRequirement[];
const areas = caseAreas(cases);
const statusCounts = countByStatus(cases);
const security = securitySummary(requirements);
const conflicts = requirementsWithConflicts(requirements);

type FieldProps = {
  label: string;
  value: string;
  muted?: boolean;
};

function Field({ label, value, muted }: FieldProps) {
  return (
    <Box>
      <Typography component="h4" variant="subtitle2">
        {label}
      </Typography>
      <Typography variant="body2" color={muted ? "text.secondary" : "text.primary"}>
        {value}
      </Typography>
    </Box>
  );
}

export function QaStatusPage() {
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState<QaStatusKey | "all">("all");
  const [area, setArea] = useState("all");

  const visible = filterCases(cases, { search: query, status, area });
  const filtered = query.trim() !== "" || status !== "all" || area !== "all";

  return (
    <Stack spacing={3} sx={{ maxWidth: 1200, mx: "auto" }}>
      <Box>
        <Typography variant="overline" color="primary.main">
          Testing the Research Archive
        </Typography>
        <Typography component="h1" variant="h4">
          QA status &amp; testing guide
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 1, maxWidth: "80ch" }}>
          What has been tested, what to expect when you test it, and where your
          feedback is needed.
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          {cases.length} cases in scope &middot; {statusCounts.passed} passed,{" "}
          {openCaseCount(cases)} still open &middot; evidence last updated{" "}
          {snapshot.evidenceUpdated} &middot; from {snapshot.source}
        </Typography>
      </Box>

      <Alert severity="info">
        <AlertTitle>These are recorded results, not a live health check</AlertTitle>
        The website may have changed since a case was tested. Each case names the
        environment it was tested in: {deployedEvidenceCount(cases)} of{" "}
        {cases.length} were checked on this development website, and the rest only
        locally or on a candidate build. A local pass or a merged fix does not
        establish a pass here.
      </Alert>

      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Typography component="h2" variant="h6">
          Before you test
        </Typography>
        <Typography variant="body2" sx={{ mt: 1 }}>
          Sign in with your own account and follow the steps on a case below. When
          you report something, include the case ID, the page, the time, what you
          expected and what happened instead. Please do not include passwords,
          one-time codes or screenshots of record data. Cases that change
          someone&rsquo;s access, or that deliberately load the system, need a
          coordinated test session rather than an individual attempt.
        </Typography>
      </Paper>

      <Divider />

      <Box>
        <Typography component="h2" variant="h5">
          1. Functional testing
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, maxWidth: "80ch" }}>
          The original checklist, written before this round of testing. One further
          case is outside the agreed scope and is excluded from every total on this
          page; its earlier finding is unchanged in the source workbook.
        </Typography>
      </Box>

      <Stack
        direction="row"
        useFlexGap
        role="group"
        aria-label="Filter cases by status"
        sx={{ flexWrap: "wrap", gap: 1 }}
      >
        <Button
          variant={status === "all" ? "contained" : "outlined"}
          aria-pressed={status === "all"}
          onClick={() => setStatus("all")}
        >
          All {cases.length}
        </Button>
        {STATUS_META.map((item) => (
          <Button
            key={item.key}
            color={item.chipColor === "default" ? "inherit" : item.chipColor}
            variant={status === item.key ? "contained" : "outlined"}
            aria-pressed={status === item.key}
            onClick={() => setStatus(item.key)}
          >
            {item.label} {statusCounts[item.key]}
          </Button>
        ))}
      </Stack>

      <Stack component="dl" spacing={0.5} sx={{ my: 0 }}>
        {STATUS_META.filter((item) => statusCounts[item.key] > 0).map((item) => (
          <Stack
            key={item.key}
            direction={{ xs: "column", sm: "row" }}
            spacing={{ xs: 0, sm: 1 }}
          >
            <Typography
              component="dt"
              variant="body2"
              sx={{ fontWeight: 600, whiteSpace: "nowrap" }}
            >
              {item.label}:
            </Typography>
            <Typography component="dd" variant="body2" color="text.secondary" sx={{ m: 0 }}>
              {item.description}
            </Typography>
          </Stack>
        ))}
      </Stack>

      <Stack direction={{ xs: "column", sm: "row" }} sx={{ gap: 2 }}>
        <TextField
          id="qa-case-search"
          label="Find a test case"
          placeholder="Case ID, feature or issue"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          fullWidth
        />
        <TextField
          id="qa-area-filter"
          select
          label="Area"
          value={area}
          onChange={(event) => setArea(event.target.value)}
          sx={{ minWidth: { sm: 230 } }}
        >
          <MenuItem value="all">All areas</MenuItem>
          {areas.map((item) => (
            <MenuItem key={item} value={item}>
              {item}
            </MenuItem>
          ))}
        </TextField>
        <Button
          onClick={() => {
            setQuery("");
            setArea("all");
            setStatus("all");
          }}
          disabled={!filtered}
          sx={{ whiteSpace: "nowrap", flexShrink: 0, alignSelf: { sm: "center" } }}
        >
          Reset filters
        </Button>
      </Stack>

      <Typography role="status" variant="body2" color="text.secondary">
        {resultsLabel(visible.length, cases.length)}
      </Typography>

      <Box>
        {visible.map((item) => {
          const badge = statusMeta(item.status);
          return (
            <Accordion
              key={item.id}
              disableGutters
              sx={{
                mb: 1,
                border: "1px solid",
                borderColor: "divider",
                boxShadow: "none",
                "&:before": { display: "none" },
              }}
            >
              <AccordionSummary
                expandIcon={<ExpandMoreOutlined />}
                id={`${item.id}-heading`}
                aria-controls={`${item.id}-details`}
              >
                <Stack sx={{ gap: 1, width: "100%", minWidth: 0, pr: 1 }}>
                  <Typography component="h3" sx={{ fontWeight: 600, overflowWrap: "anywhere" }}>
                    {item.id} &middot; {item.title}
                  </Typography>
                  <Stack direction="row" useFlexGap sx={{ flexWrap: "wrap", gap: 1, alignItems: "center" }}>
                    <Chip
                      label={badge?.label ?? item.status}
                      color={badge?.chipColor === "default" ? undefined : badge?.chipColor}
                      size="small"
                      variant="outlined"
                    />
                    <Typography variant="caption" color="text.secondary">
                      {item.category} &middot; tested on: {item.scope}
                    </Typography>
                  </Stack>
                </Stack>
              </AccordionSummary>
              <AccordionDetails id={`${item.id}-details`}>
                <Stack spacing={2} sx={{ overflowWrap: "anywhere" }}>
                  <Field label="Where it stands" value={item.note} />
                  <Field label="Steps" value={item.steps} />
                  <Field label="Expected result in the checklist" value={item.expected} />
                  {item.status === "decision" && (
                    <Alert severity="info">
                      What should happen here has not been agreed yet. Record what
                      you observe, and treat the expected result above as a
                      proposal rather than an approved requirement.
                    </Alert>
                  )}
                  {item.status === "blocked" && (
                    <Alert severity="warning">
                      This one cannot be run yet. Please do not create an account
                      or change anyone&rsquo;s permissions to attempt it.
                    </Alert>
                  )}
                  <Field label="Tested version and evidence" value={item.environment} muted />
                </Stack>
              </AccordionDetails>
            </Accordion>
          );
        })}
        {visible.length === 0 && (
          <Alert severity="info">
            No cases match these filters. Try another phrase, or reset the filters.
          </Alert>
        )}
      </Box>

      <Divider />

      <Box>
        <Typography component="h2" variant="h5">
          2. Access and security requirements
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, maxWidth: "80ch" }}>
          A separate acceptance plan, drawn from the requirements document dated{" "}
          {securityData.sourceDated}. These are <strong>not</strong> part of the
          case totals above, and none has been accepted yet: {security.headline}.
        </Typography>
      </Box>

      <Alert severity="warning">
        <AlertTitle>Nothing here is switched on yet</AlertTitle>
        {securityData.enforcementState} Please do not test these as though they
        were live, and do not treat a passing functional case above as evidence
        for any of them. {securityData.recommendationStatus}
      </Alert>

      {conflicts.length > 0 && (
        <Alert severity="info">
          <AlertTitle>
            {conflicts.length} requirement{conflicts.length === 1 ? "" : "s"} need a
            question answered before they can be tested
          </AlertTitle>
          {conflicts.map((requirement) => requirement.id).join(", ")} &mdash; the
          details are on each requirement below.
        </Alert>
      )}

      <Box>
        {requirements.map((requirement) => (
          <Accordion
            key={requirement.id}
            disableGutters
            sx={{
              mb: 1,
              border: "1px solid",
              borderColor: "divider",
              boxShadow: "none",
              "&:before": { display: "none" },
            }}
          >
            <AccordionSummary
              expandIcon={<ExpandMoreOutlined />}
              id={`${requirement.id}-heading`}
              aria-controls={`${requirement.id}-details`}
            >
              <Stack sx={{ gap: 1, width: "100%", minWidth: 0, pr: 1 }}>
                <Typography component="h3" sx={{ fontWeight: 600, overflowWrap: "anywhere" }}>
                  {requirement.id} &middot; {requirement.group}
                </Typography>
                <Stack direction="row" useFlexGap sx={{ flexWrap: "wrap", gap: 1, alignItems: "center" }}>
                  <Chip label={requirement.verification} size="small" variant="outlined" />
                  <Typography variant="caption" color="text.secondary">
                    {requirement.accessScope}
                  </Typography>
                  {requirement.conflict && (
                    <Chip
                      label="Question outstanding"
                      size="small"
                      color="info"
                      variant="outlined"
                    />
                  )}
                </Stack>
              </Stack>
            </AccordionSummary>
            <AccordionDetails id={`${requirement.id}-details`}>
              <Stack spacing={2} sx={{ overflowWrap: "anywhere" }}>
                <Field label="Requirement, as written" value={requirement.requirement} />
                <Field label="Who it applies to" value={requirement.audience} />
                <Field label="How it will be accepted" value={requirement.acceptance} />
                <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
                  <Field label="Built" value={requirement.implementation} muted />
                  <Field label="Released" value={requirement.deployment} muted />
                  <Field label="Verified" value={requirement.verification} muted />
                </Stack>
                <Field label="Still to be decided" value={requirement.decisions} muted />
                {requirement.recommendation && (
                  <Box>
                    <Typography component="h4" variant="subtitle2">
                      Recommended, not approved
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      {requirement.recommendation}
                    </Typography>
                  </Box>
                )}
                {requirement.conflict && (
                  <Alert severity="info">
                    <AlertTitle>Needs an answer first</AlertTitle>
                    {requirement.conflict}
                  </Alert>
                )}
              </Stack>
            </AccordionDetails>
          </Accordion>
        ))}
      </Box>

      <Typography variant="caption" color="text.secondary">
        This page shows {cases.length} active cases from the original{" "}
        {snapshot.originalCaseCount}-case checklist and {requirements.length} access
        requirements, which are counted separately. Totals are calculated from the
        cases themselves. A status changes only on reviewed test evidence. Named
        test users and specific record examples are kept in restricted test
        documentation, not on this page.
      </Typography>
    </Stack>
  );
}
