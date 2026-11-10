import ExpandMoreOutlined from "@mui/icons-material/ExpandMoreOutlined";
import Accordion from "@mui/material/Accordion";
import AccordionDetails from "@mui/material/AccordionDetails";
import AccordionSummary from "@mui/material/AccordionSummary";
import Alert from "@mui/material/Alert";
import AlertTitle from "@mui/material/AlertTitle";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import Divider from "@mui/material/Divider";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useMemo, useState } from "react";
import { Link as RouterLink } from "react-router-dom";

import snapshot from "../features/qa/qaSnapshot.json";
import openFindings from "../features/qa/qaOpenFindings.json";
import {
  currentQaCases,
  reopenedNotice,
} from "../features/qa/currentQaStatus.mjs";
import type { QaOpenFinding } from "../features/qa/currentQaStatus.d.mts";
import {
  applyFilters,
  attentionCases,
  coordinatedCases,
  moduleOptions,
  needsCoordination,
  summary,
  testerEnvironment,
  testerHistory,
  testerProgress,
  testerStatus,
  testerStatusGuide,
} from "../features/qa/qaTestingPresentation.mjs";
import type { TesterCase, TesterStatus } from "../features/qa/qaTestingPresentation.d.mts";

type QaCase = {
  id: string;
  category: string;
  title: string;
  steps: string;
  expected: string;
  priority: string;
  status: string;
  note?: string;
  scope?: string;
  environment?: string;
  recordedStatus?: string;
  currentStatus?: string;
  reopened?: boolean;
  finding?: QaOpenFinding | null;
};

/*
 * The cases as they stand NOW, not only as they were recorded.
 *
 * qaSnapshot.json is history - what was observed, and when - and three tests
 * guard it byte-for-byte. A case that was verified and has since been
 * reopened would otherwise still read as passed here, which is how TC-041
 * came to say "passed" on this page while its relevance finding was open.
 *
 * So current assessment lives in qaOpenFindings.json and the list is derived.
 * A finding can only ever make a case MORE open; nothing in that file can
 * mark something passed.
 */
const cases = currentQaCases(
  snapshot.cases,
  openFindings.findings,
) as unknown as QaCase[];

/**
 * QA Testing - the default page, written for a BU tester.
 *
 * <p>What changed and why: the previous single page opened with security
 * readiness, deployment evidence and nine acceptance requirements, and put the
 * test cases below all of it. A tester arriving to do an afternoon's testing
 * had to scroll past engineering status to find out what to test.
 *
 * <p>So this page answers four questions in order - what needs attention, what
 * needs somebody else, how to report a problem, and what has already been
 * tested - and the technical material moved to Security &amp; Deployment
 * Readiness, linked from the bottom.
 *
 * <p>Recorded outcomes are untouched. Every status, date, note and environment
 * is read from qaSnapshot.json exactly as it was recorded; the only
 * presentational change is that a case shows the site and date it was tested on
 * rather than the full engineering build string.
 */
