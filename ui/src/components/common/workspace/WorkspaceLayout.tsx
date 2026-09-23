import { Box } from "@mui/material";
import type { ReactNode } from "react";

/*
 * The record-workspace shell: a header, a left section navigation, and a
 * bordered content panel.
 *
 * EXTRACTED VERBATIM from AwardDashboardPage, which is the reference
 * implementation. Every sx value here is the Award page's own - nothing
 * was redesigned while extracting, because Award is already deployed and
 * the extraction has to be visually inert.
 *
 * These are presentation primitives, deliberately NOT a schema or
 * configuration engine: each workspace still composes its own sections
 * and decides what to render in them.
 */
export function WorkspaceLayout({ children }: { children: ReactNode }) {
  return (
    <Box
      sx={{
        display: "flex",
        flexDirection: { xs: "column", md: "row" },
        gap: 3,
        alignItems: "flex-start",
      }}
    >
      {children}
    </Box>
  );
}
