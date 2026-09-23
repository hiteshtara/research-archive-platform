import { Box, Typography } from "@mui/material";
import type { ReactNode } from "react";

/** One titled group inside a workspace section. */
export function WorkspaceSection({
  title,
  children,
}: {
  title: string;
  children: ReactNode;
}) {
  return (
    <Box>
      <Typography
        variant="overline"
        color="text.secondary"
        sx={{ display: "block", mb: 1, letterSpacing: "0.08em" }}
      >
        {title}
      </Typography>
      {children}
    </Box>
  );
}

/** The vertical rhythm between groups. */
export function WorkspaceSectionStack({ children }: { children: ReactNode }) {
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
      {children}
    </Box>
  );
}
