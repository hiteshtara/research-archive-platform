import type { StatusDomain } from "../../components/common/StatusPill";
import type { AwardAssociatedNegotiation, NegotiationAssociatedRecord } from "../../types/api";

export const RELATIONSHIP_ACTION_LABEL: Record<StatusDomain, string>;

export function resolveRelationshipCardState(navigableId: number | null | undefined): {
  archived: boolean;
};

export function resolveNegotiationAssociationArchived(
  association: NegotiationAssociatedRecord | null | undefined,
): boolean;

export function resolveNegotiationCardTitle(
  negotiation: AwardAssociatedNegotiation,
): string;

export function resolveNegotiationAssociationDisplayKind(
  association: NegotiationAssociatedRecord | null | undefined,
): "none" | "unsupported" | "card";

export function resolveFundingProposalCardTitle(link: {
  proposalNumber: string | null;
  exactLinkedProposalId: number | null;
}): string;

export function resolveFundingProposalCardSubtitle(link: {
  proposalNumber: string | null;
  proposalTitle: string | null;
}): string;

export function buildFundingProposalMetaText(link: {
  principalInvestigatorName: string | null;
  sponsorName: string | null;
  requestedTotalCost: number | null;
}): string;

export function buildFundedAwardMetaText(fundedAward: {
  currentAwardVersion: number | null;
  linkedAwardVersion: number | null;
  proposalVersion: number | null;
}): string;

export interface FundedAwardGroupable {
  awardNumber: string;
  awardTitle: string | null;
  awardStatus: string | null;
  proposalVersion: number | null;
  linkedAwardVersion: number | null;
  currentAwardVersion: number | null;
  relationshipActive: boolean;
  exactLinkedAwardId: number | null;
  navigableCurrentAwardId: number | null;
  sourceRelationshipId: number | null;
}

export interface GroupedFundedAward extends FundedAwardGroupable {
  sourceRelationshipIds: (number | null)[];
  sourceCount: number;
}

export function groupFundedAwardsBySourceRelationship(
  fundedAwards: FundedAwardGroupable[],
): GroupedFundedAward[];

export function fundedAwardSourceCountLabel(
  sourceCount: number,
): string | null;
