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
  const [form, setForm] = useState({
    type: "CENTRAL",
    unitNumber: "",
    includeDescendants: false,
    ioValue: "",
    expiresAt: "",
    reason: "",
  });
  const [problems, setProblems] = useState<string[]>([]);

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
        reason: form.reason || null,
      }),
    onSuccess: refresh,
  });

  const revoke = useMutation({
    mutationFn: (input: { grantId: number; reason: string }) =>
      revokeGrant(identifier, input.grantId, input.reason),
    onSuccess: refresh,
  });

  const suspend = useMutation({
    mutationFn: (input: { suspended: boolean; reason: string }) =>
      setSuspension(identifier, input.suspended, input.reason),
    onSuccess: refresh,
  });

  const data = identity.data;
  const banner = data ? accessBanner(data) : null;

  function submitGrant() {
    const found = validateGrantForm(form);
    setProblems(found);
    if (found.length === 0) {
      grant.mutate();
    }
  }

  function askReason(action: string): string | null {
    // Destructive actions require a typed reason: an unexplained audit row is
    // a poor audit row, and the reason column exists on every table involved.
    const reason = window.prompt(`Reason for ${action}:`);
    return reason && reason.trim() ? reason.trim() : null;
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
                  onClick={() => {
                    const reason = askReason("revoking this grant");
                    if (reason) revoke.mutate({ grantId: item.grantId, reason });
                  }}
                >
                  Revoke
                </Button>
              )}
            </Box>
          ))}
        </Stack>
      </Box>

      <Box>
        <Typography variant="h6">Grant access</Typography>
        {problems.length > 0 && (
          <Alert severity="warning" sx={{ mt: 1 }}>
            <ul style={{ margin: 0, paddingInlineStart: "1.2em" }}>
              {problems.map((problem) => (
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
            value={form.type}
            onChange={(event) => setForm({ ...form, type: event.target.value })}
          >
            {GRANT_TYPES.map((type: string) => (
              <MenuItem key={type} value={type}>
                {grantTypeLabel(type)}
              </MenuItem>
            ))}
          </TextField>

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
          <TextField
            size="small"
            label="Reason"
            value={form.reason}
            onChange={(event) => setForm({ ...form, reason: event.target.value })}
          />
          <Button variant="contained" onClick={submitGrant} disabled={grant.isPending}>
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
          onClick={() => {
            const reason = askReason(data?.suspended ? "restoring access" : "suspending access");
            if (reason) suspend.mutate({ suspended: !data?.suspended, reason });
          }}
        >
          {data?.suspended ? "Restore access" : "Suspend access"}
        </Button>
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
