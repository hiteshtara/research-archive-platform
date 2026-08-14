import { accessToken } from "../auth";
import { parseDownloadFilename } from "../features/award/awardSectionsPresentation.mjs";

import type {
  AwardAiQuestionResponse,
  AwardAiSummaryResponse,
  AwardEvidenceSearchResponse,
  DashboardSummary,
  DocumentSearchPageResponse,
  SafeApiErrorResponse,
} from "../types/api";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL;

console.log("API_BASE_URL =", API_BASE_URL);
if (!API_BASE_URL) {
  throw new Error("VITE_API_BASE_URL is not configured.");
}

export class ApiRequestError extends Error {
  readonly status: number;
  readonly correlationId?: string;

  constructor(
    status: number,
    path: string,
    correlationId?: string,
  ) {
    super(`Request failed with status ${status}: ${path}`);
    this.name = "ApiRequestError";
    this.status = status;
    this.correlationId = correlationId;
  }
}

async function readSafeError(
  response: Response,
): Promise<SafeApiErrorResponse | undefined> {
  try {
    const body: unknown = await response.json();
    if (!body || typeof body !== "object") {
      return undefined;
    }

    const candidate = body as Record<string, unknown>;
    return {
      status:
        typeof candidate.status === "number" ? candidate.status : undefined,
      error: typeof candidate.error === "string" ? candidate.error : undefined,
      message:
        typeof candidate.message === "string" ? candidate.message : undefined,
      correlationId:
        typeof candidate.correlationId === "string"
          ? candidate.correlationId
          : undefined,
    };
  } catch {
    return undefined;
  }
}

async function request<T>(
  path: string,
  signal?: AbortSignal,
  method: "GET" | "POST" = "GET",
  body?: unknown,
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
      ...(body === undefined
        ? {}
        : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (!response.ok) {
    const safeError = await readSafeError(response);
    throw new ApiRequestError(
      response.status,
      path,
      safeError?.correlationId,
    );
  }

  return response.json() as Promise<T>;
}

export function getDashboard(): Promise<DashboardSummary> {
  return request<DashboardSummary>("/api/dashboard");
}

export interface DocumentSearchParameters {
  documentNumber?: string;
  module?: string;
  businessRecordNumber?: string;
  title?: string;
  status?: string;
  page?: number;
  size?: number;
}

export function searchDocuments(
  parameters: DocumentSearchParameters = {},
  signal?: AbortSignal,
): Promise<DocumentSearchPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 25),
  });

  if (parameters.documentNumber?.trim()) {
    searchParameters.set("documentNumber", parameters.documentNumber.trim());
  }
  if (parameters.module?.trim()) {
    searchParameters.set("module", parameters.module.trim());
  }
  if (parameters.businessRecordNumber?.trim()) {
    searchParameters.set(
      "businessRecordNumber",
      parameters.businessRecordNumber.trim(),
    );
  }
  if (parameters.title?.trim()) {
    searchParameters.set("title", parameters.title.trim());
  }
  if (parameters.status?.trim()) {
    searchParameters.set("status", parameters.status.trim());
  }

  return request<DocumentSearchPageResponse>(
    `/api/documents/search?${searchParameters.toString()}`,
    signal,
  );
}

export interface DocumentExplorerParameters {
  documentNumber?: string;
  businessRecordNumber?: string;
  query?: string;
  module?: string;
  normalizedStatus?: string;
  nativeStatus?: string;
  unitNumber?: string;
  includeAnyUnit?: boolean;
  personId?: string;
  personName?: string;
  personRole?: string;
  piOnly?: boolean;
  sponsorCode?: string;
  subrecipientOrganizationId?: string;
  dateFrom?: string;
  dateTo?: string;
  sort?: string;
  page?: number;
  size?: number;
}

const DOCUMENT_EXPLORER_STRING_PARAMS: (keyof DocumentExplorerParameters)[] = [
  "documentNumber",
  "businessRecordNumber",
  "query",
  "module",
  "normalizedStatus",
  "nativeStatus",
  "unitNumber",
  "personId",
  "personName",
  "personRole",
  "sponsorCode",
  "subrecipientOrganizationId",
  "dateFrom",
  "dateTo",
  "sort",
];

