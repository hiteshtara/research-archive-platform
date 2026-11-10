import { useCallback, useEffect, useMemo, useState } from "react";

import Alert from "@mui/material/Alert";
import AlertTitle from "@mui/material/AlertTitle";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Divider from "@mui/material/Divider";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import {
  HARNESS_ORIGIN,
  comparisonRows,
  evaluateQuery,
  familyFor,
  familyTally,
  harnessUnavailableMessage,
  harnessUrl,
  isPartialFamily,
  scopeSensitivityLabel,
  verdictTone,
} from "../features/viewAccessAs/viewAccessAsPresentation.mjs";

import type {
  EvaluationResponse,
  FamilyResult,
  HarnessModel,
  ProfileResult,
} from "../features/viewAccessAs/viewAccessAsPresentation.d.mts";

/**
 * View Access As — temporary QA route, DEVELOPMENT ONLY.
 *
 * App.tsx imports this module inside an `import.meta.env.DEV` branch, so a
 * production build drops the whole chunk. It is not in the navigation and has
 * no production API: every decision comes from a test-scope harness running on
 * loopback, which composes the real `RecordAccessEvaluator`,
 * `RecordAuthorizationService` and `RecordScopeSqlBuilder`.
 *
 * No rule lives in this file. PERMIT, DENY, the rule names and the refusal
 * codes all arrive already decided. The page renders them and nothing more —
 * a hard-coded expected decision here would make the demonstration agree with
 * itself and prove nothing about production.
 *
 * Removal checklist: docs/VIEW_ACCESS_AS_PAGE.md.
 */
