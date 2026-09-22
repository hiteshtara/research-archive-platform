import { Box, Stack, Typography } from "@mui/material";
import type { ReactNode } from "react";

/**
 * The single structure every archive search page uses.
 *
 *   title
 *   subtitle
 *   search box
 *   hint chips / filters
 *   ------------------------
 *   result count
 *   result cards
 *   pagination
 *
 * A user who learns one archive search page should immediately
 * understand every other one, so the widths, margins and spacing below
 * are defined once here rather than per module. The values are the
 * Awards search page's existing ones, unchanged, because Awards is the
 * visual reference: hero column 640px, results column 680px, hero top
 * margin 4, outer stack spacing 4.
 */
export function SearchPageLayout({
  title,
  subtitle,
  search,
  belowSearch,
  children,
}: {
  title: string;
  subtitle?: ReactNode;
  search: ReactNode;
  belowSearch?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <Stack spacing={4} sx={{ alignItems: "center" }}>
      <Box sx={{ maxWidth: 640, width: "100%", textAlign: "center", mt: 4 }}>
        <Typography variant="h4" sx={{ fontWeight: 700, mb: 1 }}>
          {title}
        </Typography>

        {subtitle && (
          <Typography color="text.secondary" sx={{ mb: 4 }}>
            {subtitle}
          </Typography>
        )}

        {search}

        {belowSearch}
      </Box>

      {children && (
        <Box sx={{ maxWidth: 680, width: "100%" }}>{children}</Box>
      )}
    </Stack>
  );
}
