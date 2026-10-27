import { useState } from "react";
import {
  Alert,
  Box,
  Button,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  assignAdministrator,
  listAdministrators,
  revokeAdministrator,
} from "../../api/adminClient";
import {
  canRevokeAdministrator,
  formatDate,
  revokeAdministratorDisabledReason,
} from "../../features/admin/adminPresentation.mjs";

/**
 * ARCHIVE_ADMIN membership.
 *
 * The revoke control is disabled at one active administrator so the server's
 * 409 is a last-resort guard rather than how the rule is discovered. The
 * server still enforces it under a row lock: two concurrent revokes of the
 * final two administrators serialise and exactly one is refused.
 */
export function AdministratorsPage() {
  const queryClient = useQueryClient();
  const [identifier, setIdentifier] = useState("");
  const [reason, setReason] = useState("");

  const administrators = useQuery({
    queryKey: ["admin", "administrators"],
    queryFn: ({ signal }) => listAdministrators(signal),
  });

  const refresh = () =>
    queryClient.invalidateQueries({ queryKey: ["admin", "administrators"] });

  const assign = useMutation({
    mutationFn: () => assignAdministrator(identifier.trim(), reason.trim()),
    onSuccess: () => {
      setIdentifier("");
      setReason("");
      return refresh();
    },
  });

  const revoke = useMutation({
    mutationFn: (input: { identifier: string; reason: string }) =>
      revokeAdministrator(input.identifier, input.reason),
    onSuccess: refresh,
  });

  const active = administrators.data ?? [];
  const canRevoke = canRevokeAdministrator(active.length);

  return (
    <Stack spacing={3} sx={{ p: 3 }}>
      <Box>
        <Typography variant="h5">Archive administrators</Typography>
        <Typography variant="body2" color="text.secondary">
          Administrators manage access. Holding this role grants no access to
          archive records.
        </Typography>
      </Box>

      {administrators.isError && (
        <Alert severity="error">
          The administrator list could not be loaded. If record authorization
          is not yet enabled, the administrative API is not available.
        </Alert>
      )}

      <Stack spacing={1}>
        {active.map((member) => (
          <Stack
            key={member.adminMemberId}
            direction="row"
            spacing={2}
            sx={{
              alignItems: "center",
              border: 1,
              borderColor: "divider",
              borderRadius: 1,
              p: 2,
            }}
          >
            <Box sx={{ flexGrow: 1 }}>
              <Typography variant="subtitle2">{member.institutionalIdentifier}</Typography>
              <Typography variant="caption" color="text.secondary">
                Appointed {formatDate(member.grantedAt)} by {member.grantedBy}
                {member.reason ? ` — ${member.reason}` : ""}
              </Typography>
            </Box>
            <Tooltip title={revokeAdministratorDisabledReason(active.length) ?? ""}>
              <span>
                <Button
                  size="small"
                  color="error"
                  disabled={!canRevoke || revoke.isPending}
                  onClick={() => {
                    const why = window.prompt("Reason for removing this administrator:");
                    if (why && why.trim()) {
                      revoke.mutate({
                        identifier: member.institutionalIdentifier,
                        reason: why.trim(),
                      });
                    }
                  }}
                >
                  Remove
                </Button>
              </span>
            </Tooltip>
          </Stack>
        ))}
      </Stack>

      {revoke.isError && (
        <Alert severity="error">
          The administrator was not removed. The archive must always have at
          least one.
        </Alert>
      )}

      <Box>
        <Typography variant="h6">Appoint an administrator</Typography>
        <Stack spacing={2} sx={{ mt: 2, maxWidth: 520 }}>
          <TextField
            size="small"
            label="Institutional identifier"
            value={identifier}
            onChange={(event) => setIdentifier(event.target.value)}
          />
          <TextField
            size="small"
            label="Reason"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
          <Button
            variant="contained"
            disabled={!identifier.trim() || !reason.trim() || assign.isPending}
            onClick={() => assign.mutate()}
          >
            Appoint
          </Button>
          {assign.isError && <Alert severity="error">The appointment was not made.</Alert>}
        </Stack>
      </Box>
    </Stack>
  );
}