export function searchDocumentExplorer(
  parameters: DocumentExplorerParameters = {},
  signal?: AbortSignal,
): Promise<import("../types/api").DocumentExplorerResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 25),
    includeAnyUnit: String(parameters.includeAnyUnit ?? false),
    piOnly: String(parameters.piOnly ?? false),
  });

  for (const key of DOCUMENT_EXPLORER_STRING_PARAMS) {
    const value = parameters[key];
    if (typeof value === "string" && value.trim()) {
      searchParameters.set(key, value.trim());
    }
  }

  return request<import("../types/api").DocumentExplorerResponse>(
    `/api/v1/documents?${searchParameters.toString()}`,
    signal,
  );
}

export function globalSearch(
  query: string,
): Promise<import("../types/api").GlobalSearchResponse> {
  const parameters = new URLSearchParams({
    query: query.trim(),
  });

  return request<import("../types/api").GlobalSearchResponse>(
    `/api/global-search?${parameters.toString()}`,
  );
}

// --- Award API v1 (search / hierarchy / summary / versions) ---------------

export function searchAwardsV1(
  parameters: {
    q?: string;
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").AwardSearchResponseV1> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 25),
  });

  if (parameters.q?.trim()) {
    searchParameters.set("q", parameters.q.trim());
  }

  return request(`/api/v1/awards/search?${searchParameters.toString()}`, signal);
}

export function getAwardHierarchyV1(
  awardNumber: string,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardHierarchy> {
  return request(
    `/api/v1/awards/${encodeURIComponent(awardNumber)}/hierarchy`,
    signal,
  );
}

export function getAwardSummaryV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardSummaryV1> {
  return request(`/api/v1/awards/${encodeURIComponent(awardId)}/summary`, signal);
}

export function getAwardFundingProposalsV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardFundingProposalV1[]> {
  return request(
    `/api/v1/awards/${encodeURIComponent(awardId)}/funding-proposals`,
    signal,
  );
}

export function getAwardFundingSubawardsV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardFundingSubaward[]> {
  return request(
    `/api/v1/awards/${encodeURIComponent(awardId)}/funding-subawards`,
    signal,
  );
}

export function getAwardAssociatedNegotiationsV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardAssociatedNegotiation[]> {
  return request(
    `/api/v1/awards/${encodeURIComponent(awardId)}/negotiations`,
    signal,
  );
}

export function getAwardVersionsV1(
  awardId: number,
  parameters: {
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").AwardVersionPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 50),
  });

  return request(
    `/api/v1/awards/${encodeURIComponent(
      awardId,
    )}/versions?${searchParameters.toString()}`,
    signal,
  );
}

// Historical Award Records explorer - version-level search across every
// archived Award, never scoped to the current version. Deliberately a
// separate call from searchAwardsV1 above, which must stay family/
// current-record oriented.
export function searchAwardVersionsV1(
  parameters: {
    q?: string;
    awardNumber?: string;
    documentNumber?: string;
    awardId?: string;
    versionFilter?: "all" | "current" | "historical";
    sort?: "sequence" | "date";
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").AwardVersionSearchPageResponse> {
  const searchParameters = new URLSearchParams({
    versionFilter: parameters.versionFilter ?? "all",
    sort: parameters.sort ?? "sequence",
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 25),
  });

  if (parameters.q?.trim()) {
    searchParameters.set("q", parameters.q.trim());
  }
  if (parameters.awardNumber?.trim()) {
    searchParameters.set("awardNumber", parameters.awardNumber.trim());
  }
  if (parameters.documentNumber?.trim()) {
    searchParameters.set("documentNumber", parameters.documentNumber.trim());
  }
  if (parameters.awardId?.trim()) {
    searchParameters.set("awardId", parameters.awardId.trim());
  }

  return request(
    `/api/v1/awards/versions/search?${searchParameters.toString()}`,
    signal,
  );
}

// --- Award API v1 Phase 2 (people / amounts / terms / comments / SAP / attachments) ---

export function getAwardPeopleV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardPersonDetailV1[]> {
  return request(`/api/v1/awards/${encodeURIComponent(awardId)}/people`, signal);
}

export function getAwardUnitDetailsV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardUnitDetailsV1> {
  return request(
    `/api/v1/awards/${encodeURIComponent(awardId)}/unit-details`,
    signal,
  );
}

export function getAwardUnitContactsV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardUnitContactV1[]> {
  return request(
    `/api/v1/awards/${encodeURIComponent(awardId)}/unit-contacts`,
    signal,
  );
}

