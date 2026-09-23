import { Box } from "@mui/material";
import type { ReactNode } from "react";

import { WORKSPACE_FIELD_GRID } from "./workspaceGrid";

/*
 * The responsive field-card grid. The individual cards are the existing
 * shared StatCard - this project already consolidated five independent
 * reimplementations into that one component, so there is deliberately
 * no WorkspaceFieldCard here. Adding one would recreate exactly the
 * drift StatCard was made to end.
 */
export function WorkspaceFieldGrid({ children }: { children: ReactNode }) {
  return <Box sx={WORKSPACE_FIELD_GRID}>{children}</Box>;
}
