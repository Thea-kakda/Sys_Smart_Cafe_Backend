package com.smartcoffee.common.api;

import java.util.List;

public record PaginatedResponse<T>(List<T> items, Pagination pagination) {
    public record Pagination(int page, int size, long totalElements, int totalPages, boolean first, boolean last) {
    }
}

