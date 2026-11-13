export interface Capabilities {
  administrator: boolean;
  enforcementEnabled: boolean;
  administrationAvailable: boolean;
  verified: boolean;
}

export interface SecurityAdminNavigationItem {
  key: string;
  label: string;
  path: string;
}

export interface SystemStatusRow {
  key: string;
  label: string;
  value: string;
  tone: "active" | "inactive" | "neutral";
}

export interface UnavailableNotice {
  title: string;
  reason: string;
  clarification: string;
  enforcementEnabled: boolean;
  showsControls: boolean;
}

export const securityAdminNavigationItems: SecurityAdminNavigationItem[];
export const ARCHIVE_MODE: string;
export const SECURITY_ADMIN_MODE: string;

export function readCapabilities(
  raw: unknown,
  options?: { isLoading?: boolean; isError?: boolean },
): Capabilities;
export function showsModeToggle(capabilities: Capabilities): boolean;
export function administrationIsUsable(capabilities: Capabilities): boolean;
export function adminRouteState(capabilities: Capabilities): "denied" | "unavailable" | "available";
export function mayCallAdminApi(capabilities: Capabilities): boolean;
export function rendersInteractiveControls(capabilities: Capabilities): boolean;
export function unavailableNotice(capabilities: Capabilities): UnavailableNotice;
export function systemStatusRows(
  capabilities: Capabilities,
  options?: { activeGrants?: number },
): SystemStatusRow[];
