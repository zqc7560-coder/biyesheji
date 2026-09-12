package com.blog;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 个人博客系统后端启动类（M0 工程骨架）。
 *
 * <p>包结构约定（见《系统设计.md》§1 分层职责）：
 * <pre>
 * com.blog
 * ├── common    统一响应体 Result、错误码 BizCode、全局异常、工具
 * ├── config    MyBatis-Plus / Redis / OpenAPI / Web 等横切配置
 * ├── security  （M2 填充）JwtAuthFilter、JwtTokenProvider
 * ├── module    auth / user / article / category / tag / comment / storage / system / ai
 * └── job       （M9 填充）浏览量回写、成本聚合定时任务
 * </pre>
 *
 * <p>@MapperScan 扫描 module.*.mapper：每个业务模块自带 mapper 子包，
 * 模块间只能通过 Service 互相调用，不允许跨模块直接用别人的 Mapper——
 * 这条规则是为了毕业答辩时模块边界清晰，日后拆分微服务成本低。
 */
@SpringBootApplication
@MapperScan("com.blog.module.**.mapper")
@EnableScheduling   // M9：PV 回写任务（job/PvSyncJob）与 AI 成本聚合（未来）的调度开关
public class BlogServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlogServerApplication.class, args);
    }
}
