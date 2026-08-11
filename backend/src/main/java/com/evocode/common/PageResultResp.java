package com.evocode.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * 通用分页响应（docs/06-API契约.md §1）：{total, page, size, items}。
 * 所有分页接口统一由此包装，避免直接暴露 MyBatis-Plus IPage 的
 * {records, total, size, current, pages} 结构。
 */
public record PageResultResp<T>(long total, long page, long size, List<T> items) {

    public static <R> PageResultResp<R> of(IPage<R> p) {
        return new PageResultResp<>(p.getTotal(), p.getCurrent(), p.getSize(), p.getRecords());
    }
}
