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

// Takes no arguments by design - see the implementation's note on D7.
export function resolveAssociatedAwardsSectionLabel(): string;
