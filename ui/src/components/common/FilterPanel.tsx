import { ExpandLessOutlined, TuneOutlined } from "@mui/icons-material";
import {
  Badge,
  Box,
  Button,
  Collapse,
  Grid,
  Stack,
  TextField,
  Typography,
} from "@mui/material";

import type {
  FilterErrors,
  FilterFieldDefinition,
} from "../../features/common/filterPresentation.mjs";

/**
 * The collapsible structured-filter panel shared by every archive search
 * page. Negotiations was its first consumer and remains the visual
 * reference; every other search page now uses it unchanged.
 *
 * Field-agnostic on purpose: callers pass their own fields, labels and
 * helper text, so each module keeps its exact business terminology.
 *
 * Accessibility:
 *   - every input has a visible <label> ABOVE it (never a floating label
 *     that truncates, never a placeholder standing in for a label);
 *   - selects are native <select> elements, so keyboard and screen-reader
 *     behaviour is the platform's own;
 *   - helper and error text are wired to the input by MUI's
 *     aria-describedby; every field reserves one helper line, so inputs
 *     in a row stay aligned whether or not a neighbour has helper text;
 *   - the panel is a labelled region that stays mounted while collapsed
 *     (hidden, not removed), so the toggle's aria-controls always
 *     resolves;
 *   - Enter in a text or date field applies, like the search box.
 */

export type FilterField<Key extends string = string> = FilterFieldDefinition<Key>;

export function FilterToggleButton({
  open,
  activeCount,
  onClick,
  panelId = "filter-panel",
}: {
  open: boolean;
  /** The number of APPLIED filters - what the results on screen use. */
  activeCount: number;
  onClick: () => void;
  panelId?: string;
}) {
  const countText = activeCount === 1 ? "1 filter applied" : `${activeCount} filters applied`;
  return (
    <Badge
      badgeContent={activeCount}
      color="primary"
      overlap="rectangular"
      // Full width when the search row stacks on phones, so the count stays
      // attached to the button instead of floating at the row's edge.
      sx={{ width: { xs: "100%", sm: "auto" } }}
    >
      <Button
        variant="outlined"
        onClick={onClick}
        startIcon={open ? <ExpandLessOutlined /> : <TuneOutlined />}
        aria-expanded={open}
        aria-controls={panelId}
        aria-label={activeCount > 0 ? `Filters, ${countText}` : "Filters"}
        sx={{ whiteSpace: "nowrap", minHeight: 40, width: { xs: "100%", sm: "auto" } }}
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
  errors = {},
  applyDisabled = false,
  hasUnappliedChanges = false,
  applyLabel = "Apply Filters",
  panelId = "filter-panel",
  title = "Filters",
}: {
  open: boolean;
  fields: readonly FilterField<Key>[];
  values: Record<Key, string>;
  onChange: (key: Key, value: string) => void;
  onApply: () => void;
  onClearAll: () => void;
  errors?: FilterErrors<Key>;
  applyDisabled?: boolean;
  hasUnappliedChanges?: boolean;
  applyLabel?: string;
  panelId?: string;
  title?: string;
}) {
  const headingId = `${panelId}-heading`;

  return (
    <Collapse in={open}>
      <Box
        id={panelId}
        role="region"
        aria-labelledby={headingId}
        sx={{
          mt: 2,
          p: { xs: 2, sm: 2.5 },
          borderRadius: 1,
          border: 1,
          borderColor: "divider",
          bgcolor: "action.hover",
          textAlign: "left",
        }}
      >
        <Typography
          id={headingId}
          component="h2"
          variant="subtitle2"
          sx={{ fontWeight: 700, mb: 1.5 }}
        >
          {title}
        </Typography>

        <Grid container columnSpacing={2} rowSpacing={2}>
          {fields.map((field) => {
            const inputId = `${panelId}-${field.key}`;
            const error = errors[field.key];
            const value = values[field.key] ?? field.defaultValue ?? "";

            return (
              // Three across on desktop, two on tablet, one on phone - the
              // panel never overflows horizontally at any width.
              <Grid
                key={field.key}
                size={{ xs: 12, sm: 6, md: 4 }}
                sx={{ display: "flex", flexDirection: "column" }}
              >
                <Typography
                  component="label"
                  htmlFor={inputId}
                  variant="body2"
                  sx={{
                    // Grows to the tallest label in its row and bottom-aligns
                    // its text, so inputs line up even when a long label wraps.
                    display: "flex",
                    alignItems: "flex-end",
                    flexGrow: 1,
                    fontWeight: 600,
                    mb: 0.5,
                    color: error ? "error.main" : "text.primary",
                    overflowWrap: "break-word",
                  }}
                >
                  {field.label}
                </Typography>

                {field.type === "select" ? (
                  <TextField
                    select
                    fullWidth
                    size="small"
                    id={inputId}
                    value={value}
                    onChange={(event) => onChange(field.key, event.target.value)}
                    error={Boolean(error)}
                    helperText={error ?? field.helperText ?? " "}
                    slotProps={{ select: { native: true } }}
                    sx={{ "& .MuiInputBase-root": { bgcolor: "background.paper" } }}
                  >
                    {(field.options ?? []).map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </TextField>
                ) : (
                  <TextField
                    fullWidth
                    size="small"
                    id={inputId}
                    type={field.type === "date" ? "date" : "text"}
                    value={value}
                    onChange={(event) => onChange(field.key, event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter") {
                        event.preventDefault();
                        onApply();
                      }
                    }}
                    error={Boolean(error)}
                    helperText={error ?? field.helperText ?? " "}
                    sx={{ "& .MuiInputBase-root": { bgcolor: "background.paper" } }}
                  />
                )}
              </Grid>
            );
          })}
        </Grid>

        <Stack
          direction={{ xs: "column-reverse", sm: "row" }}
          spacing={1}
          sx={{
            mt: 2.5,
            alignItems: { xs: "stretch", sm: "center" },
            justifyContent: "space-between",
          }}
        >
          <Typography
            variant="caption"
            color="text.secondary"
            aria-live="polite"
            sx={{ minHeight: "1.5em" }}
          >
            {hasUnappliedChanges ? "You have filter changes that are not applied yet." : ""}
          </Typography>
          <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end" }}>
            <Button onClick={onClearAll}>Clear All</Button>
            <Button variant="contained" onClick={onApply} disabled={applyDisabled}>
              {applyLabel}
            </Button>
          </Stack>
        </Stack>
      </Box>
    </Collapse>
  );
}
