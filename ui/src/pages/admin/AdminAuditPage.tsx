import { useState } from "react";
import {
  Alert,
  Box,
  Button,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getAdminAudit } from "../../api/adminClient";
import { auditActionLabel } from "../../features/admin/adminPresentation.mjs";

/**
 * Administrative audit history.
 *
 * Read-only by construction: authz.access_audit has a database trigger that
 * rejects UPDATE and DELETE, so nothing here could alter a row even if a
 * future change tried.
 */
export function AdminAuditPage() {
  const [identity, setIdentity] = useState("");
  const [applied, setApplied] = useState("");

  const audit = useQuery({
    queryKey: ["admin", "audit", applied],
    queryFn: ({ signal }) =>
      getAdminAudit({ identity: applied || undefined, limit: 200 }, signal),
  });

  return (
    <Stack spacing={3} sx={{ p: 3 }}>
      <Box>
        <Typography variant="h5">Administrative audit</Typography>
        <Typography variant="body2" color="text.secondary">
          Every grant, revocation, suspension, link and role change. Record
          views and searches are deliberately not logged.
        </Typography>
      </Box>

      <Stack
        direction="row"
        spacing={2}
        component="form"
        onSubmit={(event) => {
          event.preventDefault();
          setApplied(identity.trim());
        }}
      >
        <TextField
          size="small"
          label="Filter by institutional identifier"
          value={identity}
          onChange={(event) => setIdentity(event.target.value)}
          sx={{ minWidth: 320 }}
        />
        <Button type="submit">Apply</Button>
      </Stack>

      {audit.isError && (
        <Alert severity="error">
          The audit history could not be loaded. If record authorization is not
          yet enabled, the administrative API is not available.
        </Alert>
      )}

      <Stack spacing={1}>
        {(audit.data ?? []).map((entry) => (
          <Box
            key={entry.auditId}
            sx={{ border: 1, borderColor: "divider", borderRadius: 1, p: 1.5 }}
          >
            <Typography variant="subtitle2">{auditActionLabel(entry.action)}</Typography>
            <Typography variant="caption" color="text.secondary">
              {entry.occurredAt} — actor {entry.actor}
              {entry.subject ? ` — subject ${entry.subject}` : ""}
            </Typography>
            {entry.detail && (
              <Typography variant="body2" sx={{ mt: 0.5 }}>
                {entry.detail}
              </Typography>
            )}
          </Box>
        ))}
        {(audit.data ?? []).length === 0 && !audit.isError && (
          <Typography variant="body2" color="text.secondary">
            Nothing recorded.
          </Typography>
        )}
      </Stack>
    </Stack>
  );
}
