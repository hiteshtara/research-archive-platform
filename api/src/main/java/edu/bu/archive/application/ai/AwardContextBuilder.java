package edu.bu.archive.application.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
import edu.bu.archive.config.AiProperties;
import edu.bu.archive.domain.model.ai.AwardAiContext;
import edu.bu.archive.domain.model.ai.AwardAiContextChanges;
import edu.bu.archive.domain.model.ai.AwardAiContextRecord;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class AwardContextBuilder {

    private final SensitiveFieldRedactor redactor;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    public AwardContextBuilder(
            SensitiveFieldRedactor redactor,
            ObjectMapper objectMapper,
            AiProperties properties
    ) {
        this.redactor = redactor;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public AwardAiContext build(
            AwardFamilyResponse awardFamily
    ) {
        if (awardFamily == null || awardFamily.sequences() == null) {
            throw new IllegalArgumentException(
                    "Award history is required"
            );
        }

        List<AwardRowResponse> rows = awardFamily.sequences()
                .stream()
                .flatMap(sequence -> sequence.rows().stream())
                .sorted(Comparator
                        .comparing(
                                AwardRowResponse::sequenceNumber,
                                Comparator.nullsLast(
                                        Comparator.naturalOrder()
                                )
                        )
                        .thenComparing(
                                AwardRowResponse::awardId,
                                Comparator.nullsLast(
                                        Comparator.naturalOrder()
                                )
                        )
                )
                .toList();

        validateLimits();

        int firstRetained = Math.max(
                0,
                rows.size() - properties.getMaxRecords()
        );
        List<AwardRowResponse> retained =
                rows.subList(firstRetained, rows.size());

        while (true) {
            boolean truncated = retained.size() < rows.size();
            AwardAiContext candidate = context(
                    awardFamily,
                    retained,
                    truncated
            );
            if (serializedLength(candidate)
                    <= properties.getMaxSerializedContextChars()) {
                return candidate;
            }
            if (retained.isEmpty()) {
                return candidate;
            }
            retained = retained.subList(1, retained.size());
        }
    }

    int serializedLength(
            AwardAiContext context
    ) {
        try {
            return objectMapper.writeValueAsString(context).length();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize approved AI context",
                    exception
            );
        }
    }

    private void validateLimits() {
        if (properties.getMaxRecords() < 1
                || properties.getMaxSerializedContextChars() < 1) {
            throw new IllegalStateException(
                    "AI context limits must be positive"
            );
        }
    }

    private AwardAiContext context(
            AwardFamilyResponse family,
            List<AwardRowResponse> rows,
            boolean truncated
    ) {
        Snapshot previous = null;
        ArrayList<AwardAiContextRecord> records =
                new ArrayList<>();
        for (AwardRowResponse row : rows) {
            Snapshot current = snapshot(row);
            records.add(compactRecord(
                    row,
                    current,
                    previous
            ));
            previous = current;
        }
        return new AwardAiContext(
                family.awardNumber(),
                family.current().awardId(),
                records,
                truncated
        );
    }

    private AwardAiContextRecord compactRecord(
            AwardRowResponse row,
            Snapshot current,
            Snapshot previous
    ) {
        AwardAiContextChanges changes =
                new AwardAiContextChanges(
                        changed(current.title(), value(
                                previous, Snapshot::title
                        )),
                        changed(current.status(), value(
                                previous, Snapshot::status
                        )),
                        changed(
                                current.awardSequenceStatus(),
                                value(
                                        previous,
                                        Snapshot::awardSequenceStatus
                                )
                        ),
                        changed(current.sponsor(), value(
                                previous, Snapshot::sponsor
                        )),
                        changed(current.primeSponsor(), value(
                                previous, Snapshot::primeSponsor
                        )),
                        changed(current.leadUnit(), value(
                                previous, Snapshot::leadUnit
                        )),
                        changed(current.beginDate(), value(
                                previous, Snapshot::beginDate
                        )),
                        changed(current.closeoutDate(), value(
                                previous, Snapshot::closeoutDate
                        ))
                );
        List<String> clearedFields = clearedFields(
                current,
                previous
        );
        return new AwardAiContextRecord(
                row.awardId(),
                row.sequenceNumber(),
                isEmpty(changes) ? null : changes,
                clearedFields.isEmpty() ? null : clearedFields
        );
    }

    private Snapshot snapshot(
            AwardRowResponse row
    ) {
        return new Snapshot(
                approvedText(row.title()),
                approvedText(row.status()),
                approvedText(row.awardSequenceStatus()),
                approvedText(row.sponsor()),
                approvedText(row.primeSponsor()),
                approvedText(row.leadUnit()),
                row.beginDate(),
                row.closeoutDate()
        );
    }

    private String approvedText(
            String value
    ) {
        String approved = redactor.redact(value);
        return approved == null || approved.isBlank()
                ? null
                : approved;
    }

    private <T> T changed(
            T current,
            T previous
    ) {
        return Objects.equals(current, previous)
                ? null
                : current;
    }

    private <T> T value(
            Snapshot snapshot,
            Function<Snapshot, T> getter
    ) {
        return snapshot == null
                ? null
                : getter.apply(snapshot);
    }

    private List<String> clearedFields(
            Snapshot current,
            Snapshot previous
    ) {
        if (previous == null) {
            return List.of();
        }
        ArrayList<String> fields =
                new ArrayList<>();
        cleared(fields, "title", current.title(), previous.title());
        cleared(fields, "status", current.status(), previous.status());
        cleared(
                fields,
                "awardSequenceStatus",
                current.awardSequenceStatus(),
                previous.awardSequenceStatus()
        );
        cleared(
                fields,
                "sponsor",
                current.sponsor(),
                previous.sponsor()
        );
        cleared(
                fields,
                "primeSponsor",
                current.primeSponsor(),
                previous.primeSponsor()
        );
        cleared(
                fields,
                "leadUnit",
                current.leadUnit(),
                previous.leadUnit()
        );
        cleared(
                fields,
                "beginDate",
                current.beginDate(),
                previous.beginDate()
        );
        cleared(
                fields,
                "closeoutDate",
                current.closeoutDate(),
                previous.closeoutDate()
        );
        return List.copyOf(fields);
    }

    private void cleared(
            List<String> fields,
            String field,
            Object current,
            Object previous
    ) {
        if (current == null && previous != null) {
            fields.add(field);
        }
    }

    private boolean isEmpty(
            AwardAiContextChanges changes
    ) {
        return changes.title() == null
                && changes.status() == null
                && changes.awardSequenceStatus() == null
                && changes.sponsor() == null
                && changes.primeSponsor() == null
                && changes.leadUnit() == null
                && changes.beginDate() == null
                && changes.closeoutDate() == null;
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
