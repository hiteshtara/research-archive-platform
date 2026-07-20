package edu.bu.archive.application.award;

import edu.bu.archive.adapter.in.web.dto.award.AwardFamilyResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardRowResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceDetailResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequencePageResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSequenceSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardWorkspaceResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardUnitContactResponse;
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

    public AwardWorkspaceResponse findWorkspace(
            String awardNumber
    ) {
        String normalizedAwardNumber =
                normalizeAwardNumber(awardNumber);

        AwardRowResponse current =
                repository.findCurrent(normalizedAwardNumber)
                        .orElseThrow(() ->
                                new NoSuchElementException(
                                        "Award not found: "
                                                + normalizedAwardNumber
                                )
                        );

        return new AwardWorkspaceResponse(
                normalizedAwardNumber,
                current
        );
    }

    public AwardSequencePageResponse findSequencePage(
            String awardNumber,
            int page,
            int size
    ) {
        String normalizedAwardNumber =
                normalizeAwardNumber(awardNumber);

        if (repository.findCurrent(normalizedAwardNumber).isEmpty()) {
            throw new NoSuchElementException(
                    "Award not found: "
                            + normalizedAwardNumber
            );
        }

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(
                Math.max(size, 1),
                100
        );

        long totalElements =
                repository.countSequences(
                        normalizedAwardNumber
                );

        int totalPages =
                totalElements == 0
                        ? 0
                        : (int) Math.ceil(
                                (double) totalElements
                                        / safeSize
                        );

        int offset = safePage * safeSize;

        List<AwardSequenceSummaryResponse> content =
                repository.findSequenceSummaries(
                        normalizedAwardNumber,
                        safeSize,
                        offset
                );

        return new AwardSequencePageResponse(
                content,
                safePage,
                safeSize,
                totalElements,
                totalPages,
                safePage == 0,
                totalPages == 0
                        || safePage >= totalPages - 1
        );
    }

    public AwardSequenceDetailResponse findSequence(
            String awardNumber,
            int sequenceNumber
    ) {
        String normalizedAwardNumber =
                normalizeAwardNumber(awardNumber);

        List<AwardRowResponse> rows =
                repository.findSequenceRows(
                        normalizedAwardNumber,
                        sequenceNumber
                );

        if (rows.isEmpty()) {
            throw new NoSuchElementException(
                    "Award sequence not found: "
                            + normalizedAwardNumber
                            + ", sequence "
                            + sequenceNumber
            );
        }

        boolean currentSequence =
                rows.stream()
                        .anyMatch(row ->
                                Boolean.TRUE.equals(
                                        row.current()
                                )
                        );

        return new AwardSequenceDetailResponse(
                normalizedAwardNumber,
                sequenceNumber,
                currentSequence,
                rows
        );
    }

    /*
     * Existing proof-of-concept response.
     * Keep temporarily until the React history tab uses pagination.
     */
    public AwardFamilyResponse findFamily(
            String awardNumber
    ) {
        String normalizedAwardNumber =
                normalizeAwardNumber(awardNumber);

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


    public List<
            edu.bu.archive.adapter.in.web.dto.award.AwardPersonResponse
            > findCurrentPeople(
                    String awardNumber
            ) {
        String normalizedAwardNumber =
                normalizeAwardNumber(awardNumber);

        if (repository.findCurrent(normalizedAwardNumber).isEmpty()) {
            throw new NoSuchElementException(
                    "Award not found: "
                            + normalizedAwardNumber
            );
        }

        return repository.findCurrentPeople(
                normalizedAwardNumber
        );
    }


    public List<AwardUnitContactResponse>
            findCurrentUnitContacts(
                    String awardNumber
            ) {
        String normalizedAwardNumber =
                normalizeAwardNumber(awardNumber);

        if (repository.findCurrent(
                normalizedAwardNumber
        ).isEmpty()) {
            throw new NoSuchElementException(
                    "Award not found: "
                            + normalizedAwardNumber
            );
        }

        return repository.findCurrentUnitContacts(
                normalizedAwardNumber
        );
    }

    private String normalizeAwardNumber(
            String awardNumber
    ) {
        String normalized =
                awardNumber == null
                        ? ""
                        : awardNumber.trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Award number is required"
            );
        }

        return normalized;
    }
}
