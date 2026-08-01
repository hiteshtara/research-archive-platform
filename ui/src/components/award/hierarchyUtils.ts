import type { AwardHierarchyNode } from "../../types/api";

// Flattens the recursive hierarchy tree into a lookup by award_number -
// used to resolve the awardId behind a breadcrumb entry (selectedAwardPath
// is just a list of award numbers) without a second network request.
export function flattenHierarchyNodes(
  root: AwardHierarchyNode,
): Map<string, AwardHierarchyNode> {
  const byAwardNumber = new Map<string, AwardHierarchyNode>();

  function walk(node: AwardHierarchyNode) {
    byAwardNumber.set(node.awardNumber, node);
    node.children.forEach(walk);
  }

  walk(root);

  return byAwardNumber;
}
