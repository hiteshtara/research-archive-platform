// Pure presentation-helper functions for ExplorerPage - kept
// dependency-free, plain JS, and node:test-able the same way
// ../award/awardSectionsPresentation.mjs is, since this project has no
// component-render test setup.
//
// RESOURCE_DEFINITIONS is the single source of truth for the resource
// dropdown: which identifier field each of the 10
// /api/v1/explorer/* resources takes, its label, and whether the
// resource's own top-level response is a list (renders as a table with
// a CSV download) or a single object (renders as a summary card, though
// several single-object resources still nest their own sub-lists -
// Unit's administrators, Award Contacts' four sections - each still
// individually CSV-downloadable via the same toCsv helper).

export const RESOURCE_DEFINITIONS = [
  {
    key: "award",
    label: "Award",
    identifierField: "awardNumber",
    identifierLabel: "Award Number",
    identifierKind: "string",
    isList: false,
  },
  {
    key: "award-version",
    label: "Award Version",
    identifierField: "awardId",
    identifierLabel: "Award ID",
    identifierKind: "number",
    isList: false,
  },
  {
    key: "workflow",
    label: "Workflow",
    identifierField: "documentNumber",
    identifierLabel: "Workflow Document Number",
    identifierKind: "string",
    isList: false,
  },
  {
    key: "unit",
    label: "Unit",
    identifierField: "unitNumber",
    identifierLabel: "Unit Number",
    identifierKind: "string",
    isList: false,
  },
  {
    key: "unit-administrators",
    label: "Unit Administrators",
    identifierField: "unitNumber",
    identifierLabel: "Unit Number",
    identifierKind: "string",
    isList: true,
  },
  {
    key: "award-contacts",
    label: "Award Contacts",
    identifierField: "awardId",
    identifierLabel: "Award ID",
    identifierKind: "number",
    isList: false,
  },
  {
    key: "person",
    label: "Person",
    identifierField: "personId",
    identifierLabel: "Person ID",
    identifierKind: "string",
    isList: false,
  },
  {
    key: "rolodex",
    label: "Rolodex",
    identifierField: "rolodexId",
    identifierLabel: "Rolodex ID",
    identifierKind: "number",
    isList: false,
  },
  {
    key: "sponsor",
    label: "Sponsor",
    identifierField: "sponsorCode",
    identifierLabel: "Sponsor Code",
    identifierKind: "string",
    isList: true,
  },
  {
    key: "attachments",
    label: "Attachments",
    identifierField: "awardId",
    identifierLabel: "Award ID",
    identifierKind: "number",
    isList: true,
  },
];

export function resourceDefinition(resourceKey) {
  return RESOURCE_DEFINITIONS.find((entry) => entry.key === resourceKey) ?? null;
}

// Renders an array of flat objects as CSV text (RFC 4180-ish: quotes a
// value only when it contains a comma/quote/newline, doubling embedded
// quotes). The header row is the union of every row's own keys, in
// first-seen order, so a short/sparse row never truncates the columns
// a later, fuller row would have contributed.
export function toCsv(rows) {
  if (!Array.isArray(rows) || rows.length === 0) {
    return "";
  }

  const columns = [];
  const seen = new Set();
  for (const row of rows) {
    if (!row || typeof row !== "object") {
      continue;
    }
    for (const key of Object.keys(row)) {
      if (!seen.has(key)) {
        seen.add(key);
        columns.push(key);
      }
    }
  }

  function cell(value) {
    if (value === null || value === undefined) {
      return "";
    }
    const text = String(value);
    if (/["\n,]/.test(text)) {
      return `"${text.replace(/"/g, '""')}"`;
    }
    return text;
  }

  const lines = [columns.map(cell).join(",")];
  for (const row of rows) {
    lines.push(columns.map((column) => cell(row?.[column])).join(","));
  }
  return lines.join("\n");
}

function awardCrossLinks(award) {
  const links = [];
  if (!award) {
    return links;
  }
  if (award.awardId != null) {
    links.push({
      label: "Award Contacts",
      resource: "award-contacts",
      identifier: String(award.awardId),
    });
    links.push({
      label: "Attachments",
      resource: "attachments",
      identifier: String(award.awardId),
    });
  }
  if (award.workflowDocumentNumber) {
    links.push({
      label: `Workflow ${award.workflowDocumentNumber}`,
      resource: "workflow",
      identifier: award.workflowDocumentNumber,
    });
  }
  if (award.leadUnitNumber) {
    links.push({
      label: `Unit ${award.leadUnitNumber}${
        award.leadUnitName ? ` (${award.leadUnitName})` : ""
      }`,
      resource: "unit",
      identifier: award.leadUnitNumber,
    });
  }
  return links;
}

function personLink(row) {
  if (!row?.personId) {
    return null;
  }
  return {
    label: row.fullName ? `Person: ${row.fullName}` : `Person ${row.personId}`,
    resource: "person",
    identifier: row.personId,
  };
}

// Cross-links surfaced for each resource kind, for the required
// navigation: Award->Workflow, Award->Unit, Award->Contacts,
// Unit->Administrators, Person->related contacts (a Unit
// Administrator/Award Contact row links forward to its Person - the
// Explorer has no reverse Person->contacts index to query, since the
// backend never exposes one), Attachment->owning Award.
export function buildCrossLinks(resourceKey, data) {
  if (!data) {
    return [];
  }

  switch (resourceKey) {
    case "award":
    case "award-version":
      return awardCrossLinks(data);

    case "unit": {
      const links = [];
      if (data.unitNumber) {
        links.push({
          label: "Unit Administrators",
          resource: "unit-administrators",
          identifier: data.unitNumber,
        });
      }
      for (const administrator of data.administrators ?? []) {
        const link = personLink(administrator);
        if (link) {
          links.push(link);
        }
      }
      return links;
    }

    case "unit-administrators":
      return (Array.isArray(data) ? data : [])
        .map((row) => personLink(row))
        .filter(Boolean);

    case "award-contacts": {
      const links = [...awardCrossLinks(data.award)];
      if (data.unitDetails?.unitNumber) {
        links.push({
          label: `Unit ${data.unitDetails.unitNumber}`,
          resource: "unit",
          identifier: data.unitDetails.unitNumber,
        });
      }
      for (const row of [
        ...(data.keyPersonnel ?? []),
        ...(data.unitContacts ?? []),
        ...(data.centralAdministrationContacts ?? []),
      ]) {
        const link = personLink(row);
        if (link) {
          links.push(link);
        }
      }
      return links;
    }

    case "sponsor":
      return (Array.isArray(data) ? data : []).flatMap((award) => {
        const links = awardCrossLinks(award);
        return links.map((link) => ({
          ...link,
          label: `${award.awardNumber}: ${link.label}`,
        }));
      });

    case "attachments":
      return (Array.isArray(data) ? data : [])
        .filter((row) => row?.awardNumber)
        .map((row) => ({
          label: `Award ${row.awardNumber}`,
          resource: "award",
          identifier: row.awardNumber,
        }))
        // an attachment list is almost always for one Award - collapse
        // duplicate "same Award" links down to one.
        .filter(
          (link, index, all) =>
            all.findIndex((other) => other.identifier === link.identifier) ===
            index,
        );

    case "person":
    case "rolodex":
    case "workflow":
    default:
      return [];
  }
}
