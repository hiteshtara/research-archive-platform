import { Box } from "@mui/material";
import type { ReactNode } from "react";

/** The bordered panel the active section renders into. */
export function WorkspaceContent({ children }: { children: ReactNode }) {
  return (
    <Box
      sx={{
        flex: 1,
        minWidth: 0,
        border: "1px solid",
        borderColor: "divider",
        borderRadius: 2,
        p: 3.25,
        minHeight: 360,
      }}
    >
      {children}
    </Box>
  );
}
