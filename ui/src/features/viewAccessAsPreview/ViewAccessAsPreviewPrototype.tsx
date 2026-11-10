import Alert from "@mui/material/Alert";
import AlertTitle from "@mui/material/AlertTitle";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import Divider from "@mui/material/Divider";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import fixtures from "./previewFixtures.json";

/**
 * View Access As - PROTOTYPE.
 *
 * <p>Renders {@code previewFixtures.json} and calls nothing. There is no fetch,
 * no API client import and no route: it exists so the design in
 * {@code docs/security/VIEW_ACCESS_AS_DESIGN.md} can be looked at rather than
 * only read, and so the shape of the answer can be argued about before an
 * endpoint exists to produce it.
 *
 * <p>Three things it is deliberately showing:
 *
 * <ul>
 *   <li><b>Enforcement is OFF</b>, stated first and in the response, because a
 *       simulated decision that reads as live behaviour is worse than no
 *       preview at all.
 *   <li><b>The administrator's own column is mostly DENY.</b> ARCHIVE_ADMIN
 *       confers no record access, and the clearest way to make that concrete
 *       is to show it next to the subject's answer.
 *   <li><b>A non-existent identifier looks exactly like a denied one.</b> Two
 *       rows in the fixtures are byte-identical for that reason; the screen
 *       must give the administrator no way to tell which identifiers are real.
 * </ul>
 *
 * <p>What it does not show, on purpose: no record title, status, unit, person,
 * date or amount, and no count, list or page of records. Those are the
 * disclosures the design exists to prevent.
 */
