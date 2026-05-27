package com.vionix.backend.common.jdbc;

public final class PageSupport {
    private PageSupport() {
    }

    public static int pageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    public static int pageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }

    public static int offset(int pageNum, int pageSize) {
        return (pageNum - 1) * pageSize;
    }
}
