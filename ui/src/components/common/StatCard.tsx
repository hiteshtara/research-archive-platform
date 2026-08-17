import { Box, Typography } from "@mui/material";

// Previously reimplemented independently (same shape, minor drift) in
// AwardSummarySection, AwardBudgetSection, AwardTimeAndMoneySection,
// ProposalSponsorProgramSection, and ProposalSummarySection. Two real
// visual languages existed pre-migration - "filled" (the original
// stat-grid tile, monospace uppercase label on a tinted background)
// and "outlined" (Budget/Time and Money's bordered tile, a regular
// caption label, with an optional accent border and trailing caption
// line) - both preserved via `variant` rather than picking a winner.
export function StatCard({
  label,
  value,
  variant = "filled",
  caption,
  accent = false,
  minWidth,
}: {
  label: string;
  value: string;
  variant?: "filled" | "outlined";
  caption?: string;
  accent?: boolean;
  minWidth?: number;
}) {
  if (variant === "outlined") {
    return (
      <Box
        sx={{
          border: "1px solid",
          borderColor: accent ? "primary.main" : "divider",
          borderRadius: 2,
          p: 2,
          minWidth,
        }}
      >
        <Typography variant="caption" color="text.secondary">
          {label}
        </Typography>
        <Typography sx={{ fontWeight: 700, fontSize: 18 }}>{value}</Typography>
        {caption && (
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ display: "block", mt: 0.5 }}
          >
            {caption}
          </Typography>
        )}
      </Box>
    );
  }

  return (
    <Box
      sx={{
        backgroundColor: "action.hover",
        borderRadius: 1.5,
        p: 2,
      }}
    >
      <Box
        sx={{
          fontSize: 10.5,
          color: "text.secondary",
          textTransform: "uppercase",
          letterSpacing: "0.04em",
          fontFamily: "monospace",
        }}
      >
        {label}
      </Box>
      <Box sx={{ fontSize: 16, fontWeight: 700, mt: 0.5 }}>{value}</Box>
    </Box>
  );
}
