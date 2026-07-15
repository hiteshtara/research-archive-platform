import type {
  DashboardSummary,
  IrbProtocol,
  PageResponse,
} from "../types/api";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL;

if (!API_BASE_URL) {
  throw new Error("VITE_API_BASE_URL is not configured.");
}

async function request<T>(path: string): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`);

  if (!response.ok) {
    throw new Error(
      `Request failed with status ${response.status}: ${path}`,
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
  return request<IrbProtocol>(
    `/api/irb/record/${recordId}`,
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

export function getIrbWorkspace(
  recordId: number,
): Promise<import("../types/api").IrbWorkspace> {
  return request<import("../types/api").IrbWorkspace>(
    `/api/irb/record/${recordId}/workspace`,
  );
}
