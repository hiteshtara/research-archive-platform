import { Button, Chip, Stack } from "@mui/material";

/**
 * Removable active-filter chips plus Clear All.
 *
 * Shared for the same reason as FilterPanel: this pattern did not exist
 * in Awards, so it is introduced in components/common rather than inside
 * one module, and any module can adopt it. Each chip's label is supplied
 * by the caller so business terminology stays module-specific.
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
      sx={{ mt: 2, flexWrap: "wrap", rowGap: 1, alignItems: "center" }}
    >
      {chips.map((chip) => (
        <Chip
          key={chip.key}
          size="small"
          label={chip.label}
          onDelete={() => onRemove(chip.key)}
        />
      ))}
      <Button size="small" onClick={onClearAll}>
        Clear All
      </Button>
    </Stack>
  );
}
