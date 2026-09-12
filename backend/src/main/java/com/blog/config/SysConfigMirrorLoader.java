package com.blog.config;

import com.blog.module.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * sys:config 镜像启动加载器（M9）——《数据库设计.md》§4："启动时全量加载进 Redis sys:config 哈希"。
 *
 * <p>为什么要启动预灌而不是纯懒加载：首个访客（往往是评审/答辩现场的第一个人）
 * 会替全栈付一次"冷启动回源+回填"的代价，且懒加载路径在 SysConfigService.publicConfig
 * 里已经有兜底，本类的价值是让"首访延迟"从统计意义上消失而不是存在。
 *
 * <p>失败不致命：Redis 未就绪/网络抖动时只记 warn——读侧的 miss→懒回填是完整
 * 等价路径，镜像体系对启动顺序零依赖（这是与 AdminBootstrapRunner 的关键差异：
 * 那个 Runner 失败必须炸，因为引导不出管理员整个后台不可用；镜像是可降级加速器）。
 *
 * <p>测试语境：@SpringBootTest 不走 SpringApplication.run，ApplicationRunner 不会
 * 被容器自动执行——镜像在测试里由 SysConfigApiM9Test 显式调用 reloadMirror() 驱动，
 * 行为可断言（沿用 M2 对 AdminBootstrapRunner 的显式驱动先例）。
 */
@Slf4j
@Component
@Order(20)   // 显式编号：晚于默认顺序的管理员引导（AdminBootstrapRunner），不依赖隐式次序
@RequiredArgsConstructor
public class SysConfigMirrorLoader implements ApplicationRunner {

    private final SysConfigService sysConfigService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            long n = sysConfigService.reloadMirror();
            log.info("sys:config 镜像启动加载完成: {} 行", n);
        } catch (Exception e) {
            log.warn("sys:config 镜像启动加载失败（读侧将懒回填，功能不受影响）: {}", e.getMessage());
        }
    }
}
