package com.blog.common;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 统一分页载荷 —— 结构对齐《API接口设计.md》§1.2。
 *
 * <p>为什么不直接把 MyBatis-Plus 的 IPage 透传给前端：
 * IPage 字段多且含 records/orders 等内部概念，契约一旦泄露，日后换 ORM 就是破坏性变更。
 * 这里做一层防腐：只暴露 total/pages/pageNum/pageSize/list 五个稳定字段。
 */
@Data
@AllArgsConstructor
public class PageResult<T> {

    /** 总记录数 */
    private long total;

    /** 总页数（前端分页条直接可用） */
    private long pages;

    /** 当前页码（1 起） */
    private long pageNum;

    /** 每页条数（服务端已钳制在 1~100，见 MyBatisPlusConfig 注释） */
    private long pageSize;

    /** 当页数据 */
    private List<T> list;

    /** 从 MyBatis-Plus IPage 转换（唯一允许感知 ORM 类型的地方） */
    public static <T> PageResult<T> of(com.baomidou.mybatisplus.core.metadata.IPage<T> page) {
        return new PageResult<>(page.getTotal(), page.getPages(),
                page.getCurrent(), page.getSize(), page.getRecords());
    }
}
