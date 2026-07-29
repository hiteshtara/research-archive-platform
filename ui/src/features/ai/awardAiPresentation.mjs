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

export function estimatedReadingSeconds(textParts) {
  const wordCount = textParts
    .join(" ")
    .trim()
    .split(/\s+/)
    .filter(Boolean).length;
  return Math.max(10, Math.ceil((wordCount * 60) / 200 / 5) * 5);
}

export function sequenceLabelFromChange(change) {
  const match = /\bsequence\s+(\d+)\b/i.exec(change);
  return match ? `Sequence ${match[1]}` : null;
}

export function currentAwardFacts(currentRecord, formatAmount) {
  const facts = [];
  const add = (label, value) => {
    if (value !== null && value !== undefined && value !== "") {
      facts.push({ label, value: String(value) });
    }
  };

  add("Status", currentRecord.status);
  add("Current Sequence", currentRecord.sequenceNumber);
  add("Sponsor", currentRecord.sponsor);
  add("Lead Unit", currentRecord.leadUnit);
  if (currentRecord.principalInvestigators.length > 0) {
    add(
      "Principal Investigator(s)",
      currentRecord.principalInvestigators.join(", "),
    );
  }

  const anticipated = currentRecord.anticipatedTotalAmount;
  const obligated = currentRecord.obligatedTotalAmount;
  if (anticipated !== null || obligated !== null) {
    const amounts = [];
    if (anticipated !== null) {
      amounts.push(`${formatAmount(anticipated)} anticipated`);
    }
    if (obligated !== null) {
      amounts.push(`${formatAmount(obligated)} obligated`);
    }
    add("Current Amounts", amounts.join(" · "));
  }

  const beginDate = currentRecord.beginDate;
  const closeoutDate = currentRecord.closeoutDate;
  if (beginDate !== null || closeoutDate !== null) {
    const dates =
      beginDate !== null && closeoutDate !== null
        ? `${beginDate} – ${closeoutDate}`
        : beginDate !== null
          ? `Begins ${beginDate}`
          : `Closeout ${closeoutDate}`;
    add("Dates", dates);
  }

  return facts;
}

export function visibleAwardTimeline(
  orderedTimeline,
  expanded,
  recentCount = 5,
) {
  if (expanded || orderedTimeline.length <= recentCount + 1) {
    return orderedTimeline;
  }

  const earliestSequence = Math.min(
    ...orderedTimeline.map((record) => record.sequenceNumber),
  );
  const visible = orderedTimeline.slice(0, recentCount);
  const visibleIds = new Set(visible.map((record) => record.awardId));
  orderedTimeline
    .filter(
      (record) =>
        record.sequenceNumber === earliestSequence &&
        !visibleIds.has(record.awardId),
    )
    .forEach((record) => visible.push(record));
  return visible;
}
