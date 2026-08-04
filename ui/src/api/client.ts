import { accessToken } from "../auth";
import { parseDownloadFilename } from "../features/award/awardSectionsPresentation.mjs";

import type {
  AwardAiQuestionResponse,
  AwardAiSummaryResponse,
  DashboardSummary,
  IrbProtocol,
  PageResponse,
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

export interface IrbSearchParameters {
  page?: number;
  size?: number;
  query?: string;
  status?: string;
  type?: string;
  sort?: string;
  direction?: "asc" | "desc";
}

export function getIrbProtocols(
  parameters: IrbSearchParameters = {},
): Promise<PageResponse<IrbProtocol>> {
  const {
    page = 0,
    size = 10,
    query = "",
    status = "",
    type = "",
    sort = "approvalDate",
    direction = "desc",
  } = parameters;

  const searchParameters = new URLSearchParams({
    page: String(page),
    size: String(size),
    sort,
    direction,
  });

  if (query.trim()) {
    searchParameters.set("query", query.trim());
  }

  if (status.trim()) {
    searchParameters.set("status", status.trim());
  }

  if (type.trim()) {
    searchParameters.set("type", type.trim());
  }

  return request<PageResponse<IrbProtocol>>(
    `/api/irb?${searchParameters.toString()}`,
  );
}

export function getIrbProtocolByRecordId(
  recordId: number,
): Promise<IrbProtocol> {
  return request<IrbProtocol>(`/api/irb/record/${recordId}`);
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

export function getIrbWorkspace(
  recordId: number,
): Promise<import("../types/api").IrbWorkspace> {
  return request<import("../types/api").IrbWorkspace>(
    `/api/irb/record/${recordId}/workspace`,
  );
}

export function getIrbFamilies(
  parameters: {
    page?: number;
    size?: number;
    query?: string;
  } = {},
  signal?: AbortSignal,
): Promise<
  import("../types/api").PageResponse<import("../types/api").IrbFamily>
> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 10),
  });

  if (parameters.query?.trim()) {
    searchParameters.set("query", parameters.query.trim());
  }

  return request(
    `/api/irb/families?${searchParameters.toString()}`,
    signal,
  );
}

export function getIrbHistory(
  parameters: {
    page?: number;
    size?: number;
    query?: string;
  } = {},
): Promise<
  import("../types/api").PageResponse<import("../types/api").IrbHistoryVersion>
> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 10),
  });

  if (parameters.query?.trim()) {
    searchParameters.set("query", parameters.query.trim());
  }

  return request(`/api/irb/history?${searchParameters.toString()}`);
}

export function getIrbHistoryVersion(
  protocolId: number,
): Promise<import("../types/api").IrbHistoryVersion> {
  return request<import("../types/api").IrbHistoryVersion>(
    `/api/irb/history/${protocolId}`,
  );
}

export function getInvestigatorProfile(
  email: string,
): Promise<import("../types/api").InvestigatorProfile> {
  const parameters = new URLSearchParams({
    email: email.trim(),
  });

  return request<import("../types/api").InvestigatorProfile>(
    `/api/investigators?${parameters.toString()}`,
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
