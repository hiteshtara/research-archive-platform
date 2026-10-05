import { SearchOutlined } from "@mui/icons-material";
import { InputAdornment, TextField } from "@mui/material";

import {
  canSubmitSearchText,
  isSearchTextTooLong,
  searchLengthHelperText,
} from "../../../features/common/searchPresentation.mjs";

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
 *
 * LENGTH (QA TC-018): the limit lives here rather than on each page, so
 * every search box counts and refuses the same way. Note what this does
 * NOT do - there is no `maxLength`. That attribute truncates a paste
 * silently, which would let someone paste a long reference, have the
 * box quietly keep the first 200 characters, and get a confident answer
 * to a search they never made. Over-long text is kept and shown,
 * counted against the limit, and refused on submit with the number of
 * characters to remove.
 *
 * The API enforces the same limit independently, so a search typed
 * straight into the URL is still refused - this is the half that
 * explains it before the request is made.
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
  const tooLong = isSearchTextTooLong(value);
  const helperText = searchLengthHelperText(value);

  return (
    <TextField
      fullWidth
      autoFocus={autoFocus}
      placeholder={placeholder}
      value={value}
      error={tooLong}
      helperText={helperText ?? undefined}
      onChange={(event) => onChange(event.target.value)}
      onKeyDown={(event) => {
        if (event.key === "Enter" && canSubmitSearchText(value)) {
          onSubmit(value);
        }
      }}
      slotProps={{
        input: {
          "aria-label": ariaLabel ?? placeholder ?? "Search",
          // Announced with the box, so someone using a screen reader
          // hears the count and the refusal rather than only seeing it.
          "aria-invalid": tooLong,
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
