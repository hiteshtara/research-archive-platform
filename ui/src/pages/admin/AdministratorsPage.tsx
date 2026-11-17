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
import { ReadOnlyBanner } from "../../features/securityAdmin/ReadOnlyBanner";
import {
  ReasonConfirmPanel,
  ReasonField,
} from "../../features/securityAdmin/ReasonConfirmPanel";
import { isValidReason, normalizeReason } from "../../features/securityAdmin/reasonRule.mjs";
import { useAdminWritesAllowed } from "../../features/securityAdmin/useAdminWritesAllowed";

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
  /** The administrator whose removal is being confirmed in-page, if any. */
  const [removing, setRemoving] = useState<string | null>(null);

  const administrators = useQuery({
    queryKey: ["admin", "administrators"],
    queryFn: ({ signal }) => listAdministrators(signal),
  });

  const refresh = () =>
    queryClient.invalidateQueries({ queryKey: ["admin", "administrators"] });

  const assign = useMutation({
    mutationFn: () => assignAdministrator(identifier.trim(), normalizeReason(reason)),
    onSuccess: () => {
      setIdentifier("");
      setReason("");
      return refresh();
    },
  });

  const revoke = useMutation({
    mutationFn: (input: { identifier: string; reason: string }) =>
      revokeAdministrator(input.identifier, input.reason),
    onSuccess: () => {
      setRemoving(null);
      return refresh();
    },
  });

  const active = administrators.data ?? [];
  /*
   * Writes are gated on what the SERVER reports, not on anything this page
   * decides. While administration is read-only every mutation control is
   * disabled, so an administrator cannot fill in a form, press the button
   * and be refused with a 409 - which is how this page behaved before.
   */
  const writesAllowed = useAdminWritesAllowed();
  const canRevoke = canRevokeAdministrator(active.length) && writesAllowed;

  return (
    <Stack spacing={3} sx={{ p: 3 }}>
      <Box>
        <Typography variant="h5">Archive administrators</Typography>
        <Typography variant="body2" color="text.secondary">
          Administrators manage access. Holding this role grants no access to
          archive records.
        </Typography>
      </Box>

      <ReadOnlyBanner />

      {administrators.isError && (
        <Alert severity="error">
          The administrator list could not be loaded. If Security
          Administration is not enabled in this environment, the
          administrative API is not available &mdash; separate from
          record-level authorization, which can stay off.
        </Alert>
      )}

      <Stack spacing={1}>
        {active.map((member) => (
          <Box key={member.adminMemberId}>
            <Stack
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
              <Tooltip
                title={
                  !writesAllowed
                    ? "Read-only mode: administrator changes are disabled in this environment"
                    : revokeAdministratorDisabledReason(active.length) ?? ""
                }
              >
                <span>
                  <Button
                    size="small"
                    color="error"
                    disabled={!canRevoke || revoke.isPending}
                    onClick={() => {
                      // No browser prompt: the reason is taken in-page, where
                      // the reason rule can be enforced.
                      if (!canRevoke) return;
                      setRemoving(member.institutionalIdentifier);
                    }}
                  >
                    Remove
                  </Button>
                </span>
              </Tooltip>
            </Stack>
            {removing === member.institutionalIdentifier && (
              <ReasonConfirmPanel
                title={`Remove ${member.institutionalIdentifier} as an administrator`}
                confirmLabel="Confirm removal"
                writesAllowed={canRevoke}
                pending={revoke.isPending}
                onCancel={() => setRemoving(null)}
                onConfirm={(why) =>
                  revoke.mutate({ identifier: member.institutionalIdentifier, reason: why })
                }
              />
            )}
          </Box>
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
            disabled={!writesAllowed}
            onChange={(event) => setIdentifier(event.target.value)}
          />
          <ReasonField value={reason} disabled={!writesAllowed} onChange={setReason} />
          <Tooltip
            title={
              writesAllowed
                ? ""
                : "Read-only mode: appointing an administrator is disabled in this environment"
            }
          >
            <span>
              <Button
                variant="contained"
                disabled={
                  !writesAllowed
                    || !identifier.trim()
                    || !isValidReason(reason)
                    || assign.isPending
                }
                onClick={() => {
                  // Belt and braces: the button is already disabled, and a
                  // synthetic click must still not reach the API.
                  if (!writesAllowed || !isValidReason(reason)) return;
                  assign.mutate();
                }}
              >
                Appoint
              </Button>
            </span>
          </Tooltip>
          {assign.isError && <Alert severity="error">The appointment was not made.</Alert>}
        </Stack>
      </Box>
    </Stack>
  );
}
