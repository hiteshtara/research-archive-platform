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

// One row of the Kuali Document Search union (Award/Proposal/
// Negotiation/Subaward/IRB only - never attachments, never the
// Award-nested transactional/financial document tables). targetRoute
// is backend-computed and ready to navigate to; null only if the
// module's routable identifier was unexpectedly missing.
export interface DocumentSearchResult {
  module: "AWARD" | "PROPOSAL" | "NEGOTIATION" | "SUBAWARD" | "IRB";
  documentNumber: string;
  businessRecordNumber: string;
  title: string | null;
  status: string | null;
  versionOrSequence: string | null;
  relevantDate: string | null;
  targetRoute: string | null;
}

export interface DocumentSearchPageResponse
  extends PageResponse<DocumentSearchResult> {}

// One row of the Kuali Document Explorer union - IRB excluded (see
// docs/architecture/KUALI_DOCUMENT_EXPLORER_DESIGN.md §16 decision 7).
// normalizedStatus is always one of the six approved buckets;
// nativeStatusCode/nativeStatusDescription are always preserved
// alongside it so a user can see the real underlying value even when
// normalizedStatus is UNKNOWN. unitCount/personCount/sponsorCount are
// ADDITIONAL relationships beyond the single primary value already
// shown - the UI renders "+N other" from these, never duplicate rows.
export interface DocumentExplorerResult {
  module: "AWARD" | "PROPOSAL" | "NEGOTIATION" | "SUBAWARD";
  documentNumber: string;
  businessRecordNumber: string;
  title: string | null;
  normalizedStatus:
    | "ACTIVE"
    | "PENDING"
    | "ARCHIVED"
    | "CANCELLED"
    | "CLOSED"
    | "UNKNOWN";
  nativeStatusCode: string | null;
  nativeStatusDescription: string | null;
  versionOrSequence: string | null;
  leadUnitNumber: string | null;
  leadUnitName: string | null;
  primaryPersonId: string | null;
  primaryPersonName: string | null;
  primaryPersonRole: string | null;
  sponsorCode: string | null;
  sponsorName: string | null;
  subrecipientOrganizationId: string | null;
  subrecipientOrganizationName: string | null;
  documentDate: string | null;
  targetRoute: string | null;
  unitCount: number;
  personCount: number;
  sponsorCount: number;
}

export interface FacetCount {
  value: string;
  count: number;
}

export interface DocumentExplorerResponse {
  results: PageResponse<DocumentExplorerResult>;
  moduleFacets: FacetCount[];
}

// The one stable, cross-domain result shape Global Search returns
// regardless of which domain a result came from - route is the
// backend-computed, ready-to-navigate frontend path (null when this
// result has no real place to click through to - a card with a null
// route must render as non-clickable, never guess a route from
// module-specific identifiers on the frontend).
export interface GlobalSearchItem {
  module: string;
  recordId: number | null;
  identifier: string;
  title: string;
  subtitle: string | null;
  status: string | null;
  documentNumber: string | null;
  matchedField: string | null;
  matchedValue: string | null;
  route: string | null;
  protocolId: number | null;
  awardId: number | null;
  sequenceNumber: number | null;
  primaryCurrent: boolean | null;
  // "RELATED" for a semantic-search result that survived deduplication
  // against structured results; null for every structured result.
  matchType: string | null;
}

