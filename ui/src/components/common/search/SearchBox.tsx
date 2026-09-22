import { SearchOutlined } from "@mui/icons-material";
import { InputAdornment, TextField } from "@mui/material";

/**
 * The one search input used by every archive search page - same height,
 * radius, icon, typography, width and Enter behaviour everywhere.
 *
 * Styling is the Awards search page's existing input, unchanged
 * (fontSize 16, borderRadius 2.5, leading search icon), because Awards
 * is the visual reference for this migration.
 *
 * Enter submits. The caller owns the value so it can keep search state
 * in the URL rather than in local-only component state.
 */
export function SearchBox({
  value,
  onChange,
  onSubmit,
  placeholder,
  autoFocus = true,
  ariaLabel,
}: {
  value: string;
  onChange: (value: string) => void;
  onSubmit: (value: string) => void;
  placeholder?: string;
  autoFocus?: boolean;
  ariaLabel?: string;
}) {
  return (
    <TextField
      fullWidth
      autoFocus={autoFocus}
      placeholder={placeholder}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      onKeyDown={(event) => {
        if (event.key === "Enter") {
          onSubmit(value);
        }
      }}
      slotProps={{
        input: {
          "aria-label": ariaLabel ?? placeholder ?? "Search",
          startAdornment: (
            <InputAdornment position="start">
              <SearchOutlined />
            </InputAdornment>
          ),
        },
      }}
      sx={{
        "& .MuiOutlinedInput-root": {
          fontSize: 16,
          borderRadius: 2.5,
        },
      }}
    />
  );
}
