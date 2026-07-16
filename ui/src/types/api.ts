export interface DashboardSummary {
  irb: number;
  protocolFamilies: number;
  protocolVersions: number;
  submissions: number;
  fundingRecords: number;
  timelineEvents: number;
  awards: number;
  proposals: number;
  negotiations: number;
  subawards: number;
  documents: number;
}

export interface IrbProtocol {
  recordId: number;
  studyId: string | null;
  protocolBase: string;
  protocolNumber: string;
  title: string;
  protocolType: string | null;
  protocolStatus: string | null;
  approvalDate: string | null;
  piBuid: string | null;
  piFullName: string | null;
  piEmail: string | null;
  piBuidMissing: boolean;
  active: boolean;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface GlobalSearchItem {
  recordId: number | null;
  protocolId: number;
  module: string;
  identifier: string;
  secondaryIdentifier: string | null;
  title: string;
  status: string | null;
  personName: string | null;
  recordType: string | null;
}

export interface GlobalSearchResponse {
  query: string;
  totalResults: number;
  results: GlobalSearchItem[];
}

export interface IrbWorkspaceProtocol {
  protocolId: number;
  protocolBase: string;
  protocolNumber: string;
  sequenceNumber: number | null;
  crcProtocolNumber: string | null;
  documentNumber: string | null;
  title: string | null;
  protocolType: string | null;
  protocolStatus: string | null;
  ohrpCategories: string | null;
  summaryKeywords: string | null;
  piId: string | null;
  piEmail: string | null;
  piAffiliation: string | null;
  fundCenterNumber: string | null;
  schoolNumber: string | null;
  irbAnalystId: string | null;
  irbAdvisorId: string | null;
  receivedDate: string | null;
  claimedDate: string | null;
  determinationDate: string | null;
  approvalDate: string | null;
  expirationDate: string | null;
  closureDate: string | null;
  authorizationDate: string | null;
  recordStorageBox: string | null;
  expirationStatus: string | null;
  workingDays: number | null;
  calendarDays: number | null;
  irbDays: number | null;
  piDays: number | null;
  fundingSourceCount: number | null;
}

export interface IrbWorkspaceFunding {
  sequence: number | null;
  source: string;
}

export interface IrbWorkspaceSubmission {
  sequenceNumber: number | null;
  submissionNumber: number | null;
  submissionType: string | null;
  submissionStatus: string | null;
  eventType: string | null;
  reviewType: string | null;
}

export interface IrbWorkspaceTimelineEvent {
  date: string;
  type: string;
  sequence: number | null;
}

export interface IrbWorkspace {
  protocol: IrbWorkspaceProtocol;
  funding: IrbWorkspaceFunding[];
  submissions: IrbWorkspaceSubmission[];
  timeline: IrbWorkspaceTimelineEvent[];
}
