export type ExplorerResourceKey =
  | "award"
  | "award-version"
  | "workflow"
  | "unit"
  | "unit-administrators"
  | "award-contacts"
  | "person"
  | "rolodex"
  | "sponsor"
  | "attachments";

export interface ExplorerResourceDefinition {
  key: ExplorerResourceKey;
  label: string;
  identifierField: string;
  identifierLabel: string;
  identifierKind: "string" | "number";
  isList: boolean;
}

export const RESOURCE_DEFINITIONS: ExplorerResourceDefinition[];

export function resourceDefinition(
  resourceKey: string,
): ExplorerResourceDefinition | null;

export function toCsv(
  rows: ReadonlyArray<object> | null | undefined,
): string;

export interface ExplorerCrossLink {
  label: string;
  resource: ExplorerResourceKey;
  identifier: string;
}

export function buildCrossLinks(
  resourceKey: string,
  data: unknown,
): ExplorerCrossLink[];
