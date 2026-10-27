import { accessToken } from "../auth";

import { ApiRequestError } from "./client";

/**
 * The ARCHIVE_ADMIN API.
 *
 * Kept apart from client.ts because the administrative surface needs verbs
 * (PATCH, PUT, DELETE) and empty 204 responses that the shared request helper
 * does not handle, and because mixing administration into the record client
 * invites a record screen calling it by accident.
 *
 * While record authorization is disabled - the default, and the current state
 * everywhere - every one of these returns 404. That is the server saying the
 * surface does not exist yet, not a bug.
 */

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL;

type Method = "GET" | "POST" | "PATCH" | "PUT" | "DELETE";

async function adminRequest<T>(
  path: string,
  method: Method = "GET",
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  const token = await accessToken();
  if (!token) {
    throw new Error("No Cognito access token is available.");
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    method,
    signal,
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (!response.ok) {
    let correlationId: string | undefined;
    let code: string | undefined;
    try {
      const payload = (await response.json()) as {
        correlationId?: string;
        code?: string;
      };
      correlationId = payload?.correlationId;
      code = payload?.code;
    } catch {
      // A body is not guaranteed; the status is the signal that matters.
    }
    throw new ApiRequestError(response.status, path, correlationId, code);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export interface AdminGrant {
  grantId: number;
  type: string;
  label: string;
  unitNumber: string | null;
  includeDescendants: boolean;
  ioValue: string | null;
  validFrom: string | null;
  expiresAt: string | null;
  revokedAt: string | null;
  active: boolean;
  scopeNote: string | null;
}

export interface AdminLink {
  linkId: number;
  cognitoIssuer: string;
  cognitoSubject: string;
  kualiPersonId: string | null;
  loginName: string | null;
  status: string;
  verifiedAt: string | null;
}

export interface AdminAuditEntry {
  auditId: number;
  occurredAt: string;
  actor: string;
  action: string;
  subject: string | null;
  detail: string | null;
}

export interface AdminIdentity {
  institutionalIdentifier: string;
  suspended: boolean;
  administrator: boolean;
  noArchiveAccess: boolean;
  accessNote: string | null;
  links: AdminLink[];
  grants: AdminGrant[];
  recentAudit: AdminAuditEntry[];
}

export interface AdminIdentitySearchHit {
  source: string;
  sourceNote: string;
  institutionalIdentifier: string | null;
  kualiPersonId: string | null;
  displayName: string | null;
  hasActiveLink: boolean;
}

export interface AdminRoleMember {
  adminMemberId: number;
  institutionalIdentifier: string;
  status: string;
  grantedBy: string;
  grantedAt: string;
  reason: string | null;
}

export interface CreateGrantRequest {
  type: string;
  unitNumber?: string | null;
  includeDescendants?: boolean;
  ioValue?: string | null;
  expiresAt?: string | null;
  reason?: string | null;
}

const base = "/api/v1/admin";

export function searchAdminIdentities(
  query: string,
  signal?: AbortSignal,
): Promise<AdminIdentitySearchHit[]> {
  return adminRequest<AdminIdentitySearchHit[]>(
    `${base}/identities?q=${encodeURIComponent(query)}`,
    "GET",
    undefined,
    signal,
  );
}

export function getAdminIdentity(
  identifier: string,
  signal?: AbortSignal,
): Promise<AdminIdentity> {
  return adminRequest<AdminIdentity>(
    `${base}/identities/${encodeURIComponent(identifier)}`,
    "GET",
    undefined,
    signal,
  );
}

export function createGrant(
  identifier: string,
  request: CreateGrantRequest,
): Promise<AdminGrant> {
  return adminRequest<AdminGrant>(
    `${base}/identities/${encodeURIComponent(identifier)}/grants`,
    "POST",
    request,
  );
}

export function modifyGrant(
  identifier: string,
  grantId: number,
  request: { expiresAt: string | null; includeDescendants: boolean; reason?: string },
): Promise<AdminGrant> {
  return adminRequest<AdminGrant>(
    `${base}/identities/${encodeURIComponent(identifier)}/grants/${grantId}`,
    "PATCH",
    request,
  );
}

export function revokeGrant(
  identifier: string,
  grantId: number,
  reason: string,
): Promise<void> {
  return adminRequest<void>(
    `${base}/identities/${encodeURIComponent(identifier)}/grants/${grantId}` +
      `?reason=${encodeURIComponent(reason)}`,
    "DELETE",
  );
}

export function setSuspension(
  identifier: string,
  suspended: boolean,
  reason: string,
): Promise<void> {
  return adminRequest<void>(
    `${base}/identities/${encodeURIComponent(identifier)}/suspension`,
    "PUT",
    { suspended, reason },
  );
}

export function unlinkIdentity(
  identifier: string,
  linkId: number,
  reason: string,
): Promise<void> {
  return adminRequest<void>(
    `${base}/identities/${encodeURIComponent(identifier)}/links/${linkId}` +
      `?reason=${encodeURIComponent(reason)}`,
    "DELETE",
  );
}

export function listAdministrators(signal?: AbortSignal): Promise<AdminRoleMember[]> {
  return adminRequest<AdminRoleMember[]>(
    `${base}/administrators`,
    "GET",
    undefined,
    signal,
  );
}

export function assignAdministrator(
  institutionalIdentifier: string,
  reason: string,
): Promise<AdminRoleMember> {
  return adminRequest<AdminRoleMember>(`${base}/administrators`, "POST", {
    institutionalIdentifier,
    reason,
  });
}

export function revokeAdministrator(
  institutionalIdentifier: string,
  reason: string,
): Promise<void> {
  return adminRequest<void>(
    `${base}/administrators/${encodeURIComponent(institutionalIdentifier)}` +
      `?reason=${encodeURIComponent(reason)}`,
    "DELETE",
  );
}

export function getAdminAudit(
  parameters: { identity?: string; action?: string; limit?: number } = {},
  signal?: AbortSignal,
): Promise<AdminAuditEntry[]> {
  const search = new URLSearchParams();
  if (parameters.identity) search.set("identity", parameters.identity);
  if (parameters.action) search.set("action", parameters.action);
  search.set("limit", String(parameters.limit ?? 100));
  return adminRequest<AdminAuditEntry[]>(
    `${base}/audit?${search.toString()}`,
    "GET",
    undefined,
    signal,
  );
}
