package com.blog.support;

import com.blog.module.article.service.ArticleCacheService;
import com.blog.module.system.service.SysConfigService;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 测试缓存卫生守卫（M9）——自动注册（junit-platform.properties 开 autodetection +
 * META-INF/services 登记），每个 @Test 执行前把共享的内存 Redis 里的博客缓存键族与
 * sys:config 镜像清空。
 *
 * <p>为什么必须存在：M9 之后详情/列表缓存与 InMemoryRedis 的 store 都是"随 Spring
 * 上下文存活"的对象，而测试的 DB 回滚（类级 @Transactional）只回滚 MySQL——Redis 不会
 * 跟着回滚。A 用例发布文章回填的列表缓存，B 用例（甚至另一个测试类、共享同一上下文
 * 缓存时）回滚后仍在，B 的"改库→读接口"断言就会读到 A 留下的旧值——这不是缓存的 bug，
 * 恰恰是缓存正确性的证明，但对测试是纯粹的污染。守卫让每个用例从"缓存空态"起跑，
 * 用例内部的缓存行为（回填/命中/失效）由 M9 自己的用例显式驱动与断言。
 *
 * <p>非 Spring 语境的纯单元测试（AesGcmUtilTest 等）：取不到 ApplicationContext 时
 * 静默跳过——守卫只对有 Redis 的上下文有意义。
 */
public class TestCacheGuardExtension implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        ApplicationContext ac;
        try {
            ac = SpringExtension.getApplicationContext(context);
        } catch (Exception e) {
            return; // 纯单元测试上下文，无 Bean 可清
        }
        // getBeanProvider：上下文里缺该 Bean（理论上不该发生）也不炸测试基建
        ac.getBeanProvider(ArticleCacheService.class)
                .ifAvailable(ArticleCacheService::evictAll);
        ac.getBeanProvider(StringRedisTemplate.class)
                .ifAvailable(t -> {
                    try {
                        // PV 计数器/按日键/补偿队列也要清：A 用例点出来的浏览量
                        // 若渗进 B 用例的 dashboard 断言，又是"数字自己动"的假阳性源
                        t.delete(SysConfigService.MIRROR_KEY);
                        t.delete(ArticleCacheService.EVICT_RETRY);
                        for (String p : new String[]{ArticleCacheService.PV_PREFIX + "*",
                                ArticleCacheService.PVDAY_PREFIX + "*"}) {
                            java.util.Set<String> ks = t.keys(p);
                            if (ks != null) {
                                // 逐个 delete(String) 而非 delete(Collection)：
                                // InMemoryRedis 的 mock 只桩了单键形态，集合形态在测试里是空操作
                                for (String k : ks) {
                                    t.delete(k);
                                }
                            }
                        }
                    } catch (Exception ignore) {
                        // 内存版 Redis 的 delete 不会抛；真 Redis 集成测试语境下清失败
                        // 也只是退回"镜像旧值"路径，不阻断守卫职责
                    }
                });
    }
}
