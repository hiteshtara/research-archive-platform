package edu.bu.archive.adapter.in.web.dto;

public final class PaginationSupport {

    private static final int DEFAULT_MAX_SIZE = 100;

    private PaginationSupport() {
    }

    public static int clampPage(int page) {
        return Math.max(page, 0);
    }

    public static int clampSize(int size) {
        return Math.min(Math.max(size, 1), DEFAULT_MAX_SIZE);
    }

    public static PageMetadata metadata(
            int page,
            int size,
            long totalElements
    ) {
        int totalPages = totalElements == 0
                ? 0
                : (int) Math.ceil((double) totalElements / size);

        return new PageMetadata(
                totalPages,
                page == 0,
                totalPages == 0 || page >= totalPages - 1
        );
    }

    public record PageMetadata(
            int totalPages,
            boolean first,
            boolean last
    ) {
    }
}
