import type { SubawardFunding } from "../../types/api";

export type AssociatedAwardCardState =
  | { kind: "NONE" }
  | { kind: "NOT_ARCHIVED"; awardNumber: string | null }
  | {
      kind: "LOADED";
      awardNumber: string | null;
      awardTitle: string | null;
      awardStatus: string | null;
      awardSponsor: string | null;
      awardAmount: number | null;
      navigableCurrentAwardId: number;
    };

export function resolveAssociatedAwardCardState(
  funding: SubawardFunding,
): AssociatedAwardCardState;

export function resolveFundingAssociationCards(
  fundingRows: SubawardFunding[],
): { subawardFundingSourceId: number; card: AssociatedAwardCardState }[];

export function resolveAssociatedAwardsSectionLabel(
  fundingRows: SubawardFunding[],
): string;