export function getAwardSponsorContactsV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardSponsorContactV1[]> {
  return request(
    `/api/v1/awards/${encodeURIComponent(awardId)}/sponsor-contacts`,
    signal,
  );
}

export function getAwardCentralAdministrationContactsV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardCentralAdministrationContactV1[]> {
  return request(
    `/api/v1/awards/${encodeURIComponent(awardId)}/central-administration-contacts`,
    signal,
  );
}

export function getAwardAmountsV1(
  awardId: number,
  parameters: {
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").AwardAmountHistoryPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 50),
  });

  return request(
    `/api/v1/awards/${encodeURIComponent(
      awardId,
    )}/amounts?${searchParameters.toString()}`,
    signal,
  );
}

export function getAwardTermsV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardTermsV1> {
  return request(`/api/v1/awards/${encodeURIComponent(awardId)}/terms`, signal);
}

export function getAwardCustomDataV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardCustomDataV1[]> {
  return request(
    `/api/v1/awards/${encodeURIComponent(awardId)}/custom-data`,
    signal,
  );
}

export function getAwardCommentsV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardCommentsV1> {
  return request(`/api/v1/awards/${encodeURIComponent(awardId)}/comments`, signal);
}

export function getAwardSapTransmissionsV1(
  awardId: number,
  parameters: {
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").AwardSapTransmissionPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 25),
  });

  return request(
    `/api/v1/awards/${encodeURIComponent(
      awardId,
    )}/sap-transmissions?${searchParameters.toString()}`,
    signal,
  );
}

export function getAwardAttachmentsV1(
  awardId: number,
  parameters: {
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").AwardAttachmentPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 25),
  });

  return request(
    `/api/v1/awards/${encodeURIComponent(
      awardId,
    )}/attachments?${searchParameters.toString()}`,
    signal,
  );
}

// --- Budget (see docs/kuali-business-rules/Budget.md) ----------------------

export function getAwardBudgetSummaryV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardBudgetSummaryV1> {
  return request(
    `/api/v1/awards/${encodeURIComponent(awardId)}/budget/summary`,
    signal,
  );
}

export function getAwardBudgetVersionsV1(
  awardId: number,
  parameters: {
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").AwardBudgetVersionPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 50),
  });

  return request(
    `/api/v1/awards/${encodeURIComponent(
      awardId,
    )}/budget/versions?${searchParameters.toString()}`,
    signal,
  );
}

export function getAwardBudgetPeriodsV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardBudgetPeriodV1[]> {
  return request(
    `/api/v1/awards/${encodeURIComponent(awardId)}/budget/periods`,
    signal,
  );
}

export function getAwardBudgetLineItemsV1(
  awardId: number,
  parameters: {
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").AwardBudgetLineItemPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 50),
  });

  return request(
    `/api/v1/awards/${encodeURIComponent(
      awardId,
    )}/budget/line-items?${searchParameters.toString()}`,
    signal,
  );
}

export function getAwardBudgetPersonnelV1(
  awardId: number,
  parameters: {
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").AwardBudgetPersonnelPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 50),
  });

  return request(
    `/api/v1/awards/${encodeURIComponent(
      awardId,
    )}/budget/personnel?${searchParameters.toString()}`,
    signal,
  );
}

// --- Time and Money (see docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md) ---

export function getAwardTimeAndMoneySummaryV1(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").TimeAndMoneySummaryV1> {
  return request(
    `/api/v1/awards/${encodeURIComponent(awardId)}/time-and-money/summary`,
    signal,
  );
}

export function getAwardTimeAndMoneyActionsV1(
  awardId: number,
  parameters: {
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").TimeAndMoneyActionPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 50),
  });

  return request(
    `/api/v1/awards/${encodeURIComponent(
      awardId,
    )}/time-and-money/actions?${searchParameters.toString()}`,
    signal,
  );
}

export function getAwardTimeAndMoneyHistoryV1(
  awardId: number,
  parameters: {
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").TimeAndMoneyHistoryPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 50),
  });

  return request(
    `/api/v1/awards/${encodeURIComponent(
      awardId,
    )}/time-and-money/history?${searchParameters.toString()}`,
    signal,
  );
}

