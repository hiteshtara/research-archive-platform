import { Card, CardContent, Chip, Stack, Typography } from "@mui/material";
import { Fragment } from "react";
import type { ReactNode } from "react";

export interface WorkspaceHeaderChip {
  // Either a plain label (rendered as a Chip via variant/color below),
  // or a fully custom element (e.g. <StatusPill/>) rendered as-is -
  // needed because a status chip's color is semantic, not a flat
  // label/variant/color triple the way the other header chips are.
  label?: string;
  variant?: "outlined" | "filled";
  color?: "default" | "primary";
  element?: ReactNode;
}

// The card+title+subtitle+chip-row header every workspace page opens
// with. Reproduces SubawardWorkspacePage.tsx's header exactly (title,
// optional subtitle, optional eyebrow line, then a wrapped chip row).
// Award's own header (a bordered Box, no Card, different typography
// scale) is a different visual today - left as-is until Award's own
// migration decides whether to converge on this shape.
export function WorkspaceHeader({
  title,
  subtitle,
  eyebrow,
  chips,
}: {
  title: string;
  subtitle?: string | null;
  eyebrow?: string | null;
  chips?: WorkspaceHeaderChip[];
}) {
  return (
    <Card>
      <CardContent>
        <Typography variant="h4">{title}</Typography>

        {subtitle && (
          <Typography variant="h6" sx={{ mt: 1 }}>
            {subtitle}
          </Typography>
        )}

        {eyebrow && (
          <Typography color="text.secondary" sx={{ mt: 1 }}>
            {eyebrow}
          </Typography>
        )}

        {chips && chips.length > 0 && (
          <Stack
            sx={{
              mt: 3,
              flexDirection: "row",
              alignItems: "center",
              flexWrap: "wrap",
              gap: 1,
            }}
          >
            {chips.map((chip, index) =>
              chip.element ? (
                <Fragment key={index}>{chip.element}</Fragment>
              ) : (
                <Chip
                  key={index}
                  size="small"
                  variant={chip.variant ?? "outlined"}
                  color={chip.color ?? "default"}
                  label={chip.label}
                />
              ),
            )}
          </Stack>
        )}
      </CardContent>
    </Card>
  );
}
