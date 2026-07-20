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

export interface IrbFamily {
  protocolBase: string;
  versionCount: number;
  latestProtocolId: number;
  latestProtocolNumber: string;
  latestTitle: string | null;
  latestStatus: string | null;
  latestType: string | null;
  piId: string | null;
  piEmail: string | null;
  latestApprovalDate: string | null;
}

export interface IrbHistoryVersion {
  protocolId: number;
  protocolBase: string;
  protocolNumber: string;
  sequenceNumber: number | null;
  documentNumber: string | null;
  crcProtocolNumber: string | null;
  title: string | null;
  protocolStatus: string | null;
  protocolType: string | null;
  piId: string | null;
  piEmail: string | null;
  approvalDate: string | null;
  expirationDate: string | null;
}

export interface InvestigatorStudy {
  recordId: number | null;
  protocolId: number;
  protocolBase: string;
  protocolNumber: string;
  title: string | null;
  status: string | null;
  recordType: string | null;
  approvalDate: string | null;
}

export interface InvestigatorProfile {
  name: string;
  email: string;
  buid: string | null;
  currentStudyCount: number;
  historicalStudyCount: number;
  currentStudies: InvestigatorStudy[];
  historicalStudies: InvestigatorStudy[];
}

export interface AwardFamily {
  awardNumber: string;
  title: string;
  status: string | null;
  awardSequenceStatus: string | null;
  sponsor: string | null;
  leadUnit: string | null;
  accountNumber: string | null;
  latestSequenceNumber: number;
  primaryAwardId: number;
}

export interface AwardRow {
  awardId: number;
  awardNumber: string;
  sequenceNumber: number;
  title: string;
  status: string | null;
  awardSequenceStatus: string;
  sponsor: string | null;
  primeSponsor: string | null;
  leadUnit: string | null;
  accountNumber: string | null;
  sponsorAwardNumber: string | null;
  beginDate: string | null;
  closeoutDate: string | null;
  current: boolean;
  primaryCurrent: boolean;
}

export interface AwardSequence {
  sequenceNumber: number;
  currentSequence: boolean;
  rows: AwardRow[];
}

export interface AwardFamilyResponse {
  awardNumber: string;
  current: AwardRow;
  sequences: AwardSequence[];
}

export interface AwardWorkspaceResponse {
  awardNumber: string;
  current: AwardRow;
}

export interface AwardSequenceSummary {
  sequenceNumber: number;
  status: string | null;
  awardSequenceStatus: string | null;
  currentSequence: boolean;
  rowCount: number;
  representativeAwardId: number;
}

export interface AwardSequencePageResponse {
  content: AwardSequenceSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface AwardSequenceDetailResponse {
  awardNumber: string;
  sequenceNumber: number;
  currentSequence: boolean;
  rows: AwardRow[];
}

export interface AwardPerson {
  awardPersonId: number;
  awardId: number;
  awardNumber: string;
  sequenceNumber: number;
  personId: string | null;
  rolodexId: number | null;
  fullName: string | null;
  contactRoleCode: string | null;
  keyPersonProjectRole: string | null;
  facultyFlag: string | null;
  academicYearEffort: number | null;
  calendarYearEffort: number | null;
  summerEffort: number | null;
  totalEffort: number | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
}

export interface AwardUnitContact {
  awardUnitContactId: number;
  awardId: number;
  awardNumber: string;
  sequenceNumber: number;
  personId: string | null;
  fullName: string | null;
  unitNumber: string | null;
  unitName: string | null;
  parentUnitNumber: string | null;
  parentUnitName: string | null;
  unitAdministratorTypeCode: string | null;
  projectRole: string | null;
  unitContactType: string | null;
  defaultUnitContact: string | null;
  primaryTitle: string | null;
  directoryTitle: string | null;
  officeLocation: string | null;
  emailAddress: string | null;
  officePhone: string | null;
  phoneExtension: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
}

export interface AwardAmount {
  awardAmountInfoId: number;
  awardId: number;
  awardNumber: string;
  sequenceNumber: number;
  anticipatedChangeDirect: number | null;
  anticipatedChangeIndirect: number | null;
  anticipatedTotalDirect: number | null;
  anticipatedTotalIndirect: number | null;
  obligatedTotalDirect: number | null;
  obligatedTotalIndirect: number | null;
  anticipatedTotalAmount: number | null;
  obligatedTotalAmount: number | null;
  tnmDocumentNumber: string | null;
  sourceVersionNumber: number | null;
}

export interface AwardProposal {
  awardFundingProposalId: number;
  awardId: number;
  proposalId: number;
  activeFlag: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
}

export interface AwardFunding {
  awardNumber: string;
  sponsor: string | null;
  primeSponsor: string | null;
  sponsorAwardNumber: string | null;
  leadUnit: string | null;
  linkedProposalCount: number;
  activeProposalCount: number;
}
