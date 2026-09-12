package com.blog.module.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 专用线程池（《AI模块设计.md》§7）。
 *
 * <p>为什么必须独立线程池：大模型单次调用 5~30s，若占用 Tomcat http-nio 线程
 * （默认 100~200），几个并发 AI 调用就能把文章/评论等主接口饿死。
 * ai-* 池把慢调用从 Web 线程卸载出去，AbortPolicy 拒绝策略在队列（100）打满时
 * 快速失败 → 上层转 4002 降级，绝不无限堆积拖垮内存（§3.4 并发维度）。
 *
 * <p>参数（core=8/max=16/queue=100）与设计文档 §7 逐字一致；keepAlive 60s
 * 让突发过后的空闲线程归还内存。线程名前缀 "ai-" 方便 thread dump 里
 * 向答辩老师证明"/api/ai 不占 http-nio 线程"（项目计划 D-3 的验证手段）。
 */
@Configuration
public class AiAsyncConfig {

    @Bean("aiExecutor")
    public ThreadPoolTaskExecutor aiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("ai-");
        // AbortPolicy：队满抛 RejectedExecutionException，Controller 捕获转 4002。
        // 不用 CallerRunsPolicy——那会把慢调用拉回 Tomcat 线程执行，卸载意义归零。
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
