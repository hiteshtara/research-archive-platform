import { useState } from "react";
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  Divider,
  FormControlLabel,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "react-router-dom";

import { ReadOnlyBanner } from "../../features/securityAdmin/ReadOnlyBanner";
import {
  ReasonConfirmPanel,
  ReasonField,
} from "../../features/securityAdmin/ReasonConfirmPanel";
import { normalizeReason, reasonProblem } from "../../features/securityAdmin/reasonRule.mjs";
import { useAdminWritesAllowed } from "../../features/securityAdmin/useAdminWritesAllowed";

import {
  createGrant,
  getAdminIdentity,
  revokeGrant,
  setSuspension,
} from "../../api/adminClient";
import {
  GRANT_TYPES,
  accessBanner,
  auditActionLabel,
  formatDate,
  grantScopeNote,
  grantTypeLabel,
  validateGrantForm,
} from "../../features/admin/adminPresentation.mjs";

/**
 * Manage one identity: grants, suspension, links and its audit trail.
 *
 * Two things on this screen are load-bearing rather than decorative. The
 * access banner states plainly when an identity - including an administrator -
 * has no archive access, because "I am an administrator, why can I see
 * nothing?" is otherwise the first support ticket. And research staff
 * relationships are shown read-only with a caption saying they confer no
 * access: historical visibility is not authorization.
 */
