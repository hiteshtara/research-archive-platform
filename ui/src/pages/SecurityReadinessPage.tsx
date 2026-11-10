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
  List,
  ListItem,
  ListItemText,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import { useMemo } from "react";
import { Link as RouterLink } from "react-router-dom";

import snapshot from "../features/qa/qaSnapshot.json";
import { SecurityReadinessSection } from "../features/qa/SecurityReadinessSection";
import readiness from "../features/qa/securityReadiness.json";
import securityData from "../features/qa/securityRequirements.json";
import type { SecurityModule } from "../features/qa/qaStatusPresentation.d.mts";
import type { SecurityRequirement } from "../features/qa/qaStatusPresentation.mjs";
import {
  enforcementIsOffEverywhere,
  requirementsWithConflicts,
  securityProgress,
  securityStages,
  securitySummary,
} from "../features/qa/qaStatusPresentation.mjs";
import { acceptanceSummary, classifyStatements } from "../features/qa/readinessHistory.mjs";
import type { ReadinessStatement } from "../features/qa/readinessHistory.d.mts";

const requirements = securityData.requirements as unknown as SecurityRequirement[];
const modules = securityData.moduleProgress as unknown as SecurityModule[];
const approvedDecisions = (securityData.decisionsApproved ?? []) as string[];
const enforcementOff = enforcementIsOffEverywhere(requirements);

type VerifiedCase = {
  id: string;
  progress?: { change?: string; deployedBuild?: string; verifiedOn?: string };
};

/** The cases carrying a build record - the engineering half of the evidence. */
const verifiedCases = (snapshot.cases as unknown as VerifiedCase[]).filter(
  (item) => item.progress,
);

/**
 * Security & Deployment Readiness.
 *
 * <p>Split out of the old combined page so QA Testing can be about testing.
 * Everything technical lives here: what is deployed, the nine acceptance
 * requirements, implementation progress by module, and the business decisions.
 *
 * <h2>This page is not access-restricted</h2>
 *
 * <p>It is a separate page, not a protected one. What it renders is compiled
 * into the JavaScript bundle every signed-in user already downloads, so a route
 * guard would change who is likely to read it, not who can. Saying so is the
 * honest option; labelling it administrator-only would claim a boundary the
 * implementation does not have. Serving it from an authorization-checked
 * endpoint is a tracked follow-up.
 *
 * <h2>Current status versus historical record</h2>
 *
 * <p>The readiness block is a dated measurement refreshed each release. The
 * requirements document was written earlier and describes the deployment as it
 * stood then - including statements later releases have overtaken. Those move
 * to Historical record rather than being deleted, because the acceptance
 * verdicts were reached against them.
 */
