import { accessToken } from "../auth";

import type { DashboardSummary, IrbProtocol, PageResponse } from "../types/api";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL;

console.log("API_BASE_URL =", API_BASE_URL);
if (!API_BASE_URL) {
  throw new Error("VITE_API_BASE_URL is not configured.");
}

async function request<T>(path: string): Promise<T> {
  const token = await accessToken();

  if (!token) {
    throw new Error("No Cognito access token is available.");
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/json",
    },
  });

  if (!response.ok) {
    throw new Error(`Request failed with status ${response.status}: ${path}`);
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

  return request(`/api/irb/families?${searchParameters.toString()}`);
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

export function getProtocols(
  parameters: {
    query?: string;
    page?: number;
    size?: number;
  } = {},
): Promise<
  import("../types/api").PageResponse<import("../types/api").ProtocolSummary>
> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 25),
  });
  if (parameters.query?.trim()) {
    searchParameters.set("query", parameters.query.trim());
  }
  return request(`/api/protocols?${searchParameters.toString()}`);
}

export function getProtocolHistory(
  protocolNumber: string,
): Promise<import("../types/api").ProtocolVersion[]> {
  return request(
    `/api/protocols/${encodeURIComponent(protocolNumber)}/history`,
  );
}

export function getProtocolPersonnel(
  protocolId: number,
): Promise<import("../types/api").ProtocolPerson[]> {
  return request(
    `/api/protocols/versions/${encodeURIComponent(protocolId)}/personnel`,
  );
}

export function getProtocolFunding(
  protocolId: number,
): Promise<import("../types/api").ProtocolFunding[]> {
  return request(
    `/api/protocols/versions/${encodeURIComponent(protocolId)}/funding`,
  );
}

export function getProtocolResearchAreas(
  protocolId: number,
): Promise<import("../types/api").ProtocolResearchArea[]> {
  return request(
    `/api/protocols/versions/${encodeURIComponent(
      protocolId,
    )}/research-areas`,
  );
}

export function getProtocolLocations(
  protocolId: number,
): Promise<import("../types/api").ProtocolLocation[]> {
  return request(
    `/api/protocols/versions/${encodeURIComponent(protocolId)}/locations`,
  );
}

export function getProtocolSubmissions(
  protocolId: number,
): Promise<import("../types/api").ProtocolSubmission[]> {
  return request(
    `/api/protocols/versions/${encodeURIComponent(
      protocolId,
    )}/submissions`,
  );
}

export function getProtocolActions(
  protocolId: number,
): Promise<import("../types/api").ProtocolAction[]> {
  return request(
    `/api/protocols/versions/${encodeURIComponent(protocolId)}/actions`,
  );
}

export function getProtocolAmendRenewals(
  protocolId: number,
): Promise<import("../types/api").ProtocolAmendRenewal[]> {
  return request(
    `/api/protocols/versions/${encodeURIComponent(
      protocolId,
    )}/amend-renewals`,
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

export function getAwardFamilies(
  parameters: {
    query?: string;
    limit?: number;
  } = {},
): Promise<import("../types/api").AwardFamily[]> {
  const searchParameters = new URLSearchParams({
    limit: String(parameters.limit ?? 50),
  });

  if (parameters.query?.trim()) {
    searchParameters.set("query", parameters.query.trim());
  }

  return request(`/api/awards/families?${searchParameters.toString()}`);
}

export function getAwardHistory(
  awardNumber: string,
): Promise<import("../types/api").AwardFamilyResponse> {
  return request(`/api/awards/history/${encodeURIComponent(awardNumber)}`);
}

export function getAwardWorkspace(
  awardNumber: string,
): Promise<import("../types/api").AwardWorkspaceResponse> {
  return request(`/api/awards/${encodeURIComponent(awardNumber)}`);
}

export function getAwardSequencePage(
  awardNumber: string,
  parameters: {
    page?: number;
    size?: number;
  } = {},
): Promise<import("../types/api").AwardSequencePageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 20),
  });

  return request(
    `/api/awards/${encodeURIComponent(
      awardNumber,
    )}/history?${searchParameters.toString()}`,
  );
}

export function getAwardSequenceDetail(
  awardNumber: string,
  sequenceNumber: number,
): Promise<import("../types/api").AwardSequenceDetailResponse> {
  return request(
    `/api/awards/${encodeURIComponent(awardNumber)}/history/${sequenceNumber}`,
  );
}

export function getAwardPeople(
  awardNumber: string,
): Promise<import("../types/api").AwardPerson[]> {
  return request(`/api/awards/${encodeURIComponent(awardNumber)}/people`);
}

export function getAwardUnitContacts(
  awardNumber: string,
): Promise<import("../types/api").AwardUnitContact[]> {
  return request(
    `/api/awards/${encodeURIComponent(awardNumber)}/unit-contacts`,
  );
}

export function getAwardAmounts(
  awardNumber: string,
): Promise<import("../types/api").AwardAmount[]> {
  return request(`/api/awards/${encodeURIComponent(awardNumber)}/amounts`);
}

export function getAwardProposals(
  awardNumber: string,
): Promise<import("../types/api").AwardProposal[]> {
  return request(`/api/awards/${encodeURIComponent(awardNumber)}/proposals`);
}

export function getAwardFunding(
  awardNumber: string,
): Promise<import("../types/api").AwardFunding> {
  return request(`/api/awards/${encodeURIComponent(awardNumber)}/funding`);
}

export function getProposalFamilies(
  parameters: {
    query?: string;
    limit?: number;
  } = {},
): Promise<import("../types/api").ProposalFamily[]> {
  const searchParameters = new URLSearchParams({
    limit: String(parameters.limit ?? 50),
  });

  if (parameters.query?.trim()) {
    searchParameters.set("query", parameters.query.trim());
  }

  return request(`/api/proposals/families?${searchParameters.toString()}`);
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

export function getProposalPeople(
  proposalNumber: string,
): Promise<import("../types/api").ProposalPerson[]> {
  return request(`/api/proposals/${encodeURIComponent(proposalNumber)}/people`);
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
): Promise<import("../types/api").NegotiationPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 25),
  });

  if (parameters.query?.trim()) {
    searchParameters.set("query", parameters.query.trim());
  }

  return request(`/api/negotiations?${searchParameters.toString()}`);
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
): Promise<import("../types/api").SubawardPageResponse> {
  const searchParameters = new URLSearchParams({
    page: String(parameters.page ?? 0),
    size: String(parameters.size ?? 25),
  });

  if (parameters.query?.trim()) {
    searchParameters.set("query", parameters.query.trim());
  }

  return request(`/api/subawards?${searchParameters.toString()}`);
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

function downloadFileName(
  contentDisposition: string | null,
  fallback: string,
): string {
  const encoded = contentDisposition?.match(/filename\*=UTF-8''([^;]+)/i);
  if (encoded?.[1]) {
    return decodeURIComponent(encoded[1].replace(/^"|"$/g, ""));
  }
  const plain = contentDisposition?.match(/filename="?([^";]+)"?/i);
  return plain?.[1] ?? fallback;
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
  link.download = downloadFileName(
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
