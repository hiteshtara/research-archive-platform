/*
 * The responsive field-card grid used by every workspace summary: one
 * column on phones, two on small screens, three from medium up.
 *
 * EXTRACTED VERBATIM from AwardSummarySection, which is the reference
 * implementation - the values are unchanged.
 *
 * This lives in its own module rather than beside the WorkspaceFieldGrid
 * component because a module that exports both a component and a
 * constant defeats React Fast Refresh.
 */
export const WORKSPACE_FIELD_GRID = {
  display: "grid",
  gridTemplateColumns: {
    xs: "1fr",
    sm: "repeat(2, 1fr)",
    md: "repeat(3, 1fr)",
  },
  gap: 1.75,
} as const;
