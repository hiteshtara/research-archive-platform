import { Box, Stack, Typography } from "@mui/material";
import type { ReactNode } from "react";

/*
 * The record-workspace header: identifier, title, a monospace metadata
 * line, optional badges, and a right-hand actions slot.
 *
 * EXTRACTED VERBATIM from AwardDashboardPage. The meta line is a slot
 * rather than a formatted string because each domain's identifiers
 * differ (Award has award_id/sequence/document, Negotiation has
 * negotiation_id/document) and inventing a shared format would either
 * lose information or fabricate it.
 */
export function WorkspaceHeader({
  identifier,
  title,
  meta,
  badges,
  actions,
}: {
  identifier: ReactNode;
  title: ReactNode;
  meta?: ReactNode;
  badges?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <Box
      sx={{
        display: "flex",
        justifyContent: "space-between",
        alignItems: "flex-start",
        pb: 2.25,
        borderBottom: "1px solid",
        borderColor: "divider",
      }}
    >
      <Box>
        <Typography sx={{ fontSize: 20, fontWeight: 700 }}>
          {identifier}
        </Typography>

        <Typography
          variant="body2"
          color="text.secondary"
          sx={{ mt: 0.5, maxWidth: 640 }}
        >
          {title}
        </Typography>

        {meta && (
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ display: "block", mt: 0.75, fontFamily: "monospace" }}
          >
            {meta}
          </Typography>
        )}

        {badges}
      </Box>

      <Stack spacing={1} sx={{ alignItems: "flex-end" }}>
        {actions}
      </Stack>
    </Box>
  );
}
