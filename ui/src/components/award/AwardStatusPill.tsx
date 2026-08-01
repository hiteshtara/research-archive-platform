import { Chip } from "@mui/material";

// Mirrors the approved mockup's .status-pill / .status-pill.closed /
// .status-pill.pending treatment - status text is free-form archived
// data (status_description), so this matches on substrings rather than
// a fixed enum.
function variantFor(status: string | null): "success" | "default" | "warning" {
  const normalized = (status ?? "").toLowerCase();

  if (normalized.includes("closed")) {
    return "default";
  }

  if (normalized.includes("pending")) {
    return "warning";
  }

  return "success";
}

export function AwardStatusPill({ status }: { status: string | null }) {
  const variant = variantFor(status);

  return (
    <Chip
      label={status ?? "Unknown"}
      size="small"
      sx={{
        fontWeight: 600,
        fontSize: 11,
        ...(variant === "success" && {
          backgroundColor: "#e7f6ee",
          color: "#1a7f4e",
        }),
        ...(variant === "warning" && {
          backgroundColor: "#fdf0da",
          color: "#8a5a06",
        }),
        ...(variant === "default" && {
          backgroundColor: "action.hover",
          color: "text.secondary",
        }),
      }}
    />
  );
}
