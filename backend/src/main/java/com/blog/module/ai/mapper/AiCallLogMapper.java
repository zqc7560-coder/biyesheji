package com.blog.module.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.module.ai.entity.AiCallLog;

/** AI 调用日志 Mapper（埋点写在 M6/M7 调用管道；分页查询+成本统计在 M7/仪表盘）。 */
public interface AiCallLogMapper extends BaseMapper<AiCallLog> {
}