export function getAwardTimeAndMoneyTransactionV1(
  awardId: number,
  pendingTransactionId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").TimeAndMoneyTransactionV1> {
  return request(
    `/api/v1/awards/${encodeURIComponent(
      awardId,
    )}/time-and-money/transactions/${encodeURIComponent(
      pendingTransactionId,
    )}`,
    signal,
  );
}

export function getAwardTimeAndMoneyDocumentV1(
  awardId: number,
  timeAndMoneyDocumentNumber: string,
  signal?: AbortSignal,
): Promise<import("../types/api").TimeAndMoneyDocumentV1> {
  return request(
    `/api/v1/awards/${encodeURIComponent(
      awardId,
    )}/time-and-money/documents/${encodeURIComponent(
      timeAndMoneyDocumentNumber,
    )}`,
    signal,
  );
}

// --- Archived File Finder (Phase 2: Award + Proposal + recordType=ALL, ---
// --- /api/v1/attachments/search) -------------------------------------------
// Every filter is an exact-match identifier, combined with AND - there
// is deliberately no free-text query parameter here (unlike
// searchDocumentExplorer/searchAwardVersionsV1's `q`), so nothing can
// veto an exact identifier the way the Historical Award Records q/
// awardNumber interaction once did. recordNumber/recordId are the
// canonical parameter names (Award number/ID or Proposal number/ID,
// per recordType); the backend also still accepts the Phase 1
// awardNumber/awardId names as aliases, but this client always sends
// the canonical names now that both exist. recordId/attachmentId/
// fileId are omitted entirely for recordType=ALL, matching the
// backend's own rejection of them as domain-ambiguous. Download
// reuses downloadAwardAttachmentV1/downloadProposalAttachmentV1 below
// as-is via each result's own recordType/parentId/attachmentId - there
// is no separate/unified download call for this feature.
export function searchArchivedFiles(
  parameters: {
    recordType?: "ALL" | "AWARD" | "PROPOSAL";
    recordNumber?: string;
    documentNumber?: string;
    recordId?: string;
    attachmentId?: string;
    fileId?: string;
    versionFilter?: "all" | "current" | "historical";
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").ArchivedFileSearchPageResponse> {
  const recordType = parameters.recordType ?? "ALL";
  const searchParameters = new URLSearchParams({
    recordType,
    versionFilter: parameters.versionFilter ?? "all",
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 25),
  });

  if (parameters.recordNumber?.trim()) {
    searchParameters.set("recordNumber", parameters.recordNumber.trim());
  }
  if (parameters.documentNumber?.trim()) {
    searchParameters.set("documentNumber", parameters.documentNumber.trim());
  }
  if (recordType !== "ALL") {
    if (parameters.recordId?.trim()) {
      searchParameters.set("recordId", parameters.recordId.trim());
    }
    if (parameters.attachmentId?.trim()) {
      searchParameters.set("attachmentId", parameters.attachmentId.trim());
    }
    if (recordType === "AWARD" && parameters.fileId?.trim()) {
      searchParameters.set("fileId", parameters.fileId.trim());
    }
  }

  return request(
    `/api/v1/attachments/search?${searchParameters.toString()}`,
    signal,
  );
}

export async function downloadAwardAttachmentV1(
  awardId: number,
  attachmentId: number,
  fallbackFileName: string,
): Promise<void> {
  const token = await accessToken();
  if (!token) {
    throw new Error("No Cognito access token is available.");
  }

  const path =
    `/api/v1/awards/${encodeURIComponent(awardId)}` +
    `/attachments/${encodeURIComponent(attachmentId)}/download`;
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      Authorization: `Bearer ${token}`,
    },
  });
  if (!response.ok) {
    if (response.status === 404) {
      throw new Error("This attachment is not available for download.");
    }
    throw new Error(`Download failed with status ${response.status}.`);
  }

  const blobUrl = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = blobUrl;
  link.download = parseDownloadFilename(
    response.headers.get("Content-Disposition"),
    fallbackFileName,
  );
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(blobUrl);
}

export function generateAwardAiSummary(
  awardNumber: string,
  signal?: AbortSignal,
): Promise<AwardAiSummaryResponse> {
  return request(
    `/api/ai/awards/${encodeURIComponent(awardNumber)}/summary`,
    signal,
    "POST",
  );
}

