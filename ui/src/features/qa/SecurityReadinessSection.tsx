import ExpandMoreOutlined from "@mui/icons-material/ExpandMoreOutlined";
import Accordion from "@mui/material/Accordion";
import AccordionDetails from "@mui/material/AccordionDetails";
import AccordionSummary from "@mui/material/AccordionSummary";
import Alert from "@mui/material/Alert";
import AlertTitle from "@mui/material/AlertTitle";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import readiness from "./securityReadiness.json";
import {
  READINESS_STAGES,
  awaitingBusinessApproval,
  deploymentSummary,
  grantFigures,
  provenanceNotice,
  readinessSummary,
  readinessTotals,
  resolvedMismatches,
  smokeTestSummary,
  stateLabel,
  stateTone,
  verificationNotice,
} from "./securityReadinessPresentation.mjs";

import type {
  KnownMismatch,
  ReadinessStage,
  ReadinessTone,
  SecurityCapability,
} from "./securityReadinessPresentation.d.mts";

/**
 * Security Readiness.
 *
 * <p>Written for a business reader first. The question it answers is "is this
 * protecting records yet", which is NOT the same as "is it built" - and the
 * seven columns exist precisely because those two have been conflated before.
 *
 * <p>Every figure is a recorded measurement with a date. Nothing is refreshed
 * at page load, so the page says "Last verified" rather than implying it is
 * live. Where something could not be re-measured, it says so instead of
 * showing an older number as if it were current.
 */

const TONE_COLOURS: Record<ReadinessTone, "success" | "warning" | "default" | "info"> = {
  complete: "success",
  partial: "warning",
  outstanding: "default",
  notApplicable: "default",
  unknown: "default",
};

const capabilities = readiness.capabilities as unknown as SecurityCapability[];
const stageLabels = readiness.stateLabels as Record<ReadinessStage, string>;

