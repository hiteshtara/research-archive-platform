import type { AwardAiTimelineRecord } from "../../types/api";

export function orderAwardTimeline(
  timeline: AwardAiTimelineRecord[],
): AwardAiTimelineRecord[];

export function timelineLabel(
  sequenceNumber: number,
  currentSequence: number,
  earliestSequence: number,
): "Current" | "Previous" | "Original";

export function showDevelopmentMetadata(isDevelopment: boolean): boolean;