export function askAwardQuestion(
  awardNumber: string,
  question: string,
  signal?: AbortSignal,
): Promise<AwardAiQuestionResponse> {
  return request(
    `/api/ai/awards/${encodeURIComponent(awardNumber)}/questions`,
    signal,
    "POST",
    { question },
  );
}

export function searchAwardEvidence(
  awardNumber: string,
  query: string,
  documentTypes: string[],
  topK?: number,
  signal?: AbortSignal,
): Promise<AwardEvidenceSearchResponse> {
  return request(
    `/api/ai/awards/${encodeURIComponent(awardNumber)}/evidence-search`,
    signal,
    "POST",
    {
      query,
      documentTypes,
      ...(topK === undefined ? {} : { topK }),
    },
  );
}

export function getProposalFamilies(
  parameters: {
    query?: string;
    limit?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").ProposalFamily[]> {
  const searchParameters = new URLSearchParams({
    limit: String(parameters.limit ?? 50),
  });

  if (parameters.query?.trim()) {
    searchParameters.set("query", parameters.query.trim());
  }

  return request(
    `/api/proposals/families?${searchParameters.toString()}`,
    signal,
  );
}

export function getProposalWorkspace(
  proposalNumber: string,
): Promise<import("../types/api").ProposalWorkspaceResponse> {
  return request(`/api/proposals/${encodeURIComponent(proposalNumber)}`);
}

export function getProposalHistory(
  proposalNumber: string,
  parameters: {
    page?: number;
    size?: number;
  } = {},
): Promise<import("../types/api").ProposalVersionPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 20),
  });

  return request(
    `/api/proposals/${encodeURIComponent(
      proposalNumber,
    )}/history?${searchParameters.toString()}`,
  );
}

export function getProposalAwards(
  proposalNumber: string,
): Promise<import("../types/api").ProposalAward[]> {
  return request(`/api/proposals/${encodeURIComponent(proposalNumber)}/awards`);
}

// --- Institutional Proposal API v1 (/api/v1/proposals) - keyed by the
// exact surrogate proposalId, distinct from the family-number-scoped
// functions above, which this section does not modify or replace.

export function getProposalSummaryV1(
  proposalId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").ProposalSummaryV1> {
  return request(`/api/v1/proposals/${encodeURIComponent(proposalId)}`, signal);
}

export function getProposalVersionsV1(
  proposalId: number,
  parameters: {
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").ProposalVersionPageResponseV1> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 50),
  });

  return request(
    `/api/v1/proposals/${encodeURIComponent(
      proposalId,
    )}/versions?${searchParameters.toString()}`,
    signal,
  );
}

export function getProposalPeopleV1(
  proposalId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").ProposalPersonV1[]> {
  return request(
    `/api/v1/proposals/${encodeURIComponent(proposalId)}/people`,
    signal,
  );
}

export function getProposalUnitsV1(
  proposalId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").ProposalUnitsV1> {
  return request(
    `/api/v1/proposals/${encodeURIComponent(proposalId)}/units`,
    signal,
  );
}

export function getProposalAttachmentsV1(
  proposalId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").ProposalAttachmentsV1> {
  return request(
    `/api/v1/proposals/${encodeURIComponent(proposalId)}/attachments`,
    signal,
  );
}

export async function downloadProposalAttachmentV1(
  proposalId: number,
  attachmentId: number,
  fallbackFileName: string,
): Promise<void> {
  const token = await accessToken();
  if (!token) {
    throw new Error("No Cognito access token is available.");
  }

  const path =
    `/api/v1/proposals/${encodeURIComponent(proposalId)}` +
    `/attachments/${encodeURIComponent(attachmentId)}/download`;
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      Authorization: `Bearer ${token}`,
    },
  });
  if (!response.ok) {
    if (response.status === 404) {
      throw new Error("This attachment is not available for download.");
    }
    throw new Error(`Download failed with status ${response.status}.`);
  }

  const blobUrl = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = blobUrl;
  link.download = parseDownloadFilename(
    response.headers.get("Content-Disposition"),
    fallbackFileName,
  );
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(blobUrl);
}

export function getProposalCommentsV1(
  proposalId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").ProposalCommentsV1> {
  return request(
    `/api/v1/proposals/${encodeURIComponent(proposalId)}/comments`,
    signal,
  );
}

export function getProposalFundedAwardsV1(
  proposalId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").ProposalFundedAwardV1[]> {
  return request(
    `/api/v1/proposals/${encodeURIComponent(proposalId)}/funded-awards`,
    signal,
  );
}

