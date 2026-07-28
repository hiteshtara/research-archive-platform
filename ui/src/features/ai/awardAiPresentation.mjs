export function orderAwardTimeline(timeline) {
  return [...timeline].sort(
    (left, right) =>
      right.sequenceNumber - left.sequenceNumber ||
      right.awardId - left.awardId,
  );
}

export function timelineLabel(
  sequenceNumber,
  currentSequence,
  earliestSequence,
) {
  if (sequenceNumber === currentSequence) {
    return "Current";
  }
  return sequenceNumber === earliestSequence ? "Original" : "Previous";
}

export function showDevelopmentMetadata(isDevelopment) {
  return isDevelopment === true;
}
