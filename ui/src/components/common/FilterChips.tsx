import { Button, Chip, Stack } from "@mui/material";

/**
 * Removable APPLIED-filter chips plus Clear All.
 *
 * Shared for the same reason as FilterPanel. Each chip's label is
 * supplied by the caller so business terminology stays module-specific.
 * A chip with onDelete is focusable and removable with Backspace/Delete,
 * and carries an explicit "Remove filter ..." name for screen readers.
 */

export interface FilterChip<Key extends string = string> {
  key: Key;
  label: string;
  value: string;
}

export function FilterChips<Key extends string>({
  chips,
  onRemove,
  onClearAll,
}: {
  chips: readonly FilterChip<Key>[];
  onRemove: (key: Key) => void;
  onClearAll: () => void;
}) {
  if (chips.length === 0) {
    return null;
  }

  return (
    <Stack
      direction="row"
      spacing={1}
      role="group"
      aria-label="Applied filters"
      sx={{
        mt: 2,
        flexWrap: "wrap",
        rowGap: 1,
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      {chips.map((chip) => (
        <Chip
          key={chip.key}
          size="small"
          label={chip.label}
          aria-label={`Remove filter ${chip.label}`}
          onDelete={() => onRemove(chip.key)}
          // Wraps instead of truncating, so the applied value stays readable
          // on narrow screens.
          sx={{
            maxWidth: "100%",
            height: "auto",
            py: 0.25,
            "& .MuiChip-label": { whiteSpace: "normal", overflowWrap: "break-word" },
          }}
        />
      ))}
      <Button size="small" onClick={onClearAll}>
        Clear All
      </Button>
    </Stack>
  );
}
