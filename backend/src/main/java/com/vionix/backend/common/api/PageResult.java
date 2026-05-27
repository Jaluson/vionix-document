package com.vionix.backend.common.api;

import java.util.List;

public record PageResult<T>(
        List<T> items,
        int pageNum,
        int pageSize,
        long total
) {
}
