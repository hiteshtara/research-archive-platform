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

    /**
     * One page of an already-filtered, fully materialized list (record
     * authorization filters rows in memory, then pages - so the page and
     * totalElements describe only what the caller may see).
     */
    public static <T> PageResponse<T> pageOf(java.util.List<T> rows, int page, int size) {
        int from = (int) Math.min((long) page * size, rows.size());
        int to = Math.min(from + size, rows.size());
        PageMetadata meta = metadata(page, size, rows.size());
        return new PageResponse<>(java.util.List.copyOf(rows.subList(from, to)), page, size,
                rows.size(), meta.totalPages(), meta.first(), meta.last());
    }

    public record PageMetadata(
            int totalPages,
            boolean first,
            boolean last
    ) {
    }
}
