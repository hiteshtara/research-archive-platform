import { Alert, Typography } from "@mui/material";

// Two empty-state visual languages currently coexist across modules -
// Subaward's TableSection uses an info Alert, most Award sections use
// plain secondary-colored text. Both are preserved here (via `variant`)
// rather than picking a winner and silently changing either module's
// look - that decision belongs to whichever migration touches Award.
export function EmptyState({
  message,
  variant = "alert",
}: {
  message: string;
  variant?: "alert" | "text";
}) {
  if (variant === "text") {
    return <Typography color="text.secondary">{message}</Typography>;
  }

  return <Alert severity="info">{message}</Alert>;
}