export function AdminIdentityWorkspacePage() {
  const { identifier = "" } = useParams();
  const queryClient = useQueryClient();
  /*
   * No access type is preselected. The form used to start on CENTRAL - access
   * to every record in every unit - so the broadest grant was one click away
   * from a form nobody had read.
   */
  const [form, setForm] = useState({
    type: "",
    unitNumber: "",
    includeDescendants: false,
    ioValue: "",
    expiresAt: "",
    reason: "",
  });
  /** Which in-page confirmation is open: a grant id to revoke, or suspension. */
  const [confirming, setConfirming] = useState<
    { kind: "revoke"; grantId: number } | { kind: "suspension" } | null
  >(null);

  const identity = useQuery({
    queryKey: ["admin", "identity", identifier],
    queryFn: ({ signal }) => getAdminIdentity(identifier, signal),
    enabled: identifier.length > 0,
  });

  const refresh = () =>
    queryClient.invalidateQueries({ queryKey: ["admin", "identity", identifier] });

  const grant = useMutation({
    mutationFn: () =>
      createGrant(identifier, {
        type: form.type,
        unitNumber: form.unitNumber || null,
        includeDescendants: form.includeDescendants,
        ioValue: form.ioValue || null,
        expiresAt: form.expiresAt ? new Date(form.expiresAt).toISOString() : null,
        reason: normalizeReason(form.reason),
      }),
    onSuccess: refresh,
  });

  const revoke = useMutation({
    mutationFn: (input: { grantId: number; reason: string }) =>
      revokeGrant(identifier, input.grantId, input.reason),
    onSuccess: () => {
      setConfirming(null);
      return refresh();
    },
  });

  const suspend = useMutation({
    mutationFn: (input: { suspended: boolean; reason: string }) =>
      setSuspension(identifier, input.suspended, input.reason),
    onSuccess: () => {
      setConfirming(null);
      return refresh();
    },
  });

  const data = identity.data;
  const banner = data ? accessBanner(data) : null;

  /*
   * Every mutation control on this page is gated on what the SERVER reports.
   * Destructive actions open an in-page reason panel rather than a browser
   * prompt: a prompt could not enforce the reason rule, blocked the page, and
   * could not be tested. The opening buttons are disabled while writes are
   * off, so the panel cannot even be reached then.
   */
  const writesAllowed = useAdminWritesAllowed();

  // Live validation: Grant access stays disabled until the form is valid.
  const problems = validateGrantForm(form);
  const reasonMessage = reasonProblem(form.reason);
  // The reason's problem is shown inline under its field, not in this list.
  const otherProblems = problems.filter((problem) => problem !== reasonMessage);
  const grantValid = problems.length === 0;

  function submitGrant() {
    if (!writesAllowed || !grantValid) return;
    grant.mutate();
  }

  return (
    <Stack spacing={3} sx={{ p: 3 }}>
      <Box>
        <Typography variant="h5">{identifier}</Typography>
        <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
          {data?.administrator && <Chip size="small" color="info" label="ARCHIVE_ADMIN" />}
          {data?.suspended && <Chip size="small" color="error" label="Suspended" />}
        </Stack>
      </Box>

      <ReadOnlyBanner />

      {identity.isError && (
        <Alert severity="error">
          This identity could not be loaded. If record authorization is not yet
          enabled, the administrative API is not available.
        </Alert>
      )}

      {banner && <Alert severity={banner.level === "danger" ? "error" : "warning"}>{banner.text}</Alert>}

      <Divider />

      <Box>
        <Typography variant="h6">Archive access</Typography>
        <Stack spacing={1} sx={{ mt: 1 }}>
          {(data?.grants ?? []).length === 0 && (
            <Typography variant="body2" color="text.secondary">
              No grants.
            </Typography>
          )}
          {(data?.grants ?? []).map((item) => (
            <Box
              key={item.grantId}
              sx={{ border: 1, borderColor: "divider", borderRadius: 1, p: 2 }}
            >
              <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap" }}>
                <Typography variant="subtitle2">{item.label}</Typography>
                {!item.active && <Chip size="small" label="not in force" />}
                {item.expiresAt && (
                  <Chip size="small" label={`expires ${formatDate(item.expiresAt)}`} />
                )}
              </Stack>
              <Typography variant="body2" color="text.secondary">
                {item.scopeNote ?? grantScopeNote(item)}
              </Typography>
              {item.active && (
                <Button
                  size="small"
                  color="error"
                  sx={{ mt: 1 }}
                  disabled={!writesAllowed || revoke.isPending}
                  onClick={() => {
                    if (!writesAllowed) return;
                    setConfirming({ kind: "revoke", grantId: item.grantId });
                  }}
                >
                  Revoke
                </Button>
              )}
              {item.active
                && confirming?.kind === "revoke"
                && confirming.grantId === item.grantId && (
                <ReasonConfirmPanel
                  title="Revoke this grant"
                  confirmLabel="Confirm revoke"
                  writesAllowed={writesAllowed}
                  pending={revoke.isPending}
                  onCancel={() => setConfirming(null)}
                  onConfirm={(reason) => revoke.mutate({ grantId: item.grantId, reason })}
                />
              )}
            </Box>
          ))}
        </Stack>
      </Box>

      <Box>
        <Typography variant="h6">Grant access</Typography>
        {form.type !== "" && otherProblems.length > 0 && (
          <Alert severity="warning" sx={{ mt: 1 }} data-testid="grant-form-problems">
            <ul style={{ margin: 0, paddingInlineStart: "1.2em" }}>
              {otherProblems.map((problem) => (
                <li key={problem}>{problem}</li>
              ))}
            </ul>
          </Alert>
        )}
        <Stack spacing={2} sx={{ mt: 2, maxWidth: 520 }}>
          <TextField
            select
            size="small"
            label="Type"
            required
            value={form.type}
            onChange={(event) => setForm({ ...form, type: event.target.value })}
            slotProps={{
              inputLabel: { shrink: true },
              select: {
                displayEmpty: true,
                renderValue: (value) =>
                  value ? (
                    grantTypeLabel(String(value))
                  ) : (
                    <Typography component="span" color="text.secondary">
                      Choose access type…
                    </Typography>
                  ),
              },
            }}
          >
            {GRANT_TYPES.map((type: string) => (
              <MenuItem key={type} value={type}>
                {grantTypeLabel(type)}
              </MenuItem>
            ))}
          </TextField>

          {form.type === "CENTRAL" && (
            <Alert severity="warning" data-testid="central-grant-warning">
              Central access grants access to every record in every unit. It
              requires a second administrator&apos;s approval.
            </Alert>
          )}

          {form.type === "UNIT" && (
            <>
              <TextField
                size="small"
                label="Unit number"
                value={form.unitNumber}
                onChange={(event) => setForm({ ...form, unitNumber: event.target.value })}
              />
              <FormControlLabel
                control={
                  <Checkbox
                    checked={form.includeDescendants}
                    onChange={(event) =>
                      setForm({ ...form, includeDescendants: event.target.checked })
                    }
                  />
                }
                label="Include sub-units"
              />
            </>
          )}

          {form.type === "IO" && (
            <TextField
              size="small"
              label="SAP account number"
              helperText="Applies to Awards only; does not reach related Proposals, Negotiations or Subawards."
              value={form.ioValue}
              onChange={(event) => setForm({ ...form, ioValue: event.target.value })}
            />
          )}

          <TextField
            size="small"
            type="date"
            label="Expires (optional)"
            slotProps={{ inputLabel: { shrink: true } }}
            helperText="Leave empty for no automatic expiry."
            value={form.expiresAt}
            onChange={(event) => setForm({ ...form, expiresAt: event.target.value })}
          />
          <ReasonField
            value={form.reason}
            onChange={(reason) => setForm({ ...form, reason })}
          />
          <Button
            variant="contained"
            onClick={() => {
              if (!writesAllowed) return;
              submitGrant();
            }}
            disabled={!writesAllowed || !grantValid || grant.isPending}
          >
            Grant access
          </Button>
          {grant.isError && <Alert severity="error">The grant was not created.</Alert>}
        </Stack>
      </Box>

      <Divider />

      <Box>
        <Typography variant="h6">Suspension</Typography>
        <Typography variant="body2" color="text.secondary">
          Suspension overrides every grant without revoking any of them, so
          restoring access is one action rather than a re-grant.
        </Typography>
        <Button
          sx={{ mt: 1 }}
          color={data?.suspended ? "primary" : "error"}
          variant="outlined"
          disabled={!writesAllowed || suspend.isPending}
          onClick={() => {
            if (!writesAllowed) return;
            setConfirming({ kind: "suspension" });
          }}
        >
          {data?.suspended ? "Restore access" : "Suspend access"}
        </Button>
        {confirming?.kind === "suspension" && (
          <ReasonConfirmPanel
            title={data?.suspended ? "Restore access" : "Suspend access"}
            confirmLabel={data?.suspended ? "Confirm restore" : "Confirm suspension"}
            destructive={!data?.suspended}
            writesAllowed={writesAllowed}
            pending={suspend.isPending}
            onCancel={() => setConfirming(null)}
            onConfirm={(reason) => suspend.mutate({ suspended: !data?.suspended, reason })}
          />
        )}
      </Box>

      <Divider />

      <Box>
        <Typography variant="h6">Identity links</Typography>
        {(data?.links ?? []).length === 0 && (
          <Typography variant="body2" color="text.secondary">
            No links. This person cannot be resolved, so they have no access
            regardless of any grant.
          </Typography>
        )}
        {(data?.links ?? []).map((link) => (
          <Typography key={link.linkId} variant="body2">
            {link.loginName ?? link.cognitoSubject} — {link.status}
            {link.kualiPersonId ? ` — Kuali person ${link.kualiPersonId}` : ""}
          </Typography>
        ))}
      </Box>

      <Divider />

      <Box>
        <Typography variant="h6">Audit history</Typography>
        <Stack spacing={0.5} sx={{ mt: 1 }}>
          {(data?.recentAudit ?? []).map((entry) => (
            <Typography key={entry.auditId} variant="body2">
              {formatDate(entry.occurredAt)} — {auditActionLabel(entry.action)}
              {entry.detail ? ` — ${entry.detail}` : ""}
            </Typography>
          ))}
          {(data?.recentAudit ?? []).length === 0 && (
            <Typography variant="body2" color="text.secondary">
              Nothing recorded for this identity.
            </Typography>
          )}
        </Stack>
      </Box>
    </Stack>
  );
}
