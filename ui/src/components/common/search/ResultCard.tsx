import { Box, Card, CardContent, Stack, Typography } from "@mui/material";
import type { SxProps, Theme } from "@mui/material";
import type { ReactNode } from "react";
import { Link as RouterLink } from "react-router-dom";

/**
 * The one result-card architecture every archive module uses.
 *
 * The DATA differs per module; the hierarchy, spacing and typography do
 * not:
 *
 *   LINE 1  primary business identifier · secondary identifier · status
 *   LINE 2  human-readable title
 *   LINE 3  people / sponsor / organization / unit
 *   RIGHT   one module-specific summary (amount, date, version count...)
 *
 * The whole card is a real anchor, not a div with an onClick, so
 * Cmd-click, Ctrl-click, middle-click, "Open in new tab" and "Copy link
 * address" all work. Link styling is reset to inherit so this is
 * visually identical to the click-handler card it replaces.
 */
export function ResultCard({
  to,
  identifier,
  secondaryIdentifier,
  status,
  title,
  metadata,
  rightSlot,
  emphasized = false,
  banner,
  sx,
}: {
  to: string;
  identifier: ReactNode;
  secondaryIdentifier?: ReactNode;
  status?: ReactNode;
  title?: ReactNode;
  metadata?: ReactNode;
  rightSlot?: ReactNode;
  emphasized?: boolean;
  banner?: ReactNode;
  sx?: SxProps<Theme>;
}) {
  return (
    <Card
      component={RouterLink}
      to={to}
      variant="outlined"
      sx={{
        display: "block",
        textDecoration: "none",
        color: "inherit",
        transition: "border-color .12s ease",
        ...(emphasized
          ? {
              borderColor: "primary.main",
              borderWidth: 2,
              "&:hover": { borderColor: "primary.dark" },
            }
          : { "&:hover": { borderColor: "primary.main" } }),
        ...sx,
      }}
    >
      <CardContent
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          gap: 2,
          "&:last-child": { pb: 2 },
        }}
      >
        <Box sx={{ minWidth: 0 }}>
          {banner && (
            <Stack
              direction="row"
              spacing={1}
              sx={{ alignItems: "center", mb: 0.5 }}
            >
              {banner}
            </Stack>
          )}

          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <Typography sx={{ fontWeight: 700 }}>{identifier}</Typography>
            {secondaryIdentifier}
            {status}
          </Stack>

          {title && (
            <Typography
              variant="body2"
              color="text.secondary"
              noWrap
              sx={{ mt: 0.5 }}
            >
              {title}
            </Typography>
          )}

          {metadata && (
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ display: "block", mt: 0.25 }}
            >
              {metadata}
            </Typography>
          )}
        </Box>

        {rightSlot && (
          <Typography sx={{ fontWeight: 700, whiteSpace: "nowrap" }}>
            {rightSlot}
          </Typography>
        )}
      </CardContent>
    </Card>
  );
}
