import { useState } from "react";
import { Box, Button, Stack, TextField, Typography } from "@mui/material";

import { normalizeReason, reasonProblem } from "./reasonRule.mjs";

const REASON_HINT = "Required: 10-500 characters, at least two words, no placeholder text.";

interface ReasonFieldProps {
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  label?: string;
  autoFocus?: boolean;
}

/**
 * A reason input that states the reason rule inline. The problem is shown as
 * an error only once something has been typed, so an untouched form reads as
 * a requirement rather than a mistake.
 */
export function ReasonField({
  value,
  onChange,
  disabled,
  label = "Reason",
  autoFocus,
}: ReasonFieldProps) {
  const problem = reasonProblem(value);
  const touched = value.length > 0;
  return (
    <TextField
      size="small"
      label={label}
      value={value}
      disabled={disabled}
      autoFocus={autoFocus}
      required
      error={touched && problem !== null}
      helperText={touched && problem ? problem : REASON_HINT}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}

interface ReasonConfirmPanelProps {
  /** What is being confirmed, e.g. "Revoke this grant". */
  title: string;
  confirmLabel: string;
  /** Called with the normalised reason, only when it satisfies the rule. */
  onConfirm: (reason: string) => void;
  onCancel: () => void;
  /** Server-reported write availability; the panel refuses to act without it. */
  writesAllowed: boolean;
  pending?: boolean;
  destructive?: boolean;
}

/**
 * The in-page replacement for window.prompt on destructive actions.
 *
 * A browser prompt could not enforce the reason rule, blocked the page, and
 * could not be exercised by a component test. This panel holds the reason
 * in-page, keeps Confirm disabled until the reason is valid, and still
 * refuses to act when the server reports writes as unavailable.
 */
export function ReasonConfirmPanel({
  title,
  confirmLabel,
  onConfirm,
  onCancel,
  writesAllowed,
  pending = false,
  destructive = true,
}: ReasonConfirmPanelProps) {
  const [reason, setReason] = useState("");
  const valid = reasonProblem(reason) === null;

  return (
    <Box
      role="group"
      aria-label={title}
      sx={{ border: 1, borderColor: "divider", borderRadius: 1, p: 2, mt: 1 }}
    >
      <Typography variant="subtitle2" sx={{ mb: 1 }}>
        {title}
      </Typography>
      <Stack spacing={1.5}>
        <ReasonField
          value={reason}
          onChange={setReason}
          disabled={!writesAllowed}
          autoFocus
        />
        <Stack direction="row" spacing={1}>
          <Button
            size="small"
            variant="contained"
            color={destructive ? "error" : "primary"}
            disabled={!writesAllowed || !valid || pending}
            onClick={() => {
              // Belt and braces: the button is already disabled in each case.
              if (!writesAllowed || !valid) return;
              onConfirm(normalizeReason(reason));
            }}
          >
            {confirmLabel}
          </Button>
          <Button size="small" onClick={onCancel}>
            Cancel
          </Button>
        </Stack>
      </Stack>
    </Box>
  );
}