export function ViewAccessAsPreviewPrototype() {
  const { enforcement, administrator, subject, grants, decisions } = fixtures;

  return (
    <Stack spacing={3} sx={{ maxWidth: 1000, mx: "auto" }}>
      <Box component="header">
        <Typography variant="overline" color="primary.main">
          Prototype — not connected to anything
        </Typography>
        <Typography component="h1" variant="h4">
          View Access As
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 1, maxWidth: "70ch" }}>
          What would this person see? Answered by the real authorization
          evaluator — not by signing in as them. No token is minted, borrowed or
          substituted, and nothing here can change a grant.
        </Typography>
      </Box>

      {/* Stated before any result, never as a footnote. */}
      <Alert severity={enforcement.enabled ? "success" : "warning"}>
        <AlertTitle>
          {enforcement.enabled
            ? "Enforcement is ON — these match live behaviour"
            : "Enforcement is OFF — these are simulated decisions"}
        </AlertTitle>
        {enforcement.enabled ? null : (
          <Typography variant="body2">
            Live behaviour today is that every signed-in user sees every archived
            record. The decisions below are what <em>would</em> happen if
            enforcement were switched on with the policy shown.
          </Typography>
        )}
        <Stack direction="row" sx={{ flexWrap: "wrap", gap: 0.75, mt: 1 }}>
          <Chip size="small" label={`Policy: ${enforcement.policySource}`} />
          <Chip size="small" variant="outlined" label={`Versions: ${enforcement.policy.versionScope}`} />
          <Chip size="small" variant="outlined" label={`Department: ${enforcement.policy.departmentMatch}`} />
          <Chip size="small" variant="outlined" label={`Contacts: ${enforcement.policy.contactDerivation}`} />
          <Chip
            size="small"
            variant="outlined"
            label={`Research staff: ${enforcement.policy.researchStaffRoles.join(", ")}`}
          />
        </Stack>
      </Alert>

      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Typography component="h2" variant="h6">
          Who is being previewed
        </Typography>
        <Stack direction="row" sx={{ flexWrap: "wrap", gap: 1, mt: 1.5 }}>
          <Chip label={subject.institutionalIdentifier} />
          <Chip variant="outlined" label={`Sign-in: ${subject.loginName}`} />
          <Chip
            color={subject.linkStatus === "ACTIVE" ? "success" : "warning"}
            label={`Identity link ${subject.linkStatus}`}
          />
          <Chip
            variant="outlined"
            label={subject.hasKualiPrincipal ? "Has Kuali principal" : "No Kuali principal"}
          />
        </Stack>
        <Typography variant="body2" sx={{ mt: 1.5 }}>
          <strong>Scope:</strong> {subject.scopeDescription}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: "block" }}>
          Described in words rather than as a count. A count of reachable records
          is an enumeration of the archive, which an administrator holding no
          data grant is not entitled to.
        </Typography>
      </Paper>

      <Box component="section" aria-labelledby="grants">
        <Typography component="h2" variant="h5" id="grants">
          Their grants
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5, mb: 2, maxWidth: "70ch" }}>
          Including the ones that no longer apply. A revoked or expired grant is
          kept as evidence of who had access and when, so it is shown with the
          reason it is not in force.
        </Typography>
        <TableContainer component={Paper} variant="outlined">
          <Table size="small" aria-label="Grants held by the previewed user">
            <TableHead>
              <TableRow>
                <TableCell>Type</TableCell>
                <TableCell>Scope</TableCell>
                <TableCell>In force</TableCell>
                <TableCell>Why</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {grants.map((grant) => (
                <TableRow key={grant.grantId}>
                  <TableCell>
                    <Chip size="small" label={grant.type} />
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2">{grant.scope}</Typography>
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      color={grant.inForce ? "success" : "default"}
                      label={grant.inForce ? "Yes" : grant.state}
                    />
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" color="text.secondary">
                      {grant.why}
                    </Typography>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Box>

      <Divider />

      <Box component="section" aria-labelledby="decisions">
        <Typography component="h2" variant="h5" id="decisions">
          Decisions for the records you asked about
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5, mb: 2, maxWidth: "70ch" }}>
          You type or paste identifiers; there is no picker, because a picker
          would let you browse records you may not be able to read. Up to ten at
          a time.
        </Typography>

        <TableContainer component={Paper} variant="outlined">
          <Table size="small" aria-label="Authorization decisions, subject compared with administrator">
            <TableHead>
              <TableRow>
                <TableCell>Record</TableCell>
                <TableCell>{subject.loginName}</TableCell>
                <TableCell>You</TableCell>
                <TableCell>Why</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {decisions.map((row) => (
                <TableRow key={`${row.module}-${row.identifier}`}>
                  <TableCell>
                    <Chip size="small" variant="outlined" label={row.module} sx={{ mr: 0.5 }} />
                    <Typography variant="body2" component="span" sx={{ fontFamily: "monospace" }}>
                      {row.identifier}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Decision value={row.subject.decision} />
                  </TableCell>
                  <TableCell>
                    <Decision value={row.administrator.decision} />
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" color="text.secondary">
                      {row.subject.reasonCode}
                    </Typography>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>

        <Alert severity="info" sx={{ mt: 2 }}>
          <AlertTitle>Why your own column is mostly DENY</AlertTitle>
          {administrator.note}
        </Alert>

        <Alert severity="warning" sx={{ mt: 2 }}>
          <AlertTitle>A refusal here does not mean the record exists</AlertTitle>
          An identifier that does not exist and a record this person cannot reach
          give exactly the same answer, deliberately. This screen is not a way to
          discover which identifiers are real.
        </Alert>
      </Box>

      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Typography component="h2" variant="h6">
          What is recorded when you use this
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1, maxWidth: "70ch" }}>
          Every preview writes an audit row: who asked, about whom, which
          identifiers, what was decided and under which policy. No record title
          or content is recorded, and if the audit cannot be written the preview
          does not happen.
        </Typography>
      </Paper>
    </Stack>
  );
}

function Decision({ value }: { value: string }) {
  return (
    <Chip
      size="small"
      color={value === "PERMIT" ? "success" : "default"}
      variant={value === "PERMIT" ? "filled" : "outlined"}
      label={value}
    />
  );
}