export default function ViewAccessAsPage() {
  const [model, setModel] = useState<HarnessModel | null>(null);
  const [evaluation, setEvaluation] = useState<EvaluationResponse | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [loading, setLoading] = useState(true);

  const [profileA, setProfileA] = useState("Research Staff");
  const [profileB, setProfileB] = useState("CENTRAL");
  const [versionScope, setVersionScope] = useState<"PER_VERSION" | "FAMILY_WIDE">("PER_VERSION");
  const [departmentMatch, setDepartmentMatch] = useState<
    "EXACT_LEAD_UNIT" | "LEAD_UNIT_WITH_DESCENDANTS"
  >("EXACT_LEAD_UNIT");
  const [familyKey, setFamilyKey] = useState("SYN-AWD-P");
  const [versionKey, setVersionKey] = useState<string | null>(null);

  const loadModel = useCallback(async () => {
    setLoading(true);
    try {
      const response = await fetch(harnessUrl("/api/model"));
      if (!response.ok) {
        throw new Error(`harness returned ${response.status}`);
      }
      const loaded: HarnessModel = await response.json();
      setModel(loaded);
      setUnavailable(false);
    } catch {
      /*
       * Any failure here means the harness is not answering. Say so, rather
       * than rendering an empty page - a blank screen is indistinguishable
       * from "everything denied", which is the wrong thing to let a reader
       * conclude from an authorization demonstration.
       */
      setUnavailable(true);
      setModel(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadModel();
  }, [loadModel]);

  useEffect(() => {
    if (!model) {
      return;
    }
    let cancelled = false;
    const run = async () => {
      try {
        const path = evaluateQuery({ profileA, profileB, versionScope, departmentMatch });
        const response = await fetch(harnessUrl(path));
        if (!response.ok) {
          throw new Error(`harness returned ${response.status}`);
        }
        const body: EvaluationResponse = await response.json();
        if (!cancelled) {
          setEvaluation(body);
          setUnavailable(false);
        }
      } catch {
        if (!cancelled) {
          setUnavailable(true);
        }
      }
    };
    void run();
    return () => {
      cancelled = true;
    };
  }, [model, profileA, profileB, versionScope, departmentMatch]);

  /*
   * Memoised on the response, not recreated per render: the two useMemo
   * calls below depend on it, and a fresh [] each render would recompute
   * both every time.
   */
  const results: ProfileResult[] = useMemo(() => evaluation?.results ?? [], [evaluation]);
  const subawardRows = useMemo(() => comparisonRows(results, "subawards"), [results]);
  const negotiationRows = useMemo(() => comparisonRows(results, "negotiations"), [results]);

  if (loading) {
    return (
      <Box sx={{ display: "flex", justifyContent: "center", py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (unavailable) {
    const message = harnessUnavailableMessage();
    return (
      <Box sx={{ maxWidth: 820, mx: "auto", py: 4 }}>
        <Alert severity="warning" action={<Button onClick={() => void loadModel()}>Retry</Button>}>
          <AlertTitle>{message.title}</AlertTitle>
          <Typography variant="body2" sx={{ mb: 1.5 }}>
            {message.detail}
          </Typography>
          <Box
            component="pre"
            sx={{
              m: 0,
              p: 1.5,
              borderRadius: 1,
              bgcolor: "action.hover",
              fontSize: 12,
              overflowX: "auto",
            }}
          >
            {message.command}
          </Box>
        </Alert>
      </Box>
    );
  }

  return (
    <Box sx={{ py: 3 }}>
      <Typography variant="h5" sx={{ mb: 0.5 }}>
        View Access As
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Every decision below is produced by the production authorization engine. This page holds
        no rule and no expected answer.
      </Typography>

      <Alert severity="warning" sx={{ mb: 2 }}>
        <AlertTitle>Temporary QA route — development builds only</AlertTitle>
        Synthetic profiles and synthetic records. No BU data, no archive row read, no grant
        issued, no token minted. Record-level enforcement in DEV is untouched and remains OFF;
        it is enabled only inside the harness&apos;s own isolated evaluation.
      </Alert>

      {evaluation?.engine ? (
        <Paper variant="outlined" sx={{ p: 1.5, mb: 2 }}>
          <Typography variant="overline" color="text.secondary">
            Decisions came from
          </Typography>
          <Box component="pre" sx={{ m: 0, fontSize: 12, overflowX: "auto" }}>
            {`evaluator    ${evaluation.engine.evaluator}
service      ${evaluation.engine.service}
sql builder  ${evaluation.engine.sqlBuilder}
loaded from  ${evaluation.engine.loadedFrom}
harness      ${HARNESS_ORIGIN}  (loopback, test scope)`}
          </Box>
        </Paper>
      ) : null}

      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Stack direction={{ xs: "column", md: "row" }} spacing={2}>
          <TextField
            select
            fullWidth
            size="small"
            label="Profile A"
            value={profileA}
            onChange={(event) => setProfileA(event.target.value)}
          >
            {model?.profiles.map((profile) => (
              <MenuItem key={profile.name} value={profile.name}>
                {profile.name}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            fullWidth
            size="small"
            label="Profile B (compare)"
            value={profileB}
            onChange={(event) => setProfileB(event.target.value)}
          >
            <MenuItem value="">— none —</MenuItem>
            {model?.profiles.map((profile) => (
              <MenuItem key={profile.name} value={profile.name}>
                {profile.name}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            fullWidth
            size="small"
            label="Policy — version scope"
            value={versionScope}
            onChange={(event) =>
              setVersionScope(event.target.value as "PER_VERSION" | "FAMILY_WIDE")
            }
          >
            {model?.versionScopes.map((scope) => (
              <MenuItem key={scope} value={scope}>
                {scope}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            fullWidth
            size="small"
            label="Policy — department match"
            value={departmentMatch}
            onChange={(event) =>
              setDepartmentMatch(
                event.target.value as "EXACT_LEAD_UNIT" | "LEAD_UNIT_WITH_DESCENDANTS",
              )
            }
          >
            {model?.departmentMatches.map((match) => (
              <MenuItem key={match} value={match}>
                {match}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
      </Paper>

      <Typography variant="overline" color="text.secondary">
        Award family
      </Typography>
      <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1, mb: 2 }}>
        {model?.families.map((family) => (
          <Chip
            key={family.familyKey}
            label={`${family.familyKey} · ${family.label}`}
            variant={family.familyKey === familyKey ? "filled" : "outlined"}
            color={family.familyKey === familyKey ? "primary" : "default"}
            onClick={() => {
              setFamilyKey(family.familyKey);
              setVersionKey(family.versionKeys[0] ?? null);
            }}
          />
        ))}
      </Stack>

      <Stack direction={{ xs: "column", lg: "row" }} spacing={2} sx={{ mb: 2 }}>
        {results.map((result) => (
          <ProfileCard
            key={result.profile}
            result={result}
            family={familyFor(result, familyKey)}
            selectedVersion={versionKey}
            onSelectVersion={setVersionKey}
          />
        ))}
      </Stack>

      <ComparisonCard
        title="Subaward inheritance"
        caption="Resolved through subaward_funding.award_id by the real service. A funding reference that resolves to no archived Award authorises nothing."
        noun="Subaward"
        rows={subawardRows}
        profiles={results.map((result) => result.profile)}
      />

      <ComparisonCard
        title="Negotiation inheritance"
        caption="Lead unit, or an association only where the association type explicitly names one."
        noun="Negotiation"
        rows={negotiationRows}
        profiles={results.map((result) => result.profile)}
      />

      {results[0] ? (
        <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
          <Typography variant="h6" sx={{ mb: 0.5 }}>
            Scoping SQL, as the engine renders it
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            The predicate RecordScopeSqlBuilder puts in the WHERE clause for each module, for{" "}
            {results[0].profile}. Rendered, not executed — the
            *RecordAuthorizationIntegrationTest suites execute the same builder against a real
            PostgreSQL.
          </Typography>
          {Object.entries(results[0].renderedSql).map(([module, sql]) => (
            <Box key={module} sx={{ mb: 1.5 }}>
              <Typography variant="overline" color="primary">
                {module}
              </Typography>
              <Box
                component="pre"
                sx={{
                  m: 0,
                  p: 1.5,
                  borderRadius: 1,
                  bgcolor: "action.hover",
                  fontSize: 12,
                  overflowX: "auto",
                  whiteSpace: "pre-wrap",
                }}
              >
                {sql}
              </Box>
            </Box>
          ))}
        </Paper>
      ) : null}
    </Box>
  );
}

function ProfileCard({
  result,
  family,
  selectedVersion,
  onSelectVersion,
}: {
  result: ProfileResult;
  family: FamilyResult | null;
  selectedVersion: string | null;
  onSelectVersion: (versionKey: string) => void;
}) {
  const tally = familyTally(family);
  const sensitivity = scopeSensitivityLabel(family);
  return (
    <Paper variant="outlined" sx={{ p: 2, flex: 1, minWidth: 0 }}>
      <Typography variant="h6">{result.profile}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
        {result.description}
      </Typography>
      <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 0.5, mb: 1.5 }}>
        {result.grants.map((grant) => (
          <Chip key={grant} label={grant} size="small" variant="outlined" />
        ))}
      </Stack>
      <Typography variant="overline" color="text.secondary">
        Resolved scope
      </Typography>
      <Typography variant="body2" sx={{ fontFamily: "monospace", fontSize: 12, mb: 1.5 }}>
        {result.outcome}
      </Typography>

      {family ? (
        <>
          <Divider sx={{ mb: 1.5 }} />
          <Typography variant="overline" color="text.secondary">
            {family.familyKey} · lead unit {family.leadUnit}
          </Typography>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Version</TableCell>
                <TableCell>Decision</TableCell>
                <TableCell>Rule</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {family.versions.map((version) => (
                <TableRow
                  key={version.versionKey}
                  hover
                  selected={version.versionKey === selectedVersion}
                  onClick={() => onSelectVersion(version.versionKey)}
                  sx={{ cursor: "pointer" }}
                >
                  <TableCell sx={{ fontFamily: "monospace" }}>{version.versionKey}</TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      label={version.verdict}
                      color={verdictTone(version.verdict)}
                    />
                  </TableCell>
                  <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>
                    {version.rule}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>

          {isPartialFamily(family) ? (
            <Alert severity="warning" sx={{ mt: 1.5 }}>
              <strong>Partial family access.</strong> {tally.permitted} of {tally.total} versions
              visible.
            </Alert>
          ) : null}

          {sensitivity ? (
            <Alert severity={family.changesUnderOtherScope ? "info" : "success"} sx={{ mt: 1 }}>
              {sensitivity}
            </Alert>
          ) : null}

          <Alert
            severity={family.aiVerdict === "ALLOWED" ? "success" : "warning"}
            sx={{ mt: 1 }}
          >
            Family-spanning read (Award Summary, Award Questions, history):{" "}
            <strong>{family.aiVerdict}</strong> — {family.aiRule}
          </Alert>
        </>
      ) : null}
    </Paper>
  );
}

function ComparisonCard({
  title,
  caption,
  noun,
  rows,
  profiles,
}: {
  title: string;
  caption: string;
  noun: string;
  rows: ReturnType<typeof comparisonRows>;
  profiles: string[];
}) {
  return (
    <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
      <Typography variant="h6" sx={{ mb: 0.5 }}>
        {title}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        {caption}
      </Typography>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>{noun}</TableCell>
            <TableCell>Basis</TableCell>
            {profiles.map((profile) => (
              <TableCell key={profile}>{profile}</TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={row.id}>
              <TableCell>
                <Typography sx={{ fontFamily: "monospace", fontSize: 13 }}>{row.id}</Typography>
                <Typography variant="caption" color="text.secondary">
                  {row.label}
                </Typography>
              </TableCell>
              <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{row.rule}</TableCell>
              {row.verdicts.map((entry) => (
                <TableCell key={entry.profile}>
                  <Chip size="small" label={entry.verdict} color={verdictTone(entry.verdict)} />
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Paper>
  );
}
