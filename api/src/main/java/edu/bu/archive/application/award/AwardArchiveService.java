package edu.bu.archive.application.award;

import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class AwardArchiveService {

    private final AwardArchiveRepository repository;

    public AwardArchiveService(
            AwardArchiveRepository repository
    ) {
        this.repository = repository;
    }

    public AwardFamilyResponse findFamily(
            String awardNumber
    ) {
        String normalizedAwardNumber =
                awardNumber == null
                        ? ""
                        : awardNumber.trim();

        if (normalizedAwardNumber.isEmpty()) {
            throw new IllegalArgumentException(
                    "Award number is required"
            );
        }

        List<AwardRowResponse> rows =
                repository.findHistoryRows(
                        normalizedAwardNumber
                );

        if (rows.isEmpty()) {
            throw new NoSuchElementException(
                    "Award not found: "
                            + normalizedAwardNumber
            );
        }

        AwardRowResponse current = rows.stream()
                .filter(row ->
                        Boolean.TRUE.equals(
                                row.primaryCurrent()
                        )
                )
                .findFirst()
                .orElse(rows.getFirst());

        Map<Integer, List<AwardRowResponse>> groupedRows =
                new LinkedHashMap<>();

        for (AwardRowResponse row : rows) {
            groupedRows
                    .computeIfAbsent(
                            row.sequenceNumber(),
                            ignored -> new ArrayList<>()
                    )
                    .add(row);
        }

        List<AwardSequenceResponse> sequences =
                groupedRows.entrySet()
                        .stream()
                        .map(entry ->
                                new AwardSequenceResponse(
                                        entry.getKey(),
                                        entry.getValue()
                                                .stream()
                                                .anyMatch(row ->
                                                        Boolean.TRUE.equals(
                                                                row.current()
                                                        )
                                                ),
                                        List.copyOf(
                                                entry.getValue()
                                        )
                                )
                        )
                        .toList();

        return new AwardFamilyResponse(
                normalizedAwardNumber,
                current,
                sequences
        );
    }
}
