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

export function getIrbProtocols(
  page = 0,
  size = 10,
  query = "",
): Promise<PageResponse<IrbProtocol>> {
  const parameters = new URLSearchParams({
    page: String(page),
    size: String(size),
    sort: "approvalDate",
    direction: "desc",
  });

  if (query.trim()) {
    parameters.set("query", query.trim());
  }

  return request<PageResponse<IrbProtocol>>(
    `/api/irb?${parameters.toString()}`,
  );
}

export function getIrbProtocol(
  studyId: string,
): Promise<IrbProtocol> {
  return request<IrbProtocol>(
    `/api/irb/${encodeURIComponent(studyId)}`,
  );
}
