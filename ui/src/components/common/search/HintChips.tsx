import { Chip, Stack } from "@mui/material";

/**
 * The searchable-dimension hints under the search box ("Award Number",
 * "PI", "Sponsor"...). Static labels that tell a user what the free-text
 * box actually covers - deliberately not interactive filters, which are
 * FilterPanel/FilterChips' job.
 *
 * Optionally clickable so a module can let a hint prefill the box; the
 * Awards page does not, and passing no handler keeps the existing
 * non-interactive rendering byte-for-byte.
 */
export function HintChips({
  hints,
  onHintClick,
}: {
  hints: readonly string[];
  onHintClick?: (hint: string) => void;
}) {
  if (hints.length === 0) {
    return null;
  }

  return (
    <Stack
      direction="row"
      spacing={1}
      sx={{ flexWrap: "wrap", justifyContent: "center", mt: 2 }}
    >
      {hints.map((hint) => (
        <Chip
          key={hint}
          label={hint}
          size="small"
          variant="outlined"
          {...(onHintClick
            ? { onClick: () => onHintClick(hint), clickable: true }
            : {})}
        />
      ))}
    </Stack>
  );
}
