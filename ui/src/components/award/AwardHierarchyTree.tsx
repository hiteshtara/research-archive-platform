import { ExpandLessOutlined, ExpandMoreOutlined } from "@mui/icons-material";
import { Box, IconButton, Stack, Typography } from "@mui/material";
import { useState } from "react";

import type { AwardHierarchyNode } from "../../types/api";

import { AwardStatusPill } from "./AwardStatusPill";
import { formatCurrencyAmount as fmt } from "../../features/award/awardSectionsPresentation.mjs";

interface AwardHierarchyTreeProps {
  root: AwardHierarchyNode;
  selectedAwardNumber: string;
  onSelect: (node: AwardHierarchyNode) => void;
}

// Approved mockup's .tree-node / .tree-wrap / .children-row treatment,
// reproduced with MUI's sx system rather than the mockup's raw CSS -
// same layout, spacing, and connecting-line presentation, fully
// recursive (every level of the real hierarchy renders, not just the
// two levels the mockup's static demo data happened to have).
export function AwardHierarchyTree({
  root,
  selectedAwardNumber,
  onSelect,
}: AwardHierarchyTreeProps) {
  return (
    <Box sx={{ display: "flex", justifyContent: "center", py: 2 }}>
      <TreeBranch
        node={root}
        isRoot
        selectedAwardNumber={selectedAwardNumber}
        onSelect={onSelect}
      />
    </Box>
  );
}

function TreeBranch({
  node,
  isRoot,
  selectedAwardNumber,
  onSelect,
}: {
  node: AwardHierarchyNode;
  isRoot: boolean;
  selectedAwardNumber: string;
  onSelect: (node: AwardHierarchyNode) => void;
}) {
  const [expanded, setExpanded] = useState(true);
  const hasChildren = node.children.length > 0;

  return (
    <Stack sx={{ alignItems: "center" }}>
      <TreeNodeCard
        node={node}
        isRoot={isRoot}
        selected={node.awardNumber === selectedAwardNumber}
        onSelect={() => onSelect(node)}
        expandable={hasChildren}
        expanded={expanded}
        onToggleExpand={() => setExpanded((value) => !value)}
      />

      {hasChildren && expanded && (
        <>
          <Box
            sx={{
              width: "1px",
              height: 32,
              backgroundColor: "divider",
              borderColor: "text.disabled",
              borderLeft: "1px solid",
            }}
          />

          <Box
            sx={{
              display: "inline-flex",
              gap: 5.5,
              position: "relative",
              pt: 4,
              "&::before":
                node.children.length > 1
                  ? {
                      content: '""',
                      position: "absolute",
                      top: 0,
                      left: 105,
                      right: 105,
                      height: "1px",
                      backgroundColor: "text.disabled",
                    }
                  : undefined,
            }}
          >
            {node.children.map((child) => (
              <Box
                key={child.awardNumber}
                sx={{
                  position: "relative",
                  "&::before": {
                    content: '""',
                    position: "absolute",
                    top: -32,
                    left: "50%",
                    width: "1px",
                    height: 32,
                    backgroundColor: "text.disabled",
                  },
                }}
              >
                <TreeBranch
                  node={child}
                  isRoot={false}
                  selectedAwardNumber={selectedAwardNumber}
                  onSelect={onSelect}
                />
              </Box>
            ))}
          </Box>
        </>
      )}
    </Stack>
  );
}

function TreeNodeCard({
  node,
  isRoot,
  selected,
  onSelect,
  expandable,
  expanded,
  onToggleExpand,
}: {
  node: AwardHierarchyNode;
  isRoot: boolean;
  selected: boolean;
  onSelect: () => void;
  expandable: boolean;
  expanded: boolean;
  onToggleExpand: () => void;
}) {
  return (
    <Box
      role="button"
      tabIndex={0}
      aria-pressed={selected}
      aria-label={`Open Award ${node.awardNumber}`}
      onClick={onSelect}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          onSelect();
        }
      }}
      sx={{
        width: 220,
        backgroundColor: "background.paper",
        border: "1.5px solid",
        borderColor: selected || isRoot ? "primary.main" : "divider",
        borderRadius: 2,
        p: 2,
        cursor: "pointer",
        position: "relative",
        boxShadow: selected
          ? "0 2px 4px rgba(20,20,30,0.06), 0 6px 20px rgba(20,20,30,0.08)"
          : "0 1px 2px rgba(20,20,30,0.05), 0 1px 8px rgba(20,20,30,0.04)",
        transition: "border-color .12s ease, box-shadow .12s ease, transform .12s ease",
        "&:hover": {
          borderColor: "primary.main",
          transform: "translateY(-2px)",
        },
        "&:focus-visible": {
          outline: "2px solid",
          outlineColor: "primary.main",
          outlineOffset: 2,
        },
      }}
    >
      {expandable && (
        <IconButton
          size="small"
          onClick={(event) => {
            event.stopPropagation();
            onToggleExpand();
          }}
          aria-label={expanded ? "Collapse children" : "Expand children"}
          sx={{ position: "absolute", top: 4, right: 4 }}
        >
          {expanded ? (
            <ExpandLessOutlined fontSize="small" />
          ) : (
            <ExpandMoreOutlined fontSize="small" />
          )}
        </IconButton>
      )}

      <Typography sx={{ fontWeight: 700, fontSize: 14 }}>
        {node.awardNumber}
      </Typography>

      <Box sx={{ mt: 0.75, mb: 0.75 }}>
        <AwardStatusPill status={node.status} />
        {node.active === false && (
          <Typography
            component="span"
            variant="caption"
            color="text.disabled"
            sx={{ ml: 1 }}
          >
            (inactive link)
          </Typography>
        )}
      </Box>

      <Typography sx={{ fontSize: 15, fontWeight: 700 }}>
        {fmt(node.currentObligatedAmount)}
      </Typography>

      <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.5 }}>
        PI: {node.principalInvestigator ?? "—"}
      </Typography>
    </Box>
  );
}
