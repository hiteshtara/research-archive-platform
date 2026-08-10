import { Card, CardContent, Typography } from "@mui/material";
import type { ReactNode } from "react";

// The plain `Card variant="outlined"><CardContent>` wrapper repeated
// throughout workspace pages for a self-contained block of content
// (a totals summary, a single timeline entry, etc.) - not a full
// section with its own loading/error/empty state (that's a workspace
// section component composing this), just the visual shell.
export function SectionCard({
  title,
  children,
}: {
  title?: string;
  children: ReactNode;
}) {
  return (
    <Card variant="outlined">
      <CardContent>
        {title && (
          <Typography variant="h6" sx={{ mb: 1 }}>
            {title}
          </Typography>
        )}
        {children}
      </CardContent>
    </Card>
  );
}