export function getProposalCustomDataV1(
  proposalId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").ProposalCustomDataV1[]> {
  return request(
    `/api/v1/proposals/${encodeURIComponent(proposalId)}/custom-data`,
    signal,
  );
}

// Resolve-only: never exposes an internal awardId from the Proposal
// domain's own payload - a caller (Funded Awards' "Open Award" link)
// calls this immediately before navigating to /awards/{awardId}.
export function resolveAwardByNumberV1(
  awardNumber: string,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardIdentifierV1> {
  return request(
    `/api/v1/awards/by-number/${encodeURIComponent(awardNumber)}`,
    signal,
  );
}

export function getNegotiations(
  parameters: {
    query?: string;
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").NegotiationPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 25),
  });

  if (parameters.query?.trim()) {
    searchParameters.set("query", parameters.query.trim());
  }

  return request(`/api/negotiations?${searchParameters.toString()}`, signal);
}

export function getNegotiationWorkspace(
  negotiationId: number,
): Promise<import("../types/api").NegotiationWorkspaceResponse> {
  return request(`/api/negotiations/${encodeURIComponent(negotiationId)}`);
}

export function getNegotiationActivities(
  negotiationId: number,
): Promise<import("../types/api").NegotiationActivity[]> {
  return request(
    `/api/negotiations/${encodeURIComponent(negotiationId)}/activities`,
  );
}

export function getNegotiationCustomData(
  negotiationId: number,
): Promise<import("../types/api").NegotiationCustomData[]> {
  return request(
    `/api/negotiations/${encodeURIComponent(negotiationId)}/custom-data`,
  );
}

export function getNegotiationNotifications(
  negotiationId: number,
): Promise<import("../types/api").NegotiationNotification[]> {
  return request(
    `/api/negotiations/${encodeURIComponent(negotiationId)}/notifications`,
  );
}

export function getNegotiationUnassociatedDetails(
  negotiationId: number,
): Promise<import("../types/api").NegotiationUnassociatedDetail[]> {
  return request(
    `/api/negotiations/${encodeURIComponent(
      negotiationId,
    )}/unassociated-details`,
  );
}

export function getNegotiationAttachments(
  negotiationId: number,
): Promise<import("../types/api").NegotiationAttachment[]> {
  return request(
    `/api/negotiations/${encodeURIComponent(negotiationId)}/attachments`,
  );
}

export function getNegotiationAssociatedRecord(
  negotiationId: number,
): Promise<import("../types/api").NegotiationAssociatedRecord> {
  return request(
    `/api/negotiations/${encodeURIComponent(
      negotiationId,
    )}/associated-record`,
  );
}

async function fetchNegotiationAttachment(
  negotiationId: number,
  attachmentId: number,
): Promise<Response> {
  const token = await accessToken();
  if (!token) {
    throw new Error("No Cognito access token is available.");
  }

  const path =
    `/api/negotiations/${encodeURIComponent(negotiationId)}` +
    `/attachments/${encodeURIComponent(attachmentId)}/download`;
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      Authorization: `Bearer ${token}`,
    },
  });
  if (!response.ok) {
    if (response.status === 404) {
      throw new Error("This attachment is not available for download.");
    }
    throw new Error(`Download failed with status ${response.status}.`);
  }

  return response;
}

export async function downloadNegotiationAttachment(
  negotiationId: number,
  attachmentId: number,
  fallbackFileName: string,
): Promise<void> {
  const response = await fetchNegotiationAttachment(
    negotiationId,
    attachmentId,
  );
  const blobUrl = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = blobUrl;
  link.download = parseDownloadFilename(
    response.headers.get("Content-Disposition"),
    fallbackFileName,
  );
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(blobUrl);
}

export async function previewNegotiationAttachment(
  negotiationId: number,
  attachmentId: number,
): Promise<void> {
  const response = await fetchNegotiationAttachment(
    negotiationId,
    attachmentId,
  );
  const blobUrl = URL.createObjectURL(await response.blob());
  window.open(blobUrl, "_blank", "noopener,noreferrer");
}

export function getSubawards(
  parameters: {
    query?: string;
    page?: number;
    size?: number;
  } = {},
  signal?: AbortSignal,
): Promise<import("../types/api").SubawardPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 25),
  });

  if (parameters.query?.trim()) {
    searchParameters.set("query", parameters.query.trim());
  }

  return request(`/api/subawards?${searchParameters.toString()}`, signal);
}

