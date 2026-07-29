package edu.bu.archive.application.ai;

import edu.bu.archive.domain.model.ai.AiCitation;
import edu.bu.archive.domain.model.ai.AwardAiContext;
import edu.bu.archive.domain.model.ai.AwardAiContextChanges;
import edu.bu.archive.domain.model.ai.AwardAiContextRecord;
import edu.bu.archive.domain.model.ai.AwardQuestionSupport;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.ai.questions-enabled",
        havingValue = "true"
)
public class AwardSequenceDiffBuilder {

    List<AwardQuestionSupport> compare(
            AwardAiContext context,
            int firstSequence,
            int secondSequence
    ) {
        Map<Integer, List<SnapshotRecord>> snapshots =
                snapshotsBySequence(context);
        List<SnapshotRecord> first = snapshots.get(firstSequence);
        List<SnapshotRecord> second = snapshots.get(secondSequence);
        if (first == null || second == null) {
            return List.of();
        }
        return compare(
                context.awardNumber(),
                firstSequence,
                first,
                secondSequence,
                second
        );
    }

    List<AwardQuestionSupport> history(
            AwardAiContext context
    ) {
        Map<Integer, List<SnapshotRecord>> snapshots =
                snapshotsBySequence(context);
        List<Integer> sequences = snapshots.keySet()
                .stream()
                .sorted()
                .toList();
        ArrayList<AwardQuestionSupport> supports =
                new ArrayList<>();
        for (int index = 1; index < sequences.size(); index++) {
            int first = sequences.get(index - 1);
            int second = sequences.get(index);
            supports.addAll(compare(
                    context.awardNumber(),
                    first,
                    snapshots.get(first),
                    second,
                    snapshots.get(second)
            ));
        }
        return List.copyOf(supports);
    }

    List<Integer> sequences(
            AwardAiContext context
    ) {
        return snapshotsBySequence(context).keySet()
                .stream()
                .sorted()
                .toList();
    }

    private List<AwardQuestionSupport> compare(
            String awardNumber,
            int firstSequence,
            List<SnapshotRecord> first,
            int secondSequence,
            List<SnapshotRecord> second
    ) {
        ArrayList<AwardQuestionSupport> supports =
                new ArrayList<>();
        addChange(
                supports,
                "title",
                "title",
                firstSequence,
                first,
                secondSequence,
                second,
                snapshot -> snapshot.title(),
                awardNumber
        );
        addChange(
                supports,
                "status",
                "status",
                firstSequence,
                first,
                secondSequence,
                second,
                snapshot -> snapshot.status(),
                awardNumber
        );
        addChange(
                supports,
                "awardSequenceStatus",
                "award sequence status",
                firstSequence,
                first,
                secondSequence,
                second,
                snapshot -> snapshot.awardSequenceStatus(),
                awardNumber
        );
        addChange(
                supports,
                "sponsor",
                "sponsor",
                firstSequence,
                first,
                secondSequence,
                second,
                snapshot -> snapshot.sponsor(),
                awardNumber
        );
        addChange(
                supports,
                "primeSponsor",
                "prime sponsor",
                firstSequence,
                first,
                secondSequence,
                second,
                snapshot -> snapshot.primeSponsor(),
                awardNumber
        );
        addChange(
                supports,
                "leadUnit",
                "lead unit",
                firstSequence,
                first,
                secondSequence,
                second,
                snapshot -> snapshot.leadUnit(),
                awardNumber
        );
        addChange(
                supports,
                "beginDate",
                "begin date",
                firstSequence,
                first,
                secondSequence,
                second,
                snapshot -> snapshot.beginDate(),
                awardNumber
        );
        addChange(
                supports,
                "closeoutDate",
                "closeout date",
                firstSequence,
                first,
                secondSequence,
                second,
                snapshot -> snapshot.closeoutDate(),
                awardNumber
        );
        return List.copyOf(supports);
    }

