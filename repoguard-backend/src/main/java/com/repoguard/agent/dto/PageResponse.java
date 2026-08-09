package com.repoguard.agent.dto;

import java.util.List;

/**
 * 只读列表接口使用的通用分页响应。
 */
public record PageResponse<T>(
    List<T> items,
    long total,
    String nextCursor,
    boolean hasMore
) {

    public PageResponse(List<T> items, long total) {
        this(items, total, null, false);
    }
}
