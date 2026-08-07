package edu.bu.archive.adapter.out.persistence;

public record SemanticSearchRow(
        String module,
        long recordId,
        long canonicalFamilyId,
        String businessNumber,
        double distance
) {
}
