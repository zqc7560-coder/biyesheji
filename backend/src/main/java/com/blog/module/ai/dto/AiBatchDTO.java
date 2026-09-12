package com.blog.module.ai.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量补全请求 DTO（POST /api/ai/batch）。
 *
 * <p>为什么用显式 DTO 而不是 Map&lt;String,List&lt;Long&gt;&gt; 松绑定：
 * 类型擦除下 Map 方案的数字元素会被 Jackson 解成 Integer，
 * 后续按 Long 消费时埋着 ClassCastException（泛型"看起来对"的坑只有运行期才炸）；
 * DTO 的目标类型完整可反射，元素精确绑定为 Long，校验注解也有了挂靠点。
 */
@Data
public class AiBatchDTO {

    /**
     * 指定补全的文章 id 列表；空/缺省=服务端自动圈定
     * "已发布且缺摘要或缺标签"的前 20 篇（圈定策略见 AiBatchService.resolveCandidates）。
     * size 上限给到 100 是"把 20 的限制留给 Service 报业务文案"，这里只挡明显离谱的输入。
     */
    @Size(max = 100, message = "articleIds 单批不能超过 100")
    private List<Long> articleIds;
}
