import { Box, Card, CardContent, Stack, Typography } from "@mui/material";
import type { CardProps, SxProps, Theme } from "@mui/material";
import type { ReactNode } from "react";

/**
 * The shared visual shell every archive result uses.
 *
 * It owns the card treatment, spacing, typography, metadata layout and
 * responsive behaviour - and nothing else. It deliberately does not own
 * the interaction, because archive results do not all do the same thing
 * when you act on them:
 *
 *   ResultCard        = this surface + a RouterLink  (open a record)
 *   file results      = this surface + a Download action
 *
 * Keeping the interaction out is what lets the Archived File Finder
 * look like the rest of the application without being given a fake href
 * it would be wrong to Cmd-click or copy.
 *
 * The hierarchy is fixed so every module reads the same way:
 *
 *   LINE 1  primary identifier · secondary identifier · status
 *   LINE 2  human-readable title
 *   LINE 3  people / sponsor / organization / unit
 *   RIGHT   one module-specific summary
 *
 * The DATA differs per module. The hierarchy does not.
 */
export function ResultSurface({
  identifier,
  secondaryIdentifier,
  status,
  title,
  metadata,
  rightSlot,
  emphasized = false,
  interactive = false,
  banner,
  cardProps,
  sx,
}: {
  identifier: ReactNode;
  secondaryIdentifier?: ReactNode;
  status?: ReactNode;
  title?: ReactNode;
  metadata?: ReactNode;
  rightSlot?: ReactNode;
  emphasized?: boolean;
  /**
   * Whether the surface itself responds to being acted on. A
   * navigational card sets this; a surface whose only action is a
   * button inside it does not, so the whole card does not look
   * clickable when it is not.
   */
  interactive?: boolean;
  banner?: ReactNode;
  cardProps?: Partial<CardProps> & Record<string, unknown>;
  sx?: SxProps<Theme>;
}) {
  return (
    <Card
      {...cardProps}
      variant="outlined"
      sx={{
        display: "block",
        textDecoration: "none",
        color: "inherit",
        transition: "border-color .12s ease",
        ...(interactive
          ? {}
          : { cursor: "default", "&:hover": { borderColor: "divider" } }),
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

        {/*
          Plain text gets the shared emphasis treatment (Award's
          obligated amount). A module that needs its own styling - a
          date range, a version chip, a Download button - passes an
          element and it is rendered as-is, because wrapping an element
          in this Typography would nest a <p> inside a <p>.
        */}
        {typeof rightSlot === "string" || typeof rightSlot === "number" ? (
          <Typography sx={{ fontWeight: 700, whiteSpace: "nowrap" }}>
            {rightSlot}
          </Typography>
        ) : (
          rightSlot && <Box sx={{ flexShrink: 0 }}>{rightSlot}</Box>
        )}
      </CardContent>
    </Card>
  );
}
