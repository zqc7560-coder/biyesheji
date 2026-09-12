package com.blog.module.system.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 仪表盘聚合载荷（《API接口设计.md》§8 GET /api/admin/dashboard）。
 *
 * <p>字段分两组：counts 是全站现状快照，trend 是近 N 日时序。刻意用嵌套 record
 * 而不是把 20 个平铺字段塞进一个 VO：前端看板"指标卡区 + 图表区"的渲染边界
 * 正好对应这个结构，字段增删不冲击另一区。
 */
@Data
@Builder
public class DashboardVo {

    /** 全站计数快照 */
    private Counts counts;

    /** 近 7 日 AI 调用趋势（真实数据源：ai_call_log 逐日聚合） */
    private List<DayPoint> aiTrend;

    /** 近 7 日浏览量趋势：本期恒为 null 序列（诚实占位，见 DashboardService 注释） */
    private List<DayPoint> pvTrend;

    /** 浏览量当前累计总值（article.view_count 求和，口径=未删除全部文章） */
    private Long totalPv;

    /** 数据口径说明：前端在图区直接展示，避免答辩现场"这折线是什么"答不上来 */
    private String note;

    /** 计数块 */
    @Data
    @Builder
    public static class Counts {
        /** 文章总数（未删除，含草稿——管理员治理视角） */
        private Long articleTotal;
        /** 已发布文章数 */
        private Long articlePublished;
        /** 用户总数（未删除） */
        private Long userTotal;
        /** 正常状态用户数（status=1） */
        private Long userActive;
        /** 评论总数（未删除，含待审/屏蔽——治理视角） */
        private Long commentTotal;
        /** 待审核评论数（status=0：管理员今日待办） */
        private Long commentPending;
        /** 近 7 日 AI 调用总次数（含缓存命中与失败，全口径账本） */
        private Long aiCalls7d;
        /** 近 7 日 AI 预估成本合计（元，cost_est 求和；单价来自 sys_config） */
        private BigDecimal aiCost7d;
    }

    /** 趋势点：{日期, 数值}；value 用 Long 统一计数与金额取整两种口径 */
    @Data
    @Builder
    public static class DayPoint {
        private String date;
        private Long value;
    }
}
