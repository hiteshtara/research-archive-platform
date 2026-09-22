import { Chip } from "@mui/material";

import { splitStatusCode } from "../../features/common/searchPresentation.mjs";
import { resolveStatusVariant } from "../../features/common/statusPresentation.mjs";

export type StatusDomain = "award" | "proposal" | "negotiation" | "subaward";

// Replaces the old Award-only AwardStatusPill (moved here unchanged for
// Award/Proposal, see statusPresentation.mjs) plus every module's own
// plain `<Chip color="primary">` status display. success/warning/
// default match the original component's exact colors; error and
// neutral are new categories needed to represent Negotiation's
// "Abandoned" and Subaward's "Cancelled" (and genuinely unclassifiable
// statuses) honestly, rather than folding them into "success" the way
// the old keyword rule silently did for Award's own "Cancelled".
export function StatusPill({
  status,
  domain,
}: {
  status: string | null;
  domain: StatusDomain;
}) {
  // The colour rule keeps reading the FULL archived value, because
  // Subaward's variant map is keyed on it ("04. PI/DA",
  // "09. Temporarily Cancelled"). Only the visible label drops the
  // leading ordinal, with the original kept on the title attribute as
  // secondary metadata - BU reads the words, not the number.
  //
  // splitStatusCode is a no-op for Award, Proposal and Negotiation
  // statuses, which carry no ordinal, so this cannot change how they
  // render.
  const variant = resolveStatusVariant(domain, status);
  const { code, label } = splitStatusCode(status);

  return (
    <Chip
      title={code ? `${code} · ${label}` : undefined}
      label={label ?? "Unknown"}
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
        ...(variant === "error" && {
          backgroundColor: "#fdecea",
          color: "#b3261e",
        }),
        ...(variant === "default" && {
          backgroundColor: "action.hover",
          color: "text.secondary",
        }),
        ...(variant === "neutral" && {
          backgroundColor: "action.hover",
          color: "text.secondary",
        }),
      }}
    />
  );
}
