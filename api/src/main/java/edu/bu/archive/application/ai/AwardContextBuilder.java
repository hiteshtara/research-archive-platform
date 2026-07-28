package edu.bu.archive.application.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
import edu.bu.archive.config.AiProperties;
import edu.bu.archive.domain.model.ai.AwardAiContext;
import edu.bu.archive.domain.model.ai.AwardAiContextRecord;

import java.util.ArrayList;
import java.util.List;

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
                .toList();

        validateLimits();

        List<AwardAiContextRecord> records = new ArrayList<>();
        boolean truncated = false;

        for (AwardRowResponse row : rows) {
            if (records.size() >= properties.getMaxRecords()) {
                truncated = true;
                break;
            }

            AwardAiContextRecord approved = approvedRecord(row);
            List<AwardAiContextRecord> candidate =
                    new ArrayList<>(records);
            candidate.add(approved);

            AwardAiContext candidateContext =
                    new AwardAiContext(
                            awardFamily.awardNumber(),
                            candidate,
                            rows.size() > candidate.size()
                    );

            if (serializedLength(candidateContext)
                    > properties.getMaxSerializedContextChars()) {
                truncated = true;
                break;
            }

            records.add(approved);
        }

        return new AwardAiContext(
                awardFamily.awardNumber(),
                records,
                truncated || records.size() < rows.size()
        );
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

    private AwardAiContextRecord approvedRecord(
            AwardRowResponse row
    ) {
        return new AwardAiContextRecord(
                row.awardId(),
                row.awardNumber(),
                row.sequenceNumber(),
                row.current(),
                row.primaryCurrent(),
                redactor.redact(row.title()),
                redactor.redact(row.status()),
                redactor.redact(row.awardSequenceStatus()),
                redactor.redact(row.sponsor()),
                redactor.redact(row.primeSponsor()),
                redactor.redact(row.leadUnit()),
                row.beginDate(),
                row.closeoutDate()
        );
    }
}
