import { Typography } from "@mui/material";

import { describeResultCount } from "../../../features/common/searchPresentation.mjs";

/**
 * "24 AWARDS FOUND" above the result list. The overline variant
 * upper-cases it; the underlying string stays sentence-case so assistive
 * technology reads it normally.
 */
export function ResultCount({
  total,
  singular,
  plural,
}: {
  total: number;
  singular: string;
  plural?: string;
}) {
  return (
    <Typography
      variant="overline"
      color="text.secondary"
      sx={{ display: "block", mb: 1.5 }}
    >
      {describeResultCount({ total, singular, plural })}
    </Typography>
  );
}
