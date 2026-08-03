export interface DashboardSummary {
  irb: number;
  submissions: number;
  fundingRecords: number;
  timelineEvents: number;
  awards: number;
  awardHistoryRecords: number;
  proposals: number;
  proposalHistoryRecords: number;
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

export interface AwardSequencePageResponse
  extends PageResponse<AwardSequenceSummary> {}

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

export interface AiCitation {
  recordType: string;
  recordId: string;
  awardNumber: string;
  sequenceNumber: number;
}

export interface AwardAiSummaryResponse {
  overview: string;
  currentRecord: AwardAiCurrentRecord;
  timeline: AwardAiTimelineRecord[];
  notableChanges: string[];
  archiveAssessment: string;
  citations: AiCitation[];
  provider: string;
  model: string;
  correlationId: string;
}

export interface AwardAiQuestionResponse {
  answer: string;
  answerType: string;
  citations: AiCitation[];
  provider: string;
  model: string;
  correlationId: string;
}

export interface AwardAiCurrentRecord {
  awardId: number;
  awardNumber: string;
  sequenceNumber: number;
  title: string | null;
  status: string | null;
  sponsor: string | null;
  leadUnit: string | null;
  principalInvestigators: string[];
  beginDate: string | null;
  closeoutDate: string | null;
  anticipatedTotalAmount: number | null;
  obligatedTotalAmount: number | null;
}

export interface AwardAiTimelineRecord {
  awardId: number;
  awardNumber: string;
  sequenceNumber: number;
  current: boolean | null;
  primaryCurrent: boolean | null;
  status: string | null;
  awardSequenceStatus: string | null;
  sponsor: string | null;
  leadUnit: string | null;
  beginDate: string | null;
  closeoutDate: string | null;
}

export interface SafeApiErrorResponse {
  status?: number;
  error?: string;
  message?: string;
  correlationId?: string;
}

// --- Award API v1 (search / hierarchy / summary / versions) ---------------

export interface AwardSearchHit {
  awardId: number;
  awardNumber: string;
  latestSequenceNumber: number | null;
  title: string | null;
  status: string | null;
  principalInvestigator: string | null;
  sponsor: string | null;
  leadUnit: string | null;
  currentObligatedAmount: number | null;
  rootAwardNumber: string | null;
  parentAwardNumber: string | null;
}

export interface AwardSearchPageResponse extends PageResponse<AwardSearchHit> {}

// An exact match against a real workflow document number
// (archive.award_version.workflow_document_number - the Kuali
// KREW_DOC_HDR_T.DOC_HDR_ID-linked identifier), searched across every
// archived version of every Award, not only the current one - so it can
// identify a specific superseded sequence, not just the Award family.
export interface AwardDocumentNumberMatchV1 {
  awardId: number;
  awardNumber: string;
  sequenceNumber: number;
  workflowDocumentNumber: string;
  documentType: string | null;
  title: string | null;
  status: string | null;
}

export interface AwardSearchResponseV1 {
  exactDocumentMatch: AwardDocumentNumberMatchV1 | null;
  results: AwardSearchPageResponse;
}

export interface AwardHierarchyNode {
  awardNumber: string;
  awardId: number | null;
  latestSequenceNumber: number | null;
  parentAwardNumber: string | null;
  active: boolean | null;
  title: string | null;
  status: string | null;
  principalInvestigator: string | null;
  sponsor: string | null;
  leadUnit: string | null;
  currentObligatedAmount: number | null;
  children: AwardHierarchyNode[];
}

export interface AwardHierarchy {
  rootAwardNumber: string;
  requestedAwardNumber: string;
  root: AwardHierarchyNode;
  selectedAwardPath: string[];
}

export interface AwardSummaryV1 {
  awardId: number;
  awardNumber: string;
  sequenceNumber: number;
  title: string | null;
  status: string | null;
  sponsor: string | null;
  primeSponsor: string | null;
  principalInvestigator: string | null;
  leadUnit: string | null;
  awardEffectiveDate: string | null;
  awardExecutionDate: string | null;
  beginDate: string | null;
  closeoutDate: string | null;
  obligatedTotalAmount: number | null;
  anticipatedTotalAmount: number | null;
  basisOfPaymentCode: string | null;
  basisOfPaymentDescription: string | null;
  methodOfPaymentCode: string | null;
  methodOfPaymentDescription: string | null;
  rootAwardNumber: string | null;
  parentAwardNumber: string | null;
}

export interface AwardVersionV1 {
  awardId: number;
  awardNumber: string;
  sequenceNumber: number;
  status: string | null;
  transactionTypeCode: string | null;
  transactionType: string | null;
  awardEffectiveDate: string | null;
  updateTimestamp: string | null;
  documentNumber: string | null;
  modificationNumber: string | null;
  primaryCurrent: boolean;
}

export interface AwardVersionPageResponse extends PageResponse<AwardVersionV1> {}

// --- Award API v1 Phase 2 (people / amounts / terms / comments / SAP / attachments) ---

export interface AwardCreditSplitV1 {
  creditTypeCode: string | null;
  credit: number | null;
}

export interface AwardPersonUnitV1 {
  unitNumber: string | null;
  leadUnit: boolean;
  creditSplits: AwardCreditSplitV1[];
}

export interface AwardPersonDetailV1 {
  awardPersonId: number | null;
  personId: string | null;
  fullName: string | null;
  contactRoleCode: string | null;
  keyPersonProjectRole: string | null;
  leadPrincipalInvestigator: boolean;
  academicYearEffort: number | null;
  calendarYearEffort: number | null;
  summerEffort: number | null;
  totalEffort: number | null;
  units: AwardPersonUnitV1[];
  creditSplits: AwardCreditSplitV1[];
}

export interface AwardAmountHistoryV1 {
  awardAmountInfoId: number | null;
  awardId: number | null;
  awardNumber: string | null;
  sequenceNumber: number | null;
  obligatedTotalDirect: number | null;
  obligatedTotalIndirect: number | null;
  obligatedTotalAmount: number | null;
  anticipatedChangeDirect: number | null;
  anticipatedChangeIndirect: number | null;
  anticipatedTotalDirect: number | null;
  anticipatedTotalIndirect: number | null;
  anticipatedTotalAmount: number | null;
  awardEffectiveDate: string | null;
  documentNumber: string | null;
  sourceVersionNumber: number | null;
}

export interface AwardAmountHistoryPageResponse
  extends PageResponse<AwardAmountHistoryV1> {}

export interface AwardSponsorTermV1 {
  awardSponsorTermId: number | null;
  sponsorTermId: number | null;
}

export interface AwardReportTermRecipientV1 {
  awardReportTermRecipientId: number | null;
  contactId: number | null;
  contactTypeCode: string | null;
  rolodexId: number | null;
  numberOfCopies: number | null;
}

export interface AwardReportTermV1 {
  awardReportTermId: number | null;
  reportClassCode: string | null;
  reportCode: string | null;
  frequencyCode: string | null;
  frequencyBaseCode: string | null;
  ospDistributionCode: string | null;
  dueDate: string | null;
  recipients: AwardReportTermRecipientV1[];
}

export interface AwardTermsV1 {
  sponsorTerms: AwardSponsorTermV1[];
  reportTerms: AwardReportTermV1[];
}

export interface AwardCommentV1 {
  awardCommentId: number | null;
  commentTypeCode: string | null;
  checklistPrintFlag: string | null;
  comments: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
}

export interface AwardNotepadEntryV1 {
  awardNotepadId: number | null;
  entryNumber: number | null;
  noteTopic: string | null;
  comments: string | null;
  restrictedView: string | null;
  sourceCreateTimestamp: string | null;
  sourceCreateUser: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
}

export interface AwardCommentsV1 {
  comments: AwardCommentV1[];
  notepadEntries: AwardNotepadEntryV1[];
}

export interface AwardSapTransmissionChildV1 {
  transmissionChildId: number | null;
  awardNumber: string | null;
  sequenceNumber: number | null;
  parentDocumentNumber: string | null;
  childDocumentNumber: string | null;
  leadUnitNumber: string | null;
  childType: string | null;
  overheadKey: string | null;
  baseCode: string | null;
  offCampus: string | null;
}

export interface AwardSapTransmissionV1 {
  transmissionId: number | null;
  awardNumber: string | null;
  sequenceNumber: number | null;
  initiatorId: string | null;
  transmitterId: string | null;
  successIndicator: string | null;
  successful: boolean;
  transmissionDate: string | null;
  basisOfPaymentCode: string | null;
  accountTypeCode: number | null;
  sponsorCode: string | null;
  methodOfPaymentCode: string | null;
  documentNumber: string | null;
  sentData: string | null;
  returnedData: string | null;
  children: AwardSapTransmissionChildV1[];
}

export interface AwardSapTransmissionPageResponse
  extends PageResponse<AwardSapTransmissionV1> {}

export interface AwardAttachmentV1 {
  awardAttachmentId: number | null;
  awardNumber: string | null;
  sequenceNumber: number | null;
  fileName: string | null;
  contentType: string | null;
  description: string | null;
  typeCode: string | null;
  documentStatusCode: string | null;
  fileSizeBytes: number | null;
  uploadStatus: string | null;
  downloadable: boolean;
  oracleUpdateTimestamp: string | null;
}

export interface AwardAttachmentPageResponse
  extends PageResponse<AwardAttachmentV1> {}

export interface ProposalFamily {
  proposalNumber: string;
  title: string | null;
  status: string | null;
  sponsorName: string | null;
  leadUnitName: string | null;
  principalInvestigator: string | null;
  latestVersionNumber: number;
  currentProposalId: number;
}

export interface ProposalRow {
  proposalId: number;
  proposalNumber: string;
  versionNumber: number;
  title: string | null;
  status: string | null;
  proposalType: string | null;
  activityType: string | null;
  sponsorCode: string | null;
  sponsorName: string | null;
  leadUnitNumber: string | null;
  leadUnitName: string | null;
  principalInvestigatorId: string | null;
  principalInvestigator: string | null;
  initialStartDate: string | null;
  initialEndDate: string | null;
  initialDirectCost: number | null;
  initialIndirectCost: number | null;
  initialTotalCost: number | null;
  totalStartDate: string | null;
  totalEndDate: string | null;
  totalDirectCost: number | null;
  totalIndirectCost: number | null;
  totalCost: number | null;
}

export interface ProposalWorkspaceResponse {
  proposalNumber: string;
  current: ProposalRow;
}

export interface ProposalVersionPageResponse
  extends PageResponse<ProposalRow> {}

export interface ProposalAward {
  proposalId: number;
  awardId: number | null;
  awardNumber: string | null;
}

export interface NegotiationSummary {
  negotiationId: number;
  documentNumber: string | null;
  negotiationStatusId: number | null;
  negotiationStatusCode: string | null;
  negotiationStatusDescription: string | null;
  negotiationAgreementTypeId: number | null;
  negotiationAgreementTypeCode: string | null;
  negotiationAgreementTypeDescription: string | null;
  negotiationAssociationTypeId: number | null;
  negotiationAssociationTypeCode: string | null;
  negotiationAssociationTypeDescription: string | null;
  associatedDocumentId: string | null;
  negotiatorPersonId: string | null;
  negotiatorFullName: string | null;
  negotiationStartDate: string | null;
  negotiationEndDate: string | null;
  anticipatedAwardDate: string | null;
}

export interface NegotiationPageResponse
  extends PageResponse<NegotiationSummary> {}

export interface NegotiationRow extends NegotiationSummary {
  documentFolder: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
  documentSourceUpdateTimestamp: string | null;
  documentSourceUpdateUser: string | null;
  documentSourceVersionNumber: number | null;
  documentSourceObjectId: string | null;
}

export interface NegotiationWorkspaceResponse {
  negotiationId: number;
  current: NegotiationRow;
}

export interface NegotiationActivity {
  negotiationActivityId: number;
  negotiationId: number;
  activityTypeId: number | null;
  activityTypeCode: string | null;
  activityTypeDescription: string | null;
  locationId: number | null;
  locationCode: string | null;
  locationDescription: string | null;
  startDate: string | null;
  endDate: string | null;
  createDate: string | null;
  followupDate: string | null;
  lastModifiedUser: string | null;
  lastModifiedDate: string | null;
  description: string | null;
  restricted: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
}

export interface NegotiationCustomData {
  negotiationCustomDataId: number;
  negotiationId: number;
  negotiationNumber: string | null;
  customAttributeId: number | null;
  value: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
}

export interface NegotiationNotification {
  notificationId: number;
  notificationTypeId: number | null;
  documentNumber: string | null;
  owningDocumentIdFk: number;
  recipients: string | null;
  subject: string | null;
  message: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
}

export interface NegotiationUnassociatedDetail {
  negotiationUnassocDetailId: number;
  negotiationId: number;
  title: string | null;
  piPersonId: string | null;
  piRolodexId: string | null;
  leadUnit: string | null;
  sponsorCode: string | null;
  piName: string | null;
  primeSponsorCode: string | null;
  sponsorAwardNumber: string | null;
  contactAdminPersonId: string | null;
  subawardOrg: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
}

export interface SubawardSummary {
  subawardId: number;
  subawardCode: string;
  sequenceNumber: number;
  documentNumber: string | null;
  title: string | null;
  statusCode: number | null;
  statusDescription: string | null;
  organizationId: string | null;
  accountNumber: string | null;
  startDate: string | null;
  endDate: string | null;
  subawardSequenceStatus: string | null;
  sourceUpdateTimestamp: string | null;
}

export interface SubawardPageResponse extends PageResponse<SubawardSummary> {}

export interface SubawardRow {
  subawardId: number;
  documentNumber: string | null;
  sequenceNumber: number;
  subawardCode: string;
  organizationId: string | null;
  startDate: string | null;
  endDate: string | null;
  subawardTypeCode: number | null;
  purchaseOrderNum: string | null;
  title: string | null;
  statusCode: number | null;
  statusDescription: string | null;
  accountNumber: string | null;
  vendorNumber: string | null;
  requisitionerId: string | null;
  requisitionerUnit: string | null;
  archiveLocation: string | null;
  closeoutDate: string | null;
  comments: string | null;
  siteInvestigator: number | null;
  costType: string | null;
  dateOfFullyExecuted: string | null;
  requisitionNumber: string | null;
  fedAwardProjDesc: string | null;
  fAndARate: number | null;
  deMinimus: string | null;
  subawardSequenceStatus: string | null;
  ffataRequired: string | null;
  fsrsSubawardNumber: string | null;
  awardPrimeSponsorName: string | null;
  awardSponsorName: string | null;
  extensionDateReceived: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
  documentSourceUpdateTimestamp: string | null;
  documentSourceUpdateUser: string | null;
  documentSourceVersionNumber: number | null;
  documentSourceObjectId: string | null;
}

export interface SubawardWorkspaceResponse {
  subawardId: number;
  current: SubawardRow;
}

export interface SubawardAmount {
  subawardAmountInfoId: number;
  subawardId: number;
  subawardCode: string;
  sequenceNumber: number;
  obligatedAmount: number | null;
  obligatedChange: number | null;
  obligatedChangeDirect: number | null;
  obligatedChangeIndirect: number | null;
  anticipatedAmount: number | null;
  anticipatedChange: number | null;
  anticipatedChangeDirect: number | null;
  anticipatedChangeIndirect: number | null;
  rate: number | null;
  effectiveDate: string | null;
  modificationEffectiveDate: string | null;
  modificationNumber: string | null;
  modificationTypeCode: string | null;
  modificationTypeDescription: string | null;
  performanceStartDate: string | null;
  performanceEndDate: string | null;
  purchaseOrderNum: string | null;
  comments: string | null;
  fileDataId: string | null;
  fileName: string | null;
  mimeType: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
}

export interface SubawardContact {
  subawardContactId: number;
  subawardId: number;
  subawardCode: string;
  sequenceNumber: number;
  contactTypeCode: string | null;
  rolodexId: number | null;
  requisitionerId: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
}

export interface SubawardCustomData {
  subawardCustomDataId: number;
  subawardId: number;
  subawardCode: string;
  sequenceNumber: number;
  customAttributeId: number | null;
  value: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
}

export interface SubawardFunding {
  subawardFundingSourceId: number;
  subawardId: number;
  subawardCode: string;
  sequenceNumber: number;
  awardId: number | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
}

export interface SubawardAttachment {
  attachmentId: number;
  subawardId: number;
  subawardCode: string;
  sequenceNumber: number;
  attachmentTypeCode: number | null;
  attachmentTypeDescription: string | null;
  documentId: number | null;
  fileName: string | null;
  mimeType: string | null;
  documentStatusCode: string | null;
  description: string | null;
  lastUpdateTimestamp: string | null;
  lastUpdateUser: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
  archived: boolean;
}

export interface SubawardTemplateInfo {
  subawardId: number;
  subawardCode: string;
  sequenceNumber: number;
  sowOrSubProposalBudget: string | null;
  subProposalDate: string | null;
  invoiceOrPaymentContact: number | null;
  irbIacucContact: number | null;
  finalStmtOfCostsContact: number | null;
  changeRequestsContact: number | null;
  subChangeRequestsContact: number | null;
  terminationContact: number | null;
  subTerminationContact: number | null;
  noCostExtensionContact: number | null;
  perfSiteDiffFromOrgAddr: string | null;
  perfSiteSameAsSubPiAddr: string | null;
  subRegisteredInCcr: string | null;
  subExemptFromReportingComp: string | null;
  parentDunsNumber: string | null;
  parentCongressionalDistrict: string | null;
  exemptFromRprtgExecComp: string | null;
  copyrightType: string | null;
  automaticCarryForward: string | null;
  carryForwardRequestsSentTo: number | null;
  treatmentPrgmIncomeAdditive: string | null;
  applicableProgramRegulations: string | null;
  applicableProgramRegsDate: string | null;
  mpiAward: string | null;
  mpiLeadershipPlan: string | null;
  rAndD: string | null;
  includesCostSharing: string | null;
  fcio: string | null;
  invoicesEmailed: string | null;
  invoiceAddressDiff: string | null;
  invoiceEmailDiff: string | null;
  fcioSubrecPolicyCd: string | null;
  animalFlag: string | null;
  animalPteSendCd: string | null;
  animalPteNrCd: string | null;
  humanFlag: string | null;
  humanSubjects: string | null;
  humanExemptDocs: string | null;
  humanPteSendCd: string | null;
  humanPteNrCd: string | null;
  humanDataExchangeAgreeCd: string | null;
  humanDataExchangeTermsCd: string | null;
  humanIncludesClinicalTrials: string | null;
  additionalTerms: string | null;
  treatmentOfIncome: string | null;
  dataSharingAttachment: string | null;
  dataSharingCd: string | null;
  finalStatementDueCd: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
}

export interface SubawardCloseout {
  subawardCloseoutId: number;
  subawardId: number;
  subawardCode: string;
  sequenceNumber: number;
  closeoutNumber: number | null;
  closeoutTypeCode: number | null;
  dateRequested: string | null;
  dateFollowup: string | null;
  dateReceived: string | null;
  comments: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
}

export interface SubawardReport {
  subawardReportId: string;
  subawardId: number;
  subawardCode: string;
  sequenceNumber: number;
  reportTypeCode: string | null;
  reportTypeDescription: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
}

export interface SubawardNotepad {
  subawardNotepadId: number;
  subawardId: number;
  subawardCode: string;
  entryNumber: number | null;
  noteTopic: string | null;
  comments: string | null;
  restrictedView: string | null;
  createTimestamp: string | null;
  createUser: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
}

export interface SubawardNotification {
  notificationId: number;
  owningDocumentIdFk: number;
  documentNumber: string | null;
  subawardCode: string | null;
  notificationTypeId: number | null;
  recipients: string | null;
  subject: string | null;
  message: string | null;
  createTimestamp: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  sourceVersionNumber: number | null;
  sourceObjectId: string | null;
}
