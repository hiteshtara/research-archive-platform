package edu.bu.archive.domain.model;

public record IrbSearchQuery(
        String query,
        String status,
        String type,
        int page,
        int size,
        String sort,
        String direction
) {
    public IrbSearchQuery {
        if (page < 0) {
            throw new IllegalArgumentException("Page cannot be negative.");
        }

        if (size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "Page size must be between 1 and 100."
            );
        }

        sort = normalizeSort(sort);
        direction = normalizeDirection(direction);
    }

    private static String normalizeSort(String value) {
        if (value == null || value.isBlank()) {
            return "approvalDate";
        }

        return value.trim();
    }

    private static String normalizeDirection(String value) {
        return "asc".equalsIgnoreCase(value) ? "asc" : "desc";
    }
}
