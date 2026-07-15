package edu.bu.archive.domain.model;

import java.util.List;

public record PageResult<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public PageResult {
        content = List.copyOf(content);
    }
}
