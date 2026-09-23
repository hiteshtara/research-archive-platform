import { Box, Typography } from "@mui/material";
import type { ReactNode } from "react";

import { ErrorState } from "../ErrorState";
import { LoadingState } from "../LoadingState";

/**
 * One loading / error / initial treatment for every archive search page,
 * so no module invents its own spinner page.
 *
 * "initial" renders nothing at all. That is deliberate and is why
 * primary search pages no longer preload results: before a search there
 * is nothing to show but the hero, and a module must not put 10,775 rows
 * on screen merely because the data exists.
 *
 * "empty" falls through to children rather than replacing them, because
 * a zero-result page still shows its result count above the "no matches"
 * line. Modules render that line with the shared EmptyState component,
 * which is what keeps the empty treatment uniform.
 */
export function SearchStates({
  state,
  errorMessage,
  children,
}: {
  state: "initial" | "loading" | "error" | "empty" | "results";
  errorMessage: string;
  children?: ReactNode;
}) {
  if (state === "initial") {
    return null;
  }

  if (state === "loading") {
    return <LoadingState mode="spinner" />;
  }

  if (state === "error") {
    return <ErrorState message={errorMessage} />;
  }

  return <>{children}</>;
}

/**
 * The hint shown before anyone has searched, for modules that want one.
 * Kept visually quiet so the page stays clean.
 */
export function InitialSearchHint({ message }: { message: string }) {
  return (
    <Box sx={{ textAlign: "center" }}>
      <Typography variant="body2" color="text.secondary">
        {message}
      </Typography>
    </Box>
  );
}
