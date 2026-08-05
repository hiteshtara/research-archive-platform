// Presentation helpers for Institutional Proposal Custom Data
// (archive.proposal_custom_data LEFT JOINed to the shared
// archive.custom_attribute lookup). Kept framework-free so it can be
// unit-tested with plain node:test, matching this repo's existing
// features/*/*.mjs convention.

const UNGROUPED_LABEL = "Other";

// custom_attribute_id has no foreign key (see database migration
// V064) - a row can arrive with label and name both null when Oracle
// has an attribute this archive hasn't loaded into
// archive.custom_attribute yet. Never render the bare numeric ID as
// the only visible text - fall back to name, then a synthetic label
// that still names the attribute.
export function resolveCustomDataLabel(row) {
  if (row.label && row.label.trim() !== "") {
    return row.label;
  }
  if (row.name && row.name.trim() !== "") {
    return row.name;
  }
  return `Custom Field ${row.customAttributeId ?? "?"}`;
}

// Groups rows by their proven groupName, preserving each group's
// first-seen order and each row's order within its group. Rows with no
// groupName (null, or a lookup miss) collapse into a single "Other"
// group placed last, rather than one throwaway group per null row.
export function groupCustomData(rows) {
  const groups = new Map();

  for (const row of rows) {
    const key = row.groupName && row.groupName.trim() !== ""
      ? row.groupName
      : UNGROUPED_LABEL;
    if (!groups.has(key)) {
      groups.set(key, []);
    }
    groups.get(key).push(row);
  }

  const ordered = [...groups.entries()].filter(
    ([groupName]) => groupName !== UNGROUPED_LABEL,
  );
  if (groups.has(UNGROUPED_LABEL)) {
    ordered.push([UNGROUPED_LABEL, groups.get(UNGROUPED_LABEL)]);
  }

  return ordered.map(([groupName, groupRows]) => ({ groupName, rows: groupRows }));
}

// Case-insensitive match against the resolved label, the raw name, and
// the value - so a search box can find a field by any of the three,
// including proposals where most rows lack a resolved label.
export function matchesCustomDataQuery(row, query) {
  const normalizedQuery = query.trim().toLowerCase();
  if (normalizedQuery === "") {
    return true;
  }

  const haystack = [
    resolveCustomDataLabel(row),
    row.name ?? "",
    row.value ?? "",
  ]
    .join(" ")
    .toLowerCase();

  return haystack.includes(normalizedQuery);
}