export function getSubawardWorkspace(
  subawardId: number,
): Promise<import("../types/api").SubawardWorkspaceResponse> {
  return request(`/api/subawards/${encodeURIComponent(subawardId)}`);
}

export function getSubawardAmounts(
  subawardId: number,
): Promise<import("../types/api").SubawardAmount[]> {
  return request(
    `/api/subawards/${encodeURIComponent(subawardId)}/amounts`,
  );
}

export function getSubawardContacts(
  subawardId: number,
): Promise<import("../types/api").SubawardContact[]> {
  return request(
    `/api/subawards/${encodeURIComponent(subawardId)}/contacts`,
  );
}

export function getSubawardCustomData(
  subawardId: number,
): Promise<import("../types/api").SubawardCustomData[]> {
  return request(
    `/api/subawards/${encodeURIComponent(subawardId)}/custom-data`,
  );
}

export function getSubawardFunding(
  subawardId: number,
): Promise<import("../types/api").SubawardFunding[]> {
  return request(
    `/api/subawards/${encodeURIComponent(subawardId)}/funding`,
  );
}

export function getSubawardAttachments(
  subawardId: number,
): Promise<import("../types/api").SubawardAttachment[]> {
  return request(
    `/api/subawards/${encodeURIComponent(subawardId)}/attachments`,
  );
}

export async function downloadSubawardAttachment(
  subawardId: number,
  attachmentId: number,
  fallbackFileName: string,
): Promise<void> {
  const token = await accessToken();
  if (!token) {
    throw new Error("No Cognito access token is available.");
  }

  const path =
    `/api/subawards/${encodeURIComponent(subawardId)}` +
    `/attachments/${encodeURIComponent(attachmentId)}/download`;
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      Authorization: `Bearer ${token}`,
    },
  });
  if (!response.ok) {
    if (response.status === 404) {
      throw new Error("This attachment has not been archived for download.");
    }
    throw new Error(`Download failed with status ${response.status}.`);
  }

  const blobUrl = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = blobUrl;
  link.download = parseDownloadFilename(
    response.headers.get("Content-Disposition"),
    fallbackFileName,
  );
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(blobUrl);
}

export function getSubawardTemplateInfo(
  subawardId: number,
): Promise<import("../types/api").SubawardTemplateInfo> {
  return request(
    `/api/subawards/${encodeURIComponent(subawardId)}/template-info`,
  );
}

export function getSubawardCloseout(
  subawardId: number,
): Promise<import("../types/api").SubawardCloseout[]> {
  return request(
    `/api/subawards/${encodeURIComponent(subawardId)}/closeout`,
  );
}

export function getSubawardReports(
  subawardId: number,
): Promise<import("../types/api").SubawardReport[]> {
  return request(
    `/api/subawards/${encodeURIComponent(subawardId)}/reports`,
  );
}

export function getSubawardNotepad(
  subawardId: number,
): Promise<import("../types/api").SubawardNotepad[]> {
  return request(
    `/api/subawards/${encodeURIComponent(subawardId)}/notepad`,
  );
}

export function getSubawardNotifications(
  subawardId: number,
): Promise<import("../types/api").SubawardNotification[]> {
  return request(
    `/api/subawards/${encodeURIComponent(subawardId)}/notifications`,
  );
}

// --- Archive Explorer (Phase 2, /api/v1/explorer/**) -----------------------
//
// Dev-only, feature-flagged (VITE_EXPLORER_ENABLED) - see
// docs/ARCHIVE_EXPLORER.md. Every call is a fixed, predefined
// lookup by natural identifier, same as the backend - no arbitrary
// query is ever constructed here.

export function getExplorerAward(
  awardNumber: string,
  signal?: AbortSignal,
): Promise<import("../types/api").ExplorerAward> {
  const parameters = new URLSearchParams({ awardNumber: awardNumber.trim() });
  return request(`/api/v1/explorer/awards?${parameters.toString()}`, signal);
}

export function getExplorerAwardVersion(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").ExplorerAward> {
  const parameters = new URLSearchParams({ awardId: String(awardId) });
  return request(
    `/api/v1/explorer/award-versions?${parameters.toString()}`,
    signal,
  );
}

