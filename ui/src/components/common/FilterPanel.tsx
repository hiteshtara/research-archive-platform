import { ExpandLessOutlined, TuneOutlined } from "@mui/icons-material";
import {
  Badge,
  Box,
  Button,
  Collapse,
  Grid,
  Stack,
  TextField,
} from "@mui/material";

/**
 * A collapsible structured-filter panel.
 *
 * Shared on purpose. Awards is the reference implementation for
 * list/search presentation in this archive, but it has no filter panel of
 * its own yet - so rather than copy JSX into one module, this lives in
 * components/common from the start and Negotiations is simply its first
 * consumer. Proposals, Subawards and Awards can adopt it unchanged.
 *
 * It is deliberately field-agnostic: callers pass their own fields and
 * their own labels, so each module keeps its exact business terminology.
 */

export interface FilterField<Key extends string = string> {
  key: Key;
  label: string;
  type?: "date";
}

export function FilterToggleButton({
  open,
  activeCount,
  onClick,
}: {
  open: boolean;
  activeCount: number;
  onClick: () => void;
}) {
  return (
    <Badge badgeContent={activeCount} color="primary">
      <Button
        variant="outlined"
        onClick={onClick}
        startIcon={open ? <ExpandLessOutlined /> : <TuneOutlined />}
        aria-expanded={open}
        aria-controls="filter-panel"
        sx={{ whiteSpace: "nowrap" }}
      >
        Filters
      </Button>
    </Badge>
  );
}

export function FilterPanel<Key extends string>({
  open,
  fields,
  values,
  onChange,
  onApply,
  onClearAll,
  applyLabel = "Apply filters",
}: {
  open: boolean;
  fields: readonly FilterField<Key>[];
  values: Record<Key, string>;
  onChange: (key: Key, value: string) => void;
  onApply: () => void;
  onClearAll: () => void;
  applyLabel?: string;
}) {
  return (
    <Collapse in={open} unmountOnExit>
      <Box
        id="filter-panel"
        sx={{
          mt: 2,
          p: 2,
          borderRadius: 1,
          border: 1,
          borderColor: "divider",
          bgcolor: "action.hover",
        }}
      >
        <Grid container spacing={2}>
          {fields.map((field) => (
            // Three across on desktop, two on tablet, one on phone - so
            // the panel never overflows horizontally at any width.
            <Grid key={field.key} size={{ xs: 12, sm: 6, md: 4 }}>
              <TextField
                fullWidth
                size="small"
                type={field.type === "date" ? "date" : "text"}
                label={field.label}
                value={values[field.key] ?? ""}
                onChange={(event) => onChange(field.key, event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    onApply();
                  }
                }}
                slotProps={
                  field.type === "date"
                    ? { inputLabel: { shrink: true } }
                    : undefined
                }
              />
            </Grid>
          ))}
        </Grid>

        <Stack
          direction="row"
          spacing={1}
          sx={{ mt: 2, justifyContent: "flex-end" }}
        >
          <Button onClick={onClearAll}>Clear All</Button>
          <Button variant="contained" onClick={onApply}>
            {applyLabel}
          </Button>
        </Stack>
      </Box>
    </Collapse>
  );
}