export function QaTestingPage() {
  const [caseId, setCaseId] = useState("");
  const [status, setStatus] = useState("all");
  const [module, setModule] = useState("all");

  const totals = useMemo(() => summary(cases), []);
  const attention = useMemo(() => attentionCases(cases), []);
  const coordinated = useMemo(() => coordinatedCases(cases), []);
  const modules = useMemo(() => moduleOptions(cases), []);
  const visible = useMemo(
    () => applyFilters(cases, { caseId, status, module }),
    [caseId, status, module],
  );

  const filtering = caseId.trim() !== "" || status !== "all" || module !== "all";

  return (
    <Stack spacing={3} sx={{ maxWidth: 1100, mx: "auto" }}>
      <Box component="header">
        <Typography variant="overline" color="primary.main">
          Research Archive
        </Typography>
        <Typography component="h1" variant="h4">
          QA Testing
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 1, maxWidth: "70ch" }}>
          What to test, what still needs attention, and what has already been
          checked. These are recorded results from earlier testing - the site may
          have changed since, so if something behaves differently from what a
          case says, that is worth reporting.
        </Typography>
      </Box>

      {/* The summary, as counts a tester can act on rather than a percentage. */}
      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Typography component="h2" variant="h6" gutterBottom>
          Where testing stands
        </Typography>
        <Stack direction="row" sx={{ flexWrap: "wrap", gap: 1 }}>
          <Chip label={`${totals.total} cases in total`} />
          <Chip color="success" label={`${totals.counts.passed} passed`} />
          <Chip color="info" label={`${totals.counts.decision} decision needed`} />
          <Chip color="warning" label={`${totals.counts.blocked} blocked`} />
          <Chip
            color="warning"
            variant="outlined"
            label={`${totals.counts.evidence} passed, more checks needed`}
          />
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>
          Evidence last updated {snapshot.evidenceUpdated}, from {snapshot.source}.
        </Typography>
      </Paper>

      {/* 1. What needs attention - the reason a tester opened this page. */}
      <Box component="section" aria-labelledby="needs-attention">
        <Typography component="h2" variant="h5" id="needs-attention">
          {totals.open} cases still need attention
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5, mb: 2, maxWidth: "70ch" }}>
          The other {totals.counts.passed} passed and need nothing from you unless
          you notice them behaving differently.
        </Typography>

        <Stack spacing={1.5}>
          {attention.map((item: TesterCase) => {
            const meta = testerStatus(item.status);
            const where = testerEnvironment(item);
            return (
              <Paper key={item.id} variant="outlined" sx={{ p: 2 }}>
                <Stack
                  direction="row"
                  sx={{ flexWrap: "wrap", gap: 1, alignItems: "center", mb: 0.5 }}
                >
                  <Chip size="small" label={item.id} />
                  <Chip size="small" color={meta.chipColor} label={meta.headline} />
                  <Chip size="small" variant="outlined" label={item.category} />
                  {needsCoordination(item) ? (
                    <Chip size="small" variant="outlined" color="warning" label="Needs coordination" />
                  ) : null}
                </Stack>
                <Typography component="h3" sx={{ fontWeight: 600 }}>
                  {item.title}
                </Typography>
                <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                  {meta.plain}
                </Typography>
                {item.note ? (
                  <Typography variant="body2" sx={{ mt: 1 }}>
                    {item.note}
                  </Typography>
                ) : null}
                <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: "block" }}>
                  {where.site ?? "Environment not recorded"}
                  {where.testedOn ? ` · last tested ${where.testedOn}` : " · date not recorded"}
                </Typography>
              </Paper>
            );
          })}
        </Stack>
      </Box>

      {/* 2. What cannot be done alone. */}
      {coordinated.length > 0 ? (
        <Alert severity="warning">
          <AlertTitle>
            {coordinated.length} cases need a coordinated session, not an individual attempt
          </AlertTitle>
          <Typography variant="body2">
            These need an account or a permission an ordinary tester does not have.
            Trying them on your own produces a failure that is not a real failure,
            so please arrange a session instead of reporting what you see.
          </Typography>
          <Stack component="ul" sx={{ mt: 1, mb: 0, pl: 2.5 }} spacing={0.5}>
            {coordinated.map((item: TesterCase) => (
              <Typography component="li" variant="body2" key={item.id}>
                <strong>{item.id}</strong> — {item.title}
                {item.note ? `. ${item.note}` : ""}
              </Typography>
            ))}
          </Stack>
        </Alert>
      ) : null}

      {/* 3. How to test, and how to report. */}
      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Typography component="h2" variant="h6">
          Before you start, and how to report a problem
        </Typography>
        <Typography variant="body2" sx={{ mt: 1 }}>
          Sign in with your own account and follow the steps on a case below.
        </Typography>
        <Typography variant="body2" sx={{ mt: 1.5 }}>
          When something is wrong, include <strong>the case ID</strong>, the page
          you were on, roughly when it happened, what you expected and what
          happened instead. A screenshot of the page layout helps.
        </Typography>
        <Alert severity="info" sx={{ mt: 1.5 }}>
          Please do not include passwords, one-time codes, or screenshots showing
          record contents. The case ID and a description are enough for us to
          reproduce it.
        </Alert>
      </Paper>

      <Divider />

      {/* 4. What has already been tested. */}
      <Box component="section" aria-labelledby="all-cases">
        <Typography component="h2" variant="h5" id="all-cases">
          All {totals.total} test cases
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5, maxWidth: "70ch" }}>
          Kept exactly as they were recorded. An outcome is never rewritten to
          match a later fix - the date and the site it was tested on are what make
          a result mean anything.
        </Typography>

        <Stack
          direction={{ xs: "column", sm: "row" }}
          spacing={1.5}
          sx={{ mt: 2, mb: 2, flexWrap: "wrap" }}
        >
          <TextField
            size="small"
            label="Case ID or title"
            placeholder="e.g. TC-023 or attachments"
            value={caseId}
            onChange={(event) => setCaseId(event.target.value)}
            sx={{ minWidth: 220 }}
          />
          <TextField
            size="small"
            select
            label="Status"
            value={status}
            onChange={(event) => setStatus(event.target.value)}
            sx={{ minWidth: 200 }}
          >
            <MenuItem value="all">All statuses</MenuItem>
            {testerStatusGuide()
              .filter((entry: TesterStatus) => totals.counts[entry.key] > 0)
              .map((entry: TesterStatus) => (
                <MenuItem key={entry.key} value={entry.key}>
                  {entry.headline} ({totals.counts[entry.key]})
                </MenuItem>
              ))}
          </TextField>
          <TextField
            size="small"
            select
            label="Module"
            value={module}
            onChange={(event) => setModule(event.target.value)}
            sx={{ minWidth: 200 }}
          >
            <MenuItem value="all">All modules</MenuItem>
            {modules.map((name: string) => (
              <MenuItem key={name} value={name}>
                {name}
              </MenuItem>
            ))}
          </TextField>
          {filtering ? (
            <Button
              onClick={() => {
                setCaseId("");
                setStatus("all");
                setModule("all");
              }}
            >
              Clear filters
            </Button>
          ) : null}
        </Stack>

        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }} aria-live="polite">
          Showing {visible.length} of {totals.total} cases
        </Typography>

        {visible.length === 0 ? (
          <Alert severity="info">
            No cases match those filters. Clear them to see all {totals.total}.
          </Alert>
        ) : null}

        {visible.map((item: TesterCase) => {
          const meta = testerStatus(item.status);
          const where = testerEnvironment(item);
          return (
            <Accordion key={item.id} disableGutters>
              <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
                <Stack
                  direction="row"
                  sx={{ flexWrap: "wrap", gap: 1, alignItems: "center", pr: 1 }}
                >
                  <Chip size="small" label={item.id} />
                  <Typography sx={{ fontWeight: 600, overflowWrap: "anywhere" }}>
                    {item.title}
                  </Typography>
                  <Chip size="small" color={meta.chipColor} label={meta.headline} />
                  <Chip size="small" variant="outlined" label={item.category} />
                </Stack>
              </AccordionSummary>
              <AccordionDetails>
                <Stack spacing={1.5}>
                  {/* A case that was verified and has since been reopened.
                      A tester needs to know that part of this was checked
                      and holds, that part of it is still wrong, and that the
                      difference is not their misreading. */}
                  {item.reopened && item.finding ? (
                    <Alert severity="warning">
                      <Typography sx={{ fontWeight: 600 }}>
                        {reopenedNotice(item.finding)?.headline}
                      </Typography>
                      <Typography variant="body2" sx={{ mt: 0.5 }}>
                        <strong>Checked and still holds:</strong>{" "}
                        {item.finding.whatWasVerified}
                      </Typography>
                      <Typography variant="body2" sx={{ mt: 0.5 }}>
                        <strong>Still open:</strong> {item.finding.whatRemainsOpen}
                      </Typography>
                      <Typography variant="body2" sx={{ mt: 0.5 }}>
                        <strong>How we know:</strong> {item.finding.evidence}
                      </Typography>
                      <Typography variant="body2" sx={{ mt: 0.5 }}>
                        <strong>What would close it:</strong>{" "}
                        {item.finding.whatWouldCloseIt}
                      </Typography>
                      <Typography variant="caption" sx={{ display: "block", mt: 0.5 }}>
                        Recorded as passed earlier; reopened {item.finding.openedOn}.
                        The earlier result is kept below exactly as it was written.
                      </Typography>
                    </Alert>
                  ) : null}
                  <Box>
                    <Typography component="h4" variant="subtitle2">
                      What this status means
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      {meta.plain}
                    </Typography>
                  </Box>
                  <Box>
                    <Typography component="h4" variant="subtitle2">
                      Steps
                    </Typography>
                    <Typography variant="body2">{item.steps}</Typography>
                  </Box>
                  <Box>
                    <Typography component="h4" variant="subtitle2">
                      What should happen
                    </Typography>
                    <Typography variant="body2">{item.expected}</Typography>
                  </Box>
                  {item.note ? (
                    <Box>
                      <Typography component="h4" variant="subtitle2">
                        Notes from the last test
                      </Typography>
                      <Typography variant="body2">{item.note}</Typography>
                    </Box>
                  ) : null}
                  <Box>
                    <Typography component="h4" variant="subtitle2">
                      When and where it was tested
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      {where.site ?? "Environment not recorded"}
                      {where.testedOn ? `, on ${where.testedOn}` : ", date not recorded"}
                    </Typography>
                  </Box>
                  {(() => {
                    const progress = testerProgress(item);
                    if (!progress) return null;
                    return (
                      <>
                        {progress.results ? (
                          <Box>
                            <Typography component="h4" variant="subtitle2">
                              What was observed
                            </Typography>
                            <Typography variant="body2">{progress.results}</Typography>
                            {progress.verifiedOn ? (
                              <Typography variant="caption" color="text.secondary">
                                Checked {progress.verifiedOn}
                                {progress.verifiedIn ? ` on the ${progress.verifiedIn}` : ""}
                              </Typography>
                            ) : null}
                          </Box>
                        ) : null}
                        {progress.limitations ? (
                          <Box>
                            <Typography component="h4" variant="subtitle2">
                              Remaining limitations
                            </Typography>
                            <Typography variant="body2" color="text.secondary">
                              {progress.limitations}
                            </Typography>
                          </Box>
                        ) : null}
                      </>
                    );
                  })()}
                  {testerHistory(item).length > 0 ? (
                    <Box>
                      <Typography component="h4" variant="subtitle2">
                        Earlier findings
                      </Typography>
                      <Stack component="ul" sx={{ pl: 2.5, m: 0 }} spacing={0.5}>
                        {testerHistory(item).map((entry) => (
                          <Typography component="li" variant="body2" key={`${entry.on}-${entry.summary}`}>
                            {entry.on ? <strong>{entry.on}</strong> : null}
                            {entry.on ? " — " : ""}
                            {entry.summary}
                          </Typography>
                        ))}
                      </Stack>
                    </Box>
                  ) : null}
                </Stack>
              </AccordionDetails>
            </Accordion>
          );
        })}
      </Box>

      <Divider />

      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Typography component="h2" variant="h6">
          Looking for the technical status?
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1, maxWidth: "70ch" }}>
          Security implementation progress, deployment evidence and the business
          decisions still outstanding have moved to their own page, so this one
          stays about testing.
        </Typography>
        <Button
          component={RouterLink}
          to="/qa-status/readiness"
          variant="outlined"
          sx={{ mt: 1.5 }}
        >
          Security &amp; Deployment Readiness
        </Button>
      </Paper>
    </Stack>
  );
}