// failedModules names every domain whose search errored during this
// request (e.g. "IRB", "AWARD") - partial results are always returned
// rather than failing the whole search for one domain's outage.
export interface GlobalSearchResponse {
  query: string;
  totalResults: number;
  results: GlobalSearchItem[];
  failedModules: string[];
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

// Award Evidence Search (Phase 3) - direct, cited evidence retrieval,
// never a generated narrative answer. Every field traces back to a
// real archive.search_embedding (V071) row - see
// docs/architecture/AWARD_EVIDENCE_RETRIEVAL_PHASE3_DESIGN.md section
// 3.3/6. excerpt is always server-redacted and length-capped before it
// reaches this response - never raw, unbounded archive text.
export interface AwardEvidenceSearchResult {
  documentType: string;
  awardNumber: string;
  title: string;
  excerpt: string;
  sourceTable: string;
  sourcePrimaryKey: string;
  score: number;
  targetSection: string;
}

export interface AwardEvidenceSearchResponse {
  query: string;
  awardNumber: string;
  results: AwardEvidenceSearchResult[];
  insufficientEvidence: boolean;
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

// The Funding Proposal(s) behind this Award - one row per real
// archive.award_funding_proposal relationship row, family-wide (every
// award_id in this Award's whole award_number family), from
// GET /api/v1/awards/{awardId}/funding-proposals - the bidirectional
// counterpart to ProposalFundedAwardV1. exactLinkedProposalId is the
// database relationship's own historical proposal_id (audit only,
// never rendered); navigableActiveProposalId is that Proposal
// family's ACTIVE version, resolved server-side, and is what a client
// navigates to. relationshipActive mirrors
// AWARD_FUNDING_PROPOSALS.ACTIVE - inactive relationships are still
// returned, never dropped.
export interface AwardFundingProposalV1 {
  proposalNumber: string;
  proposalTitle: string | null;
  proposalStatus: string | null;
  workflowDocumentNumber: string | null;
  principalInvestigatorName: string | null;
  sponsorName: string | null;
  requestedTotalCost: number | null;
  linkedProposalVersion: number | null;
  activeProposalVersion: number | null;
  relationshipActive: boolean;
  exactLinkedProposalId: number | null;
  navigableActiveProposalId: number | null;
}

export interface AwardFundingSubaward {
  subawardCode: string;
  organizationId: string | null;
  subawardStatus: string | null;
  workflowDocumentNumber: string | null;
  subawardAmount: number | null;
  linkedSubawardVersion: number | null;
  currentSubawardVersion: number | null;
  exactLinkedSubawardId: number | null;
  navigableCurrentSubawardId: number | null;
}

export interface AwardAssociatedNegotiation {
  negotiationId: number;
  documentNumber: string | null;
  negotiationStatusDescription: string | null;
  negotiationAgreementTypeDescription: string | null;
  negotiatorFullName: string | null;
  negotiationStartDate: string | null;
  negotiationEndDate: string | null;
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
  primaryCurrent: boolean;
  documentNumber: string | null;
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

// Historical Award Records explorer - one row per award_id (a specific
// version), never scoped to the current version. Deliberately a
// separate type from AwardSearchHit (which is always current-only) and
// from AwardVersionV1 (which is always one Award family's own history,
// not a cross-family search result).
export interface AwardVersionSearchHit {
  awardId: number;
  awardNumber: string;
  sequenceNumber: number | null;
  documentNumber: string | null;
  title: string | null;
  status: string | null;
  sponsor: string | null;
  principalInvestigator: string | null;
  leadUnit: string | null;
  awardEffectiveDate: string | null;
  updateTimestamp: string | null;
  primaryCurrent: boolean;
}

export interface AwardVersionSearchPageResponse
  extends PageResponse<AwardVersionSearchHit> {}

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

// An Award has exactly one lead unit (archive.unit, the shared
// reference table Award/Proposal/Negotiation/Subaward/Time & Money are
// all expected to reuse - never a second, Award-owned copy of Unit
// data). leadUnit is always true here.
export interface AwardUnitDetailsV1 {
  unitNumber: string | null;
  unitName: string | null;
  parentUnitNumber: string | null;
  parentUnitName: string | null;
  organization: string | null;
  leadUnit: boolean;
}

// Real, Award-specific archived data (archive.award_unit_contact) -
// never derived, unlike AwardCentralAdministrationContactV1. unitNumber
// is this contact's own associated unit and can differ from the
// Award's lead unit - leadUnit is true only when it matches.
export interface AwardUnitContactV1 {
  personId: string | null;
  fullName: string | null;
  projectRole: string | null;
  unitNumber: string | null;
  leadUnit: boolean;
  email: string | null;
  phone: string | null;
}

// Resolves through archive.award_sponsor_contact and, when present,
// the shared archive.rolodex table.
export interface AwardSponsorContactV1 {
  fullName: string | null;
  organization: string | null;
  contactRoleCode: string | null;
  email: string | null;
  phone: string | null;
}

// DERIVED, never persisted as its own table - reproduces Kuali's
// Award.initCentralAdminContacts() exactly: the Award's lead unit's
// administrators filtered to default_group_flag='C'.
export interface AwardCentralAdministrationContactV1 {
  personId: string | null;
  fullName: string | null;
  projectRole: string | null;
  email: string | null;
  phone: string | null;
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

// One archived award_comment row - a specific Award version's comment,
// with its workflow_document_number joined in. Appears both as a
// category's "current" entry and as a member of its "history" list.
export interface AwardCommentEntryV1 {
  awardCommentId: number | null;
  awardId: number;
  sequenceNumber: number | null;
  workflowDocumentNumber: string | null;
  commentText: string | null;
  updateTimestamp: string | null;
  updateUser: string | null;
}

// One human-readable comment category (e.g. "General Comments"),
// covering every archived version of the whole Award number family -
// not just the version being viewed. "current" is the newest entry in
// "history" (null when this Award family has no comment of this type
// at all - render "No comment recorded"). "history" is newest-to-oldest
// with only consecutive exact-text duplicates collapsed.
export interface AwardCommentCategoryV1 {
  commentTypeCode: string;
  commentTypeDescription: string | null;
  current: AwardCommentEntryV1 | null;
  history: AwardCommentEntryV1[];
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
  commentCategories: AwardCommentCategoryV1[];
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

// --- Time and Money (see docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md) ---
//
// Summary is scoped to one exact awardId (the version being viewed),
// not the whole award_number family. Actions/History are family-wide.
// timeAndMoneyDocumentNumber/pendingTransactionId/
// awardAmountTransactionId/transactionDetailId are always named
// explicitly - this bundle has several differently-typed columns
// historically named TRANSACTION_ID in real Kuali, so a bare
// "transactionId" is never used here.

// Split scope, proven against real Kuali source and live data:
// awardId/awardNumber/sequenceNumber/obligated*/anticipated* are
// scoped to this EXACT awardId (the version being viewed) -
// genuinely version-specific financial state. familyTransactionCount/
// lastFamilyTimeAndMoneyDocumentNumber/lastFamilyNoticeDate/
// lastFamilyTransactionTypeDescription are scoped to the WHOLE
// awardNumber family (every version) - most ordinary Awards' current
// version has never itself been Time-and-Money-created even when
// their family has real history, so these four fields deliberately
// search across all versions rather than just this one.
// Budget: proven live against real Oracle/archive data (see
// docs/kuali-business-rules/Budget.md) that budget_version_number is a
// family-wide monotonic counter, not scoped to one award_id -
// selectedBudgetId/selectedBudgetVersionNumber are the archive-facing
// "current" Budget (highest version with Posted status, falling back
// to highest non-Cancelled), deliberately named "selected" rather than
// "current" so it is never mistaken for Kuali's own live
// getCurrentBudget() concept, which targets transient in-progress
// statuses that essentially never survive to a closed archive.
export interface AwardBudgetSummaryV1 {
  awardId: number;
  awardNumber: string;
  viewedSequenceNumber: number;
  selectedBudgetId: number | null;
  selectedBudgetVersionNumber: number | null;
  statusCode: string | null;
  statusDescription: string | null;
  workflowDocumentNumber: string | null;
  startDate: string | null;
  endDate: string | null;
  totalDirectCost: number | null;
  totalIndirectCost: number | null;
  totalCost: number | null;
  // Frozen, per-version snapshots of an Award-level computation - real
  // Kuali source proves these are NOT the version's own requested
  // amount (totalCost above) and NOT something to recompute; see
  // docs/kuali-business-rules/Budget.md. Null for legacy "Converted
  // Budget Document" versions that predate this snapshot.
  awardBudgetTotalCostLimit: number | null;
  budgetChangeTotalCostLimit: number | null;
}

// owningAwardId/owningAwardSequenceNumber record which specific Award
// version this budget actually belongs to - consecutive budget
// versions routinely belong to different Award sequences within the
// same award_number family (budget_version_number is family-wide, not
// per-award_id). selected marks the same budget the Summary's
// selectedBudgetId points to.
export interface AwardBudgetVersionV1 {
  budgetId: number;
  budgetVersionNumber: number;
  owningAwardId: number;
  owningAwardSequenceNumber: number;
  workflowDocumentNumber: string | null;
  statusCode: string | null;
  statusDescription: string | null;
  startDate: string | null;
  endDate: string | null;
  totalDirectCost: number | null;
  totalIndirectCost: number | null;
  totalCost: number | null;
  awardBudgetTotalCostLimit: number | null;
  budgetChangeTotalCostLimit: number | null;
  selected: boolean;
}

export interface AwardBudgetVersionPageResponse
  extends PageResponse<AwardBudgetVersionV1> {}

export interface AwardBudgetPeriodV1 {
  budgetPeriodId: number;
  periodNumber: number | null;
  startDate: string | null;
  endDate: string | null;
  totalDirectCost: number | null;
  totalIndirectCost: number | null;
  totalCost: number | null;
}

export interface AwardBudgetLineItemV1 {
  budgetLineItemId: number;
  budgetPeriodId: number;
  lineItemNumber: number | null;
  description: string | null;
  costElement: string | null;
  startDate: string | null;
  endDate: string | null;
  lineItemCost: number | null;
  costSharingAmount: number | null;
}

export interface AwardBudgetLineItemPageResponse
  extends PageResponse<AwardBudgetLineItemV1> {}

// calculatedSalary is the sum of every real, persisted calculated-cost
// row for this personnel line (Oracle stores one row per rate
// application, never a single "calculated salary" field) - a sum of
// real stored numbers, not a computed/invented figure. See
// docs/kuali-business-rules/Budget.md.
export interface AwardBudgetPersonnelV1 {
  budgetPersonId: number;
  personId: string | null;
  fullName: string | null;
  jobCode: string | null;
  appointmentType: string | null;
  baseSalary: number | null;
  calculatedSalary: number | null;
}

export interface AwardBudgetPersonnelPageResponse
  extends PageResponse<AwardBudgetPersonnelV1> {}

export interface TimeAndMoneySummaryV1 {
  awardId: number;
  awardNumber: string;
  sequenceNumber: number;
  obligatedTotalAmount: number | null;
  obligatedTotalDirect: number | null;
  obligatedTotalIndirect: number | null;
  anticipatedTotalAmount: number | null;
  anticipatedTotalDirect: number | null;
  anticipatedTotalIndirect: number | null;
  familyTransactionCount: number;
  lastFamilyTimeAndMoneyDocumentNumber: string | null;
  lastFamilyNoticeDate: string | null;
  lastFamilyTransactionTypeDescription: string | null;
}

export interface TimeAndMoneyActionV1 {
  awardAmountTransactionId: number;
  awardNumber: string;
  timeAndMoneyDocumentNumber: string;
  transactionTypeCode: string | null;
  transactionTypeDescription: string | null;
  noticeDate: string | null;
  comments: string | null;
  documentStatus: string | null;
  creationDate: string | null;
  sourceUpdateUser: string | null;
  sourceUpdateTimestamp: string | null;
}

export interface TimeAndMoneyActionPageResponse
  extends PageResponse<TimeAndMoneyActionV1> {}

// sequenceNumber is this row's own Award version (via the
// award_version join). originatingAwardVersion is a separate value:
// the Award version a Time and Money-created snapshot was produced
// against - the two are usually equal but can differ. Both null
// pendingTransactionId/timeAndMoneyDocumentNumber and false
// timeAndMoneyCreated mean this is the Award's original entry, never
// touched by a Time and Money action.
export interface TimeAndMoneyHistoryEntryV1 {
  awardAmountInfoId: number;
  awardId: number;
  awardNumber: string;
  sequenceNumber: number;
  pendingTransactionId: number | null;
  timeAndMoneyDocumentNumber: string | null;
  originatingAwardVersion: number | null;
  obligatedTotalDirect: number | null;
  obligatedTotalIndirect: number | null;
  obligatedTotalAmount: number | null;
  anticipatedChangeDirect: number | null;
  anticipatedChangeIndirect: number | null;
  anticipatedTotalDirect: number | null;
  anticipatedTotalIndirect: number | null;
  anticipatedTotalAmount: number | null;
  awardEffectiveDate: string | null;
  timeAndMoneyCreated: boolean;
}

export interface TimeAndMoneyHistoryPageResponse
  extends PageResponse<TimeAndMoneyHistoryEntryV1> {}

export interface TimeAndMoneyTransactionDetailV1 {
  transactionDetailId: number;
  awardNumber: string;
  sequenceNumber: number;
  timeAndMoneyDocumentNumber: string;
  sourceAwardNumber: string;
  destinationAwardNumber: string;
  obligatedAmount: number | null;
  obligatedDirectAmount: number | null;
  obligatedIndirectAmount: number | null;
  anticipatedAmount: number | null;
  anticipatedDirectAmount: number | null;
  anticipatedIndirectAmount: number | null;
  comments: string | null;
  transactionDetailType: string | null;
}

// The pending_transaction-sourced fields (everything except
// pendingTransactionId/timeAndMoneyDocumentNumber/details) are
// nullable - an old, already-processed transaction's working-state row
// may no longer exist in Oracle; transaction_detail (the durable
// ledger) still resolves the lookup. fandaDistributionPeriod is an F&A
// cost-distribution period identifier - never a Budget Version.
export interface TimeAndMoneyTransactionV1 {
  pendingTransactionId: number;
  timeAndMoneyDocumentNumber: string | null;
  sourceAwardNumber: string | null;
  destinationAwardNumber: string | null;
  obligatedAmount: number | null;
  obligatedDirectAmount: number | null;
  obligatedIndirectAmount: number | null;
  anticipatedAmount: number | null;
  anticipatedDirectAmount: number | null;
  anticipatedIndirectAmount: number | null;
  comments: string | null;
  processedFlag: string | null;
  fandaDistributionPeriod: string | null;
  details: TimeAndMoneyTransactionDetailV1[];
}

// "Workflow Details" - a Time and Money KEW document's own header.
// There is no live KEW connection in this archive - documentStatus/
// creationDate are the only "workflow" information available, not a
// live routing/approval trail. A different KEW document from any
// Award's own workflowDocumentNumber - never cross-link the two.
export interface TimeAndMoneyDocumentV1 {
  timeAndMoneyDocumentNumber: string;
  rootAwardNumber: string;
  documentStatus: string | null;
  creationDate: string | null;
}

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

// --- Institutional Proposal API v1 (/api/v1/proposals) ---
//
// Keyed by the exact surrogate proposalId (one specific version), not
// proposalNumber - mirrors the Award v1 API's own convention.
// proposalId, proposalNumber, sequenceNumber, and
// workflowDocumentNumber are four distinct identifiers, never inferred
// one from another. Distinct from the older, family-number-scoped
// ProposalRow/ProposalWorkspaceResponse/ProposalFamily types above.

export interface ProposalSummaryV1 {
  proposalId: number;
  proposalNumber: string;
  sequenceNumber: number;
  workflowDocumentNumber: string | null;
  title: string | null;
  status: string | null;
  proposalSequenceStatus: string | null;
  proposalType: string | null;
  activityType: string | null;
  leadUnitNumber: string | null;
  leadUnitName: string | null;
  sponsorCode: string | null;
  sponsorName: string | null;
  principalInvestigatorId: string | null;
  principalInvestigatorName: string | null;
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

export interface ProposalVersionV1 {
  proposalId: number;
  proposalNumber: string;
  sequenceNumber: number;
  workflowDocumentNumber: string | null;
  proposalSequenceStatus: string | null;
  status: string | null;
  title: string | null;
  sourceUpdateTimestamp: string | null;
}

export interface ProposalVersionPageResponseV1
  extends PageResponse<ProposalVersionV1> {}

export interface ProposalPersonV1 {
  proposalPersonId: number;
  personId: string | null;
  fullName: string | null;
  contactRoleCode: string | null;
  keyPersonProjectRole: string | null;
  principalInvestigator: boolean;
  facultyFlag: string | null;
  academicYearEffort: number | null;
  calendarYearEffort: number | null;
  summerEffort: number | null;
  totalEffort: number | null;
}

export interface ProposalAssociatedUnitV1 {
  proposalPersonUnitId: number;
  proposalPersonId: number;
  personName: string | null;
  unitNumber: string | null;
  unitName: string | null;
  leadUnit: boolean;
}

// A genuinely separate sibling table from ProposalPersonV1 - never
// merged (live-verified as a different real person than the PI in the
// reference fixture).
export interface ProposalUnitContactV1 {
  proposalUnitContactId: number;
  personId: string | null;
  fullName: string | null;
  unitAdministratorTypeCode: string | null;
  unitAdministratorTypeDescription: string | null;
  unitContactType: string | null;
}

export interface ProposalUnitsV1 {
  associatedUnits: ProposalAssociatedUnitV1[];
  unitContacts: ProposalUnitContactV1[];
}

export interface ProposalAttachmentV1 {
  proposalAttachmentId: number;
  sequenceNumber: number | null;
  attachmentNumber: number | null;
  attachmentTitle: string | null;
  attachmentTypeCode: number | null;
  attachmentTypeDescription: string | null;
  fileName: string | null;
  contentType: string | null;
  comments: string | null;
  fileSizeBytes: number | null;
  uploadStatus: string | null;
  downloadable: boolean;
  sourceUpdateTimestamp: string | null;
}

// Grouped by Oracle's real PROPOSAL_ATTACHMENT_TYPE taxonomy - never
// by attachmentTitle (a title containing "Guidelines" is still filed
// under Oracle's real "Other" type - see the API's own migration
// comment for the live-verified proof).
export interface ProposalAttachmentGroupV1 {
  attachmentTypeCode: number | null;
  attachmentTypeDescription: string | null;
  attachments: ProposalAttachmentV1[];
}

export interface ProposalAttachmentsV1 {
  groups: ProposalAttachmentGroupV1[];
}

export interface ProposalCommentEntryV1 {
  proposalCommentId: number | null;
  proposalId: number | null;
  sequenceNumber: number | null;
  comments: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
}

// Only "Proposal Comments" and "Proposal IP Review Comments" (codes
// 12/13) are shown, per explicit instruction - family-wide, with
// history behavior matching Award's own Comments screen (consecutive
// identical text collapsed to its earliest occurrence).
export interface ProposalCommentCategoryV1 {
  commentTypeCode: string;
  commentTypeDescription: string | null;
  current: ProposalCommentEntryV1 | null;
  history: ProposalCommentEntryV1[];
}

export interface ProposalCommentsV1 {
  commentCategories: ProposalCommentCategoryV1[];
}

// One row per real archive.proposal_award relationship row (this
// Proposal's whole proposalNumber family). exactLinkedAwardId is the
// database relationship's own historical award_id (audit only, never
// rendered); navigableCurrentAwardId is that Award family's CURRENT
// version, resolved server-side, and is what a client navigates to -
// no separate resolve-by-number call needed. relationshipActive
// mirrors AWARD_FUNDING_PROPOSALS.ACTIVE - inactive relationships are
// still returned, never dropped.
export interface ProposalFundedAwardV1 {
  awardNumber: string;
  awardTitle: string | null;
  awardStatus: string | null;
  proposalVersion: number | null;
  linkedAwardVersion: number | null;
  currentAwardVersion: number | null;
  relationshipActive: boolean;
  exactLinkedAwardId: number | null;
  navigableCurrentAwardId: number | null;
}

// archive.proposal_custom_data LEFT JOINed to the shared
// archive.custom_attribute lookup, scoped to this exact proposalId
// (version-scoped - never combined with a sibling version's rows).
// label/name/dataType/groupName are null when custom_attribute_id has
// no matching lookup row yet (the column is deliberately not a foreign
// key - Oracle can add new attributes over time); value being null is
// a real persisted blank, distinct from the attribute having no row at
// all (which simply never appears in this list).
export interface ProposalCustomDataV1 {
  proposalCustomDataId: number;
  customAttributeId: number | null;
  label: string | null;
  name: string | null;
  dataType: string | null;
  groupName: string | null;
  value: string | null;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
}

// Resolve-only: a caller supplies a stable awardNumber and gets back
// the current version's internal awardId, immediately before
// navigating - never stored, never rendered as visible text.
export interface AwardIdentifierV1 {
  awardId: number;
  awardNumber: string;
  sequenceNumber: number | null;
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
  label: string | null;
  name: string | null;
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

export interface NegotiationAttachment {
  attachmentId: number;
  activityId: number | null;
  fileName: string | null;
  contentType: string | null;
  fileSize: number | null;
  checksum: string | null;
  archiveStatus: string;
  sourceUpdateTimestamp: string | null;
  sourceUpdateUser: string | null;
  downloadable: boolean;
}

export interface NegotiationAssociatedRecord {
  associationTypeCode: string | null;
  associationTypeDescription: string | null;
  associatedDocumentId: string | null;
  kind: "AWARD" | "PROPOSAL" | "SUBAWARD" | "NONE" | "UNSUPPORTED";
  navigableId: number | null;
  clickable: boolean;
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
  contactTypeDescription: string | null;
  fullName: string | null;
  organization: string | null;
  email: string | null;
  phone: string | null;
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
  exactLinkedAwardId: number | null;
  awardNumber: string | null;
  awardTitle: string | null;
  awardStatus: string | null;
  awardSponsor: string | null;
  awardAmount: number | null;
  navigableCurrentAwardId: number | null;
  archived: boolean;
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

// --- Archive Explorer (Phase 2, /api/v1/explorer/**) -----------------------
//
// Dedicated Explorer contracts (docs/ARCHIVE_EXPLORER.md) - kept
// independent of the public v1 Award API's DTOs above so either can
// evolve without the other, except where an Explorer response reuses an
// Award Contacts section verbatim (AwardPersonDetailV1/
// AwardUnitDetailsV1/AwardUnitContactV1/AwardSponsorContactV1/
// AwardCentralAdministrationContactV1), matching the backend, which
// reuses those exact DTOs rather than duplicating their shape.

export interface ExplorerAward {
  awardId: number;
  awardNumber: string;
  sequenceNumber: number;
  title: string | null;
  status: string | null;
  principalInvestigator: string | null;
  workflowDocumentNumber: string | null;
  modificationNumber: string | null;
  leadUnitNumber: string | null;
  leadUnitName: string | null;
  primaryCurrent: boolean;
}

// GET /api/v1/explorer/proposals - one row per ACTIVE Proposal version
// family. exactLinkedAwardId is the database relationship's own
// award_id (audit only, never navigated to directly - it may be a
// long-superseded version); navigableCurrentAwardId is that Award
// family's current version, resolved server-side. obligatedAmount/
// anticipatedAmount are null whenever there's no active funded-Award
// relationship, or that Award has no amount snapshot yet.
export interface ExplorerProposalDiscovery {
  proposalId: number;
  proposalNumber: string;
  proposalTitle: string | null;
  workflowDocumentNumber: string | null;
  sponsorName: string | null;
  principalInvestigatorName: string | null;
  attachmentCount: number;
  linkedAwardNumber: string | null;
  navigableCurrentAwardId: number | null;
  awardTitle: string | null;
  obligatedAmount: number | null;
  anticipatedAmount: number | null;
  exactLinkedAwardId: number | null;
}

export interface ExplorerProposalDiscoveryFilters {
  hasAttachments?: boolean;
  hasFundedAward?: boolean;
  minimumAwardAmount?: number;
  sponsorCode?: string;
  sponsorName?: string;
  leadUnitNumber?: string;
  proposalStatus?: string;
  personName?: string;
  proposalType?: string;
  activityType?: string;
  dateFrom?: string;
  dateTo?: string;
  page?: number;
  size?: number;
}

export interface ExplorerUnitAdministrator {
  personId: string | null;
  fullName: string | null;
  administratorTypeCode: string | null;
  administratorTypeDescription: string | null;
  defaultGroupFlag: string | null;
  email: string | null;
  phone: string | null;
}

export interface ExplorerUnit {
  unitNumber: string | null;
  unitName: string | null;
  parentUnitNumber: string | null;
  parentUnitName: string | null;
  organization: string | null;
  administrators: ExplorerUnitAdministrator[];
}

export interface ExplorerAwardContacts {
  award: ExplorerAward;
  keyPersonnel: AwardPersonDetailV1[];
  unitDetails: AwardUnitDetailsV1;
  unitContacts: AwardUnitContactV1[];
  sponsorContacts: AwardSponsorContactV1[];
  centralAdministrationContacts: AwardCentralAdministrationContactV1[];
}

export interface ExplorerPerson {
  personId: string | null;
  firstName: string | null;
  middleName: string | null;
  lastName: string | null;
  fullName: string | null;
  email: string | null;
  phone: string | null;
}

export interface ExplorerRolodex {
  rolodexId: number;
  firstName: string | null;
  lastName: string | null;
  organization: string | null;
  phone: string | null;
  email: string | null;
  city: string | null;
  state: string | null;
  active: boolean | null;
}
