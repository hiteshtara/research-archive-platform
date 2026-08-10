import { Box, CircularProgress, Skeleton, Stack } from "@mui/material";

import { resolveSkeletonRowHeights } from "../../features/common/loadingStatePresentation.mjs";

const SPINNER_PADDING = {
  "full-section": 8,
  spinner: 6,
  compact: 4,
} as const;

type SpinnerMode = keyof typeof SPINNER_PADDING;

// The one loading widget every workspace page/section should use.
// "full-section" (the default) is the original whole-page/whole-tab
// spinner - previously reimplemented identically in
// SubawardWorkspacePage.tsx and NegotiationWorkspacePage.tsx.
// "spinner" and "compact" preserve the two other real, already-shipped
// CircularProgress paddings (results panels, dialogs) rather than
// forcing every call site to the same py. "skeleton" replaces a
// hand-rolled Skeleton/Stack-of-Skeletons block; pass `height` for one
// row, `count` for several identical rows, or `heights` for rows of
// different sizes (all three preserve exact pre-migration pixel sizes).
export function LoadingState({
  mode = "full-section",
  height = 220,
  count = 1,
  heights,
}: {
  mode?: SpinnerMode | "skeleton";
  height?: number;
  count?: number;
  heights?: number[];
}) {
  if (mode === "skeleton") {
    const rowHeights = resolveSkeletonRowHeights({ height, count, heights });
    if (rowHeights.length <= 1) {
      return <Skeleton variant="rounded" height={rowHeights[0] ?? height} />;
    }
    return (
      <Stack spacing={1.5}>
        {rowHeights.map((rowHeight, index) => (
          <Skeleton key={index} variant="rounded" height={rowHeight} />
        ))}
      </Stack>
    );
  }

  return (
    <Box
      sx={{ display: "grid", placeItems: "center", py: SPINNER_PADDING[mode] }}
    >
      <CircularProgress />
    </Box>
  );
}
