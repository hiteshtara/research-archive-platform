export type GrantTypeName = "CENTRAL" | "UNIT" | "IO" | "CONTACT_DERIVATION";

export interface GrantLike {
  type: string;
  includeDescendants?: boolean;
  validFrom?: string | null;
  expiresAt?: string | null;
  revokedAt?: string | null;
}

export interface IdentityLike {
  administrator?: boolean;
  suspended?: boolean;
  grants?: GrantLike[];
}

export interface AccessBanner {
  level: "warning" | "danger";
  text: string;
}

export interface GrantForm {
  type: string;
  unitNumber?: string | null;
  includeDescendants?: boolean;
  ioValue?: string | null;
  expiresAt?: string | null;
  reason?: string | null;
}

export const GRANT_TYPES: GrantTypeName[];

export function grantTypeLabel(type: string | null | undefined): string;

export function grantScopeNote(grant: GrantLike | null | undefined): string | null;

export function isGrantActive(
  grant: GrantLike | null | undefined,
  now?: Date | string,
): boolean;

export function accessBanner(identity: IdentityLike | null | undefined): AccessBanner | null;

export function searchSourceNote(source: string | null | undefined): string;

export function historicalStatusCaption(
  actvInd: string | null | undefined,
  extractedAt?: string | null,
): string | null;

export function canRevokeAdministrator(activeAdministratorCount: number): boolean;

export function revokeAdministratorDisabledReason(
  activeAdministratorCount: number,
): string | null;

export function auditActionLabel(action: string | null | undefined): string;

export function validateGrantForm(form: GrantForm | null | undefined): string[];

export function requiresReason(action: string): boolean;

export function formatDate(value: string | null | undefined): string;