export function SecurityReadinessPage() {
  const security = securitySummary(requirements);
  const progress = securityProgress(requirements);
  const conflicts = requirementsWithConflicts(requirements);
  const statements = useMemo(
    () => classifyStatements(securityData, readiness.deployment),
    [],
  );
  const acceptance = useMemo(() => acceptanceSummary(requirements), []);

  return (
    <Stack spacing={3} sx={{ maxWidth: 1100, mx: "auto" }}>
      <Box component="header">
        <Typography variant="overline" color="primary.main">
          Research Archive
        </Typography>
        <Typography component="h1" variant="h4">
          Security &amp; Deployment Readiness
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 1, maxWidth: "75ch" }}>
          What is protecting records today, what is deployed, and which security
          requirements have been accepted. Testers want{" "}
          <RouterLink to="/qa-status">QA Testing</RouterLink> instead.
        </Typography>
      </Box>

      <Alert severity="info">
        <AlertTitle>This page is separated for clarity, not restricted</AlertTitle>
        Anyone signed in to the archive can open it. What is here is deployment
        and testing status, not credentials or record data. Serving it from an
        authorization-checked endpoint is a tracked follow-up; until then the
        separation is about who needs to read this, not who may.
      </Alert>

      <SecurityReadinessSection today={readiness.verifiedAt} />

      <Alert severity="warning">
        <AlertTitle>
          {acceptance.verified} of {acceptance.total} security requirements have been accepted
        </AlertTitle>
        Acceptance means somebody checked a requirement against its criteria with
        enforcement switched on. That has not happened for any of them. A merged
        pull request, a passing test and a deployment are three different things,
        and none of them is acceptance.
      </Alert>

      <Box>
        <Typography component="h2" variant="h5">
          Security acceptance requirements
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, maxWidth: "80ch" }}>
          A separate acceptance plan, drawn from the requirements document dated{" "}
          {securityData.sourceDated}. These are <strong>not</strong> part of the
          case totals above, and none has been accepted yet: {security.headline}.
        </Typography>
      </Box>

      <Alert severity="warning">
        <AlertTitle>Record-level authorization is OFF</AlertTitle>
        {securityData.enforcementState} Please do not test these as though they
        were live, and do not treat a passing QA case as evidence for any of
        them. {securityData.recommendationStatus}
      </Alert>

      {/*
        The recorded apiDeploymentState said "NOTHING authorization-related is
        running", naming revision 81. Revision 85 shipped on 2026-11-09
        carrying all of it, so the sentence is now false - but it is also the
        record of what was true when the acceptance verdicts were reached. It
        moves to Historical record, labelled, rather than being deleted or
        silently left here reading as current. readinessHistory.mjs decides
        that by comparing the revision it names against the deployed one.
      */}

      {/*
        Security implementation progress. Separate from the case totals and
        from the requirement list: this is "which parts of the archive have
        authorization at all", which is a different question from "is each
        written requirement satisfied".
      */}
      <Box>
        <Typography component="h3" variant="h6">
          Security implementation progress
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, maxWidth: "80ch" }}>
          {progress.decided} of {progress.total} requirements have a settled
          business decision. {progress.implemented} are implemented in code
          across their whole stated scope &mdash; the rest cover Award and
          Proposal only, of five modules, which is why they read
          &ldquo;partial&rdquo; rather than done. {progress.merged} are merged
          into main, and the running API now carries them &mdash; see the
          deployment block above for which revision. And{" "}
          <strong>{progress.verified} have been verified against their acceptance
          criteria</strong>. Those are four different facts and are reported
          separately on purpose.
        </Typography>

        <TableContainer component={Paper} variant="outlined" sx={{ mt: 2 }}>
          <Table size="small" aria-label="Security implementation progress by area">
            <TableHead>
              <TableRow>
                <TableCell>Area</TableCell>
                <TableCell>State</TableCell>
                <TableCell>Evidence</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {modules.map((module) => (
                <TableRow key={module.name}>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {module.name}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {module.detail}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      label={module.state}
                      color={
                        module.state === "working"
                          ? "success"
                          : module.state === "implemented"
                            ? "info"
                            : module.state === "merged-not-deployed"
                              ? "warning"
                              : module.state === "off"
                                ? "warning"
                                : "default"
                      }
                      variant={module.state === "off" ? "filled" : "outlined"}
                    />
                  </TableCell>
                  <TableCell>
                    <Typography variant="caption" color="text.secondary">
                      {module.evidence}
                    </Typography>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
        {enforcementOff && (
          <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 1 }}>
            No requirement on this page reports enforcement as enabled.
          </Typography>
        )}
      </Box>

      {approvedDecisions.length > 0 && (
        <Box>
          <Typography component="h3" variant="h6">
            Approved business decisions
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, maxWidth: "80ch" }}>
            Agreed {securityData.reconciledOn}. An approved decision is not
            working software; see each requirement for how far it has actually
            travelled.
          </Typography>
          <List dense>
            {approvedDecisions.map((decision: string) => (
              <ListItem key={decision} sx={{ pl: 0 }}>
                <ListItemText primary={decision} />
              </ListItem>
            ))}
          </List>
        </Box>
      )}

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
                  <Chip
                    label={
                      requirement.status?.acceptanceVerified === "yes"
                        ? "Verified"
                        : "Not verified"
                    }
                    size="small"
                    variant="outlined"
                    color={
                      requirement.status?.acceptanceVerified === "yes" ? "success" : "default"
                    }
                  />
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
                <Field label="Approved business rule" value={requirement.businessRule} />

                <Box>
                  <Typography component="h4" variant="subtitle2" gutterBottom>
                    How far this has travelled
                  </Typography>
                  <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
                    {securityStages(requirement).map((stage) => (
                      <Chip
                        key={stage.key}
                        size="small"
                        label={`${stage.label}: ${stage.text}`}
                        title={stage.detail}
                        color={
                          stage.tone === "done"
                            ? "success"
                            : stage.tone === "partial"
                              ? "warning"
                              : stage.tone === "open"
                                ? "info"
                                : "default"
                        }
                        variant={stage.tone === "done" ? "filled" : "outlined"}
                      />
                    ))}
                  </Stack>
                </Box>

                <Field label="Test coverage" value={requirement.tests} muted />
                <Field label="Remaining blockers" value={requirement.blockers} muted />
                <Field label="Related work" value={requirement.evidence} muted />
                {requirement.decisions && (
                  <Field label="Still to be decided" value={requirement.decisions} muted />
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
        This page shows {requirements.length} access requirements, counted
        separately from the QA cases and never satisfied by one. Named test users
        and specific record examples are kept in restricted test documentation,
        not here.
      </Typography>

      {/*
        The build evidence behind the QA verifications. These are the fields
        the tester page deliberately drops - pull requests, commit SHAs,
        Amplify jobs and ECS revisions - kept here in full so the evidence is
        not lost, only moved.
      */}
      <Box component="section" aria-labelledby="case-builds">
        <Divider sx={{ mb: 2 }} />
        <Typography component="h2" variant="h5" id="case-builds">
          Build evidence for verified QA cases
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5, maxWidth: "75ch" }}>
          {verifiedCases.length} of {snapshot.cases.length} cases carry a record
          of which change was tested and which build it was tested on. The QA
          Testing page shows what was observed and when; the build identifiers
          are here.
        </Typography>
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 2 }}>
          <Table size="small" aria-label="Build evidence for verified QA cases">
            <TableHead>
              <TableRow>
                <TableCell>Case</TableCell>
                <TableCell>Change tested</TableCell>
                <TableCell>Build it was tested on</TableCell>
                <TableCell>Verified</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {verifiedCases.map((item) => (
                <TableRow key={item.id}>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {item.id}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" color="text.secondary" sx={{ overflowWrap: "anywhere" }}>
                      {item.progress?.change ?? "—"}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" color="text.secondary" sx={{ overflowWrap: "anywhere" }}>
                      {item.progress?.deployedBuild ?? "—"}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" color="text.secondary">
                      {item.progress?.verifiedOn ?? "—"}
                    </Typography>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Box>

      {statements.historical.length > 0 ? (
        <Box component="section" aria-labelledby="historical">
          <Divider sx={{ mb: 2 }} />
          <Typography component="h2" variant="h5" id="historical">
            Historical record
          </Typography>
          <Typography color="text.secondary" sx={{ mt: 0.5, maxWidth: "75ch" }}>
            Recorded on {securityData.sourceDated} and since overtaken by a later
            release. Kept rather than deleted: it is the record of what was true,
            and the acceptance verdicts above were reached against it.
          </Typography>
          {statements.historical.map((statement: ReadinessStatement) => (
            <Paper variant="outlined" key={statement.key} sx={{ p: 2, mt: 2 }}>
              <Stack direction="row" sx={{ gap: 1, flexWrap: "wrap", alignItems: "center", mb: 1 }}>
                <Typography component="h3" variant="subtitle2">
                  {statement.heading}, as at {securityData.sourceDated}
                </Typography>
                <Chip
                  size="small"
                  label={`Superseded by ${statement.supersededBy ?? "a later release"}`}
                />
              </Stack>
              <Typography variant="body2" color="text.secondary">
                {statement.text}
              </Typography>
            </Paper>
          ))}
        </Box>
      ) : null}

      <Divider />

      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Typography component="h2" variant="h6">
          Looking for the test cases?
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1, maxWidth: "70ch" }}>
          All {snapshot.cases.length} QA cases, their recorded outcomes and the
          instructions for running them are on the QA Testing page.
        </Typography>
        <Button component={RouterLink} to="/qa-status" variant="outlined" sx={{ mt: 1.5 }}>
          QA Testing
        </Button>
      </Paper>
    </Stack>
  );
}

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
