import { NavigateNextOutlined } from "@mui/icons-material";
import { Box, Link, Typography } from "@mui/material";

import type { AwardHierarchyNode } from "../../types/api";

interface AwardBreadcrumbProps {
  path: string[];
  nodesByAwardNumber: Map<string, AwardHierarchyNode>;
  onNavigate: (node: AwardHierarchyNode) => void;
}

// Approved mockup's header breadcrumb - built from the hierarchy's own
// selectedAwardPath (root -> ... -> current), letting a user jump
// between related Awards without returning to search.
export function AwardBreadcrumb({
  path,
  nodesByAwardNumber,
  onNavigate,
}: AwardBreadcrumbProps) {
  if (path.length === 0) {
    return null;
  }

  return (
    <Box
      component="nav"
      aria-label="Award hierarchy breadcrumb"
      sx={{
        display: "flex",
        alignItems: "center",
        flexWrap: "wrap",
        gap: 0.5,
        fontSize: 13,
      }}
    >
      {path.map((awardNumber, index) => {
        const isLast = index === path.length - 1;
        const node = nodesByAwardNumber.get(awardNumber);

        return (
          <Box
            key={awardNumber}
            sx={{ display: "flex", alignItems: "center", gap: 0.5 }}
          >
            {isLast || !node ? (
              <Typography
                variant="body2"
                sx={{ fontWeight: 600 }}
                aria-current={isLast ? "page" : undefined}
              >
                {awardNumber}
              </Typography>
            ) : (
              <Link
                component="button"
                variant="body2"
                underline="hover"
                color="text.secondary"
                onClick={() => onNavigate(node)}
                sx={{ font: "inherit" }}
              >
                {awardNumber}
              </Link>
            )}

            {!isLast && (
              <NavigateNextOutlined
                fontSize="small"
                sx={{ color: "text.disabled" }}
              />
            )}
          </Box>
        );
      })}
    </Box>
  );
}