    private <T> void addChange(
            List<AwardQuestionSupport> supports,
            String field,
            String label,
            int firstSequence,
            List<SnapshotRecord> first,
            int secondSequence,
            List<SnapshotRecord> second,
            Function<Snapshot, T> getter,
            String awardNumber
    ) {
        List<String> firstValues = values(first, getter);
        List<String> secondValues = values(second, getter);
        if (firstValues.equals(secondValues)) {
            return;
        }
        List<AiCitation> citations = citations(
                awardNumber,
                first,
                second
        );
        supports.add(new AwardQuestionSupport(
                field + ":sequence-" + firstSequence
                        + ":sequence-" + secondSequence,
                "Between sequence " + firstSequence
                        + " and sequence " + secondSequence
                        + ", the archived " + label
                        + " changed from "
                        + display(firstValues)
                        + " to "
                        + display(secondValues)
                        + ".",
                citations
        ));
    }

    private <T> List<String> values(
            List<SnapshotRecord> records,
            Function<Snapshot, T> getter
    ) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        records.stream()
                .map(SnapshotRecord::snapshot)
                .map(getter)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .sorted()
                .forEach(values::add);
        return List.copyOf(values);
    }

    private String display(
            List<String> values
    ) {
        return values.isEmpty()
                ? "not recorded"
                : String.join(" / ", values);
    }

    private List<AiCitation> citations(
            String awardNumber,
            List<SnapshotRecord> first,
            List<SnapshotRecord> second
    ) {
        return java.util.stream.Stream.concat(
                        first.stream(),
                        second.stream()
                )
                .sorted(Comparator.comparing(
                        record -> record.record().awardId()
                ))
                .map(record -> new AiCitation(
                        "award",
                        String.valueOf(record.record().awardId()),
                        awardNumber,
                        record.record().sequenceNumber()
                ))
                .toList();
    }

    private Map<Integer, List<SnapshotRecord>> snapshotsBySequence(
            AwardAiContext context
    ) {
        LinkedHashMap<Integer, List<SnapshotRecord>> result =
                new LinkedHashMap<>();
        Snapshot current = new Snapshot(
                null, null, null, null, null, null, null, null
        );
        for (AwardAiContextRecord record : context.records()) {
            current = apply(current, record);
            result.computeIfAbsent(
                            record.sequenceNumber(),
                            ignored -> new ArrayList<>()
                    )
                    .add(new SnapshotRecord(record, current));
        }
        return result;
    }

    private Snapshot apply(
            Snapshot previous,
            AwardAiContextRecord record
    ) {
        AwardAiContextChanges changes = record.changes();
        List<String> cleared = record.clearedFields() == null
                ? List.of()
                : record.clearedFields();
        return new Snapshot(
                value(
                        "title", previous.title(),
                        changes == null ? null : changes.title(),
                        cleared
                ),
                value(
                        "status", previous.status(),
                        changes == null ? null : changes.status(),
                        cleared
                ),
                value(
                        "awardSequenceStatus",
                        previous.awardSequenceStatus(),
                        changes == null
                                ? null
                                : changes.awardSequenceStatus(),
                        cleared
                ),
                value(
                        "sponsor", previous.sponsor(),
                        changes == null ? null : changes.sponsor(),
                        cleared
                ),
                value(
                        "primeSponsor", previous.primeSponsor(),
                        changes == null ? null : changes.primeSponsor(),
                        cleared
                ),
                value(
                        "leadUnit", previous.leadUnit(),
                        changes == null ? null : changes.leadUnit(),
                        cleared
                ),
                value(
                        "beginDate", previous.beginDate(),
                        changes == null ? null : changes.beginDate(),
                        cleared
                ),
                value(
                        "closeoutDate", previous.closeoutDate(),
                        changes == null ? null : changes.closeoutDate(),
                        cleared
                )
        );
    }

    private <T> T value(
            String field,
            T previous,
            T changed,
            List<String> cleared
    ) {
        if (cleared.contains(field)) {
            return null;
        }
        return changed == null ? previous : changed;
    }

    private record SnapshotRecord(
            AwardAiContextRecord record,
            Snapshot snapshot
    ) {
    }

    private record Snapshot(
            String title,
            String status,
            String awardSequenceStatus,
            String sponsor,
            String primeSponsor,
            String leadUnit,
            LocalDate beginDate,
            LocalDate closeoutDate
    ) {
    }
}