export function SecurityReadinessSection({ today }: { today: string }) {
  const totals = readinessTotals(capabilities);
  const notice = verificationNotice(readiness, today);
  const deployment = deploymentSummary(readiness.deployment as never);
  const grants = grantFigures(readiness.database as never);
  const pending = awaitingBusinessApproval(capabilities);
  const provenance = provenanceNotice(readiness as never);
  const resolved = resolvedMismatches(readiness as never);
  const smoke = smokeTestSummary(readiness.smokeTest as never);
  /*
   * Null today, because the mismatch this panel used to report is resolved.
   * Typed rather than deleted: the next one gets rendered without anyone
   * having to rebuild the markup, and TypeScript stops narrowing the literal
   * null to `never`.
   */
  const mismatch = readiness.knownMismatch as KnownMismatch | null;

  return (
    <Stack spacing={2.5}>
      <Box>
        <Typography variant="overline" color="primary.main">
          Security readiness
        </Typography>
        <Typography component="h2" variant="h5">
          What is protecting records today
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 1, maxWidth: "80ch" }}>
          Built is not the same as switched on. Each capability below is shown at
          every stage between written code and actually restricting what someone
          can see.
        </Typography>
      </Box>

      <Alert severity={notice.stale ? "warning" : "info"}>
        <AlertTitle>{notice.label}</AlertTitle>
        {notice.detail}
      </Alert>

      <Alert severity="warning">
        <AlertTitle>
          Record-level access restrictions are {readiness.enforcement.state === "off" ? "OFF" : "ON"}
        </AlertTitle>
        <Typography variant="body2">{readiness.enforcement.businessMeaning}</Typography>
        <Typography variant="body2" sx={{ mt: 1 }}>
          {readiness.enforcement.whyNotOn}
        </Typography>
      </Alert>

      {deployment ? (
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography component="h3" variant="subtitle2" sx={{ mb: 1 }}>
            The version running in {deployment.environment}
          </Typography>
          <Stack direction="row" sx={{ flexWrap: "wrap", gap: 1 }}>
            <Chip size="small" label={`API revision ${deployment.revision}`} />
            <Chip size="small" label={`Commit ${deployment.commit} (${deployment.commitDate})`} />
            <Chip size="small" label={deployment.shortDigest} />
            <Chip
              size="small"
              color={deployment.isCurrent ? "success" : "warning"}
              label={
                deployment.isCurrent
                  ? "Up to date with main"
                  : `${deployment.behind} commits behind main`
              }
            />
            {deployment.runningTasks ? (
              <Chip size="small" variant="outlined" label={`Tasks ${deployment.runningTasks}`} />
            ) : null}
            <Chip size="small" variant="outlined" label={`Verified ${deployment.verifiedAt}`} />
          </Stack>
          {deployment.commitFull ? (
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ mt: 1, display: "block", wordBreak: "break-all" }}
            >
              Commit {deployment.commitFull}
            </Typography>
          ) : null}
          {readiness.deployment.note ? (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>
              {readiness.deployment.note}
            </Typography>
          ) : null}
          {/*
            * Say where these facts came from. The panel named revision 84 for
            * hours after 85 was deployed, because nothing on the page
            * distinguished "measured on a date" from "currently true".
            */}
          <Typography variant="caption" color="text.secondary" sx={{ mt: 1.5, display: "block" }}>
            <strong>{provenance.label}.</strong> {provenance.detail}
          </Typography>
          {smoke ? (
            <Stack direction="row" sx={{ flexWrap: "wrap", gap: 1, mt: 1.5, alignItems: "center" }}>
              <Chip
                size="small"
                color={smoke.allPassed ? "success" : "warning"}
                label={`Smoke test ${smoke.text}`}
              />
              <Typography variant="caption" color="text.secondary">
                {smoke.performedAt} against {smoke.revision}
                {smoke.failed || smoke.blocked
                  ? ` - ${smoke.failed} failed, ${smoke.blocked} blocked`
                  : ""}
              </Typography>
            </Stack>
          ) : null}
        </Paper>
      ) : null}

      {mismatch ? (
        <Alert severity="info">
          <AlertTitle>{mismatch.title}</AlertTitle>
          <Typography variant="body2">{mismatch.businessMeaning}</Typography>
          <Typography variant="body2" sx={{ mt: 1 }}>
            <strong>Resolution:</strong> {mismatch.resolution}
          </Typography>
        </Alert>
      ) : null}

      {/*
        * A mismatch this page used to report, and no longer does. Kept rather
        * than deleted: a reader who saw the warning needs to be able to find
        * out it was resolved, not just notice it vanished.
        */}
      {resolved.length > 0 ? (
        <Alert severity="success">
          <AlertTitle>Resolved since the last update</AlertTitle>
          <Stack spacing={1}>
            {resolved.map((entry) => (
              <Typography key={entry.title} variant="body2">
                <strong>{entry.title}</strong> - resolved {entry.resolvedOn}. {entry.how}
              </Typography>
            ))}
          </Stack>
        </Alert>
      ) : null}

      <Paper variant="outlined" sx={{ p: 2 }}>
        <Typography component="h3" variant="subtitle2">
          Who currently has access
        </Typography>
        {grants.verified ? null : (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            These figures are from <strong>{grants.asOf}</strong> and could not be
            re-checked on {readiness.verifiedAt}. {grants.caveat}
          </Typography>
        )}
        <Stack direction="row" sx={{ flexWrap: "wrap", gap: 1, mt: 1.5 }}>
          <Chip size="small" label={`${grants.administrators} administrator(s)`} />
          <Chip size="small" label={`${grants.identityLinks} identity link(s)`} />
          <Chip
            size="small"
            color={grants.activeGrants === 0 ? "warning" : "default"}
            label={`${grants.activeGrants} active access grant(s)`}
          />
          <Chip size="small" variant="outlined" label={`As at ${grants.asOf}`} />
        </Stack>
      </Paper>

      <Typography variant="body2" color="text.secondary">
        {totals.enforcing} of {totals.total} capabilities are restricting access today;{" "}
        {totals.deployed} are in the deployed version; {totals.awaitingApproval} are waiting
        on a business decision.
      </Typography>

      <TableContainer component={Paper} variant="outlined">
        <Table size="small" aria-label="Security capability readiness by stage">
          <TableHead>
            <TableRow>
              <TableCell>Capability</TableCell>
              <TableCell>Status</TableCell>
              {READINESS_STAGES.map((stage: ReadinessStage) => (
                <TableCell key={stage} align="center">
                  {stageLabels[stage]}
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {capabilities.map((capability) => {
              const summary = readinessSummary(capability);
              return (
                <TableRow key={capability.id}>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {capability.name}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {capability.id}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Chip size="small" color={TONE_COLOURS[summary.tone]} label={summary.text} />
                  </TableCell>
                  {READINESS_STAGES.map((stage: ReadinessStage) => (
                    <TableCell key={stage} align="center">
                      <Chip
                        size="small"
                        variant={capability.states[stage] === "yes" ? "filled" : "outlined"}
                        color={TONE_COLOURS[stateTone(capability.states[stage])]}
                        label={stateLabel(capability.states[stage])}
                      />
                    </TableCell>
                  ))}
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>

      <Box>
        <Typography component="h3" variant="h6" sx={{ mb: 1 }}>
          What each capability means
        </Typography>
        {capabilities.map((capability) => {
          const summary = readinessSummary(capability);
          return (
            <Accordion key={capability.id} disableGutters>
              <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
                <Stack
                  direction={{ xs: "column", sm: "row" }}
                  sx={{ alignItems: { sm: "center" }, gap: 1, width: "100%" }}
                >
                  <Typography sx={{ fontWeight: 600, flexGrow: 1 }}>
                    {capability.name}
                  </Typography>
                  <Chip size="small" color={TONE_COLOURS[summary.tone]} label={summary.text} />
                </Stack>
              </AccordionSummary>
              <AccordionDetails>
                <Typography variant="body2" sx={{ mb: 1.5 }}>
                  {capability.businessSummary}
                </Typography>

                {capability.pendingApproval ? (
                  <Alert severity="warning" sx={{ mb: 1.5 }}>
                    <AlertTitle>Waiting on a business decision</AlertTitle>
                    {capability.pendingApproval}
                  </Alert>
                ) : null}

                <Typography variant="overline" color="text.secondary">
                  Technical evidence
                </Typography>
                <Table size="small">
                  <TableBody>
                    {READINESS_STAGES.filter(
                      (stage: ReadinessStage) => capability.evidence[stage],
                    ).map((stage: ReadinessStage) => (
                      <TableRow key={stage}>
                        <TableCell sx={{ width: "28%", verticalAlign: "top" }}>
                          <Typography variant="body2" sx={{ fontWeight: 600 }}>
                            {stageLabels[stage]}
                          </Typography>
                          <Chip
                            size="small"
                            sx={{ mt: 0.5 }}
                            variant="outlined"
                            color={TONE_COLOURS[stateTone(capability.states[stage])]}
                            label={stateLabel(capability.states[stage])}
                          />
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2" color="text.secondary">
                            {capability.evidence[stage]}
                          </Typography>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </AccordionDetails>
            </Accordion>
          );
        })}
      </Box>

      {pending.length > 0 ? (
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography component="h3" variant="h6" sx={{ mb: 1 }}>
            Decisions needed from the business
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            These are not blocked on engineering. Each one changes who can see what,
            so none has been chosen on the business&rsquo;s behalf.
          </Typography>
          <Stack spacing={1.5}>
            {pending.map((capability) => (
              <Box key={capability.id}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {capability.name}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {capability.pendingApproval}
                </Typography>
              </Box>
            ))}
          </Stack>
        </Paper>
      ) : null}
    </Stack>
  );
}