export function getExplorerWorkflow(
  documentNumber: string,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardDocumentNumberMatchV1> {
  const parameters = new URLSearchParams({
    documentNumber: documentNumber.trim(),
  });
  return request(
    `/api/v1/explorer/workflows?${parameters.toString()}`,
    signal,
  );
}

export function getExplorerUnit(
  unitNumber: string,
  signal?: AbortSignal,
): Promise<import("../types/api").ExplorerUnit> {
  const parameters = new URLSearchParams({ unitNumber: unitNumber.trim() });
  return request(`/api/v1/explorer/units?${parameters.toString()}`, signal);
}

export function getExplorerUnitAdministrators(
  unitNumber: string,
  signal?: AbortSignal,
): Promise<import("../types/api").ExplorerUnitAdministrator[]> {
  const parameters = new URLSearchParams({ unitNumber: unitNumber.trim() });
  return request(
    `/api/v1/explorer/unit-administrators?${parameters.toString()}`,
    signal,
  );
}

export function getExplorerAwardContacts(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").ExplorerAwardContacts> {
  const parameters = new URLSearchParams({ awardId: String(awardId) });
  return request(
    `/api/v1/explorer/award-contacts?${parameters.toString()}`,
    signal,
  );
}

export function getExplorerPerson(
  personId: string,
  signal?: AbortSignal,
): Promise<import("../types/api").ExplorerPerson> {
  const parameters = new URLSearchParams({ personId: personId.trim() });
  return request(`/api/v1/explorer/persons?${parameters.toString()}`, signal);
}

export function getExplorerRolodex(
  rolodexId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").ExplorerRolodex> {
  const parameters = new URLSearchParams({ rolodexId: String(rolodexId) });
  return request(`/api/v1/explorer/rolodex?${parameters.toString()}`, signal);
}

export function getExplorerSponsors(
  sponsorCode: string,
  signal?: AbortSignal,
): Promise<import("../types/api").ExplorerAward[]> {
  const parameters = new URLSearchParams({ sponsorCode: sponsorCode.trim() });
  return request(
    `/api/v1/explorer/sponsors?${parameters.toString()}`,
    signal,
  );
}

export function getExplorerAttachments(
  awardId: number,
  signal?: AbortSignal,
): Promise<import("../types/api").AwardAttachmentV1[]> {
  const parameters = new URLSearchParams({ awardId: String(awardId) });
  return request(
    `/api/v1/explorer/attachments?${parameters.toString()}`,
    signal,
  );
}

export function getExplorerProposalDiscovery(
  filters: import("../types/api").ExplorerProposalDiscoveryFilters,
  signal?: AbortSignal,
): Promise<import("../types/api").ExplorerProposalDiscovery[]> {
  const parameters = new URLSearchParams();
  if (filters.hasAttachments !== undefined) {
    parameters.set("hasAttachments", String(filters.hasAttachments));
  }
  if (filters.hasFundedAward !== undefined) {
    parameters.set("hasFundedAward", String(filters.hasFundedAward));
  }
  if (filters.minimumAwardAmount !== undefined) {
    parameters.set(
      "minimumAwardAmount",
      String(filters.minimumAwardAmount),
    );
  }
  if (filters.sponsorCode) {
    parameters.set("sponsorCode", filters.sponsorCode.trim());
  }
  if (filters.sponsorName) {
    parameters.set("sponsorName", filters.sponsorName.trim());
  }
  if (filters.leadUnitNumber) {
    parameters.set("leadUnitNumber", filters.leadUnitNumber.trim());
  }
  if (filters.proposalStatus) {
    parameters.set("proposalStatus", filters.proposalStatus.trim());
  }
  if (filters.personName) {
    parameters.set("personName", filters.personName.trim());
  }
  if (filters.proposalType) {
    parameters.set("proposalType", filters.proposalType.trim());
  }
  if (filters.activityType) {
    parameters.set("activityType", filters.activityType.trim());
  }
  if (filters.dateFrom) {
    parameters.set("dateFrom", filters.dateFrom);
  }
  if (filters.dateTo) {
    parameters.set("dateTo", filters.dateTo);
  }
  parameters.set("page", String(filters.page ?? 0));
  parameters.set("size", String(filters.size ?? 50));

  return request(
    `/api/v1/explorer/proposals?${parameters.toString()}`,
    signal,
  );
}
