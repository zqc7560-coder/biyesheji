package com.blog.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.module.system.dto.SystemConfigUpdateDTO;
import com.blog.module.system.entity.SysConfig;
import com.blog.module.system.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统配置服务（M8）—— 站点公开参数读取 + 管理员批量修改（UC-A-09）。
 *
 * <p>两张白名单是本模块的安全核心：
 * <ul>
 *   <li>PUBLIC_KEYS：GET /api/system/config（匿名可达）只暴露这几键。若不设白名单
 *       全表直出，ai_daily_quota（爆破成本情报）、ai_price_*（商业参数）都会随
 *       匿名接口泄露——"公开接口返回最小集合"与 PII 分级（M2）同一原则。</li>
 *   <li>EDITABLE_KEYS：PUT /api/admin/system/config 只允许改这几键。ai_* 键族
 *       虽然也是 sys_config 行，但它们的语义闸门分散在 AiSysConfigReader/配额/
 *       记账三处，走通用表单改出脏值（如 quota 填负数）会静默改变 AI 防线行为；
 *       管理它们的正确入口是各自模块的专职接口（AI 配置页/未来的配额设置），
 *       系统参数页只管"站点门面"类参数。</li>
 * </ul>
 *
 * <p>修改时按库中 config_type 做值校验（boolean 须 true/false、number 须可解析
 * 十进制），类型解释以服务端存储为事实源、不信任客户端自报——校验不过整批回滚
 * （@Transactional 单事务），响应体回传修改后的全量可编辑清单供表单刷新。
 *
 * <p><b>M9：sys:config Redis 镜像（《数据库设计.md》§4）</b>。publicConfig() 从
 * Hash 镜像 sys:config 读（miss 懒加载全表回填），启动引导 SysConfigMirrorLoader
 * 预灌；修改口对镜像做"双删"（写前 DEL + afterCommit DEL），与文章缓存同一套
 * Cache-Aside 纪律。<b>镜像的读侧边界是刻意的</b>：只有匿名高频的 publicConfig
 * 走镜像；allow_register / comment_audit 等安全开关的 Service 内读仍直查 DB——
 * ① 它们都在写事务内被消费（注册/发评论），镜像会把"同事务未提交的改动"读成旧值，
 *    造成"刚改完开关本单请求不生效"的正确性坑；② 这些开关每请求至多一次
 *    单行主键查询，本就不是性能瓶颈。镜像只优化"读多到值得为其担一致性风险"的
 *    那一条路径（首屏站点参数），其余一律 DB 真相——性能优化的适用面要写死在
 *    代码注释里，而不是留给后人猜。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysConfigService {

    /** 匿名可见键：site_name/logo/备案号/注册开关/评论审核开关（API 文档 §8 括号的超集，
     *  comment_audit 前端详情页发表框要据此渲染提示，不公开就得让游客打登录接口嗅探） */
    public static final Set<String> PUBLIC_KEYS = Set.of(
            "site_name", "site_logo", "site_icp", "allow_register", "comment_audit");

    /** 管理员可编辑键：公开键 + 非敏感运营参数。ai_enabled 是"一键熔断"总闸
     *  （项目计划 R-03 应急路径），必须留在表单里；ai_daily_quota 属 P1 用例
     *  UC-A-08（配额写 sys_config），一并放开但强校验 number 类型。 */
    public static final Set<String> EDITABLE_KEYS = Set.of(
            "site_name", "site_logo", "site_icp", "allow_register", "comment_audit",
            "ai_enabled", "ai_daily_quota");

    /** 镜像键（《数据库设计.md》§4 表列的键名逐字对齐）：Hash，field=config_key */
    public static final String MIRROR_KEY = "sys:config";
    /** 镜像哨兵字段：标记"这轮全量加载完成"。读侧以它判空而非 hlen==0——
     *  真表理论可空，哨兵让"空库"与"未加载"两种状态可区分（懒加载不空转） */
    private static final String MIRROR_LOADED_FIELD = "__loaded_at";

    private final SysConfigMapper sysConfigMapper;
    /** M9：sys:config 镜像的读写口（StringRedisTemplate——与黑名单/配额同一个叶子基建，
     *  无环；Redis 故障全部 try-catch 降级直查 DB，镜像是加速器不是依赖） */
    private final StringRedisTemplate redisTemplate;

    /**
     * 站点公开参数（匿名接口用）：返回 {key: value} 平铺 Map，前端按 key 取用。
     *
     * <p>M9：读路径改走 sys:config 镜像（首屏全站每访客一次，匿名 QPS 最高的
     * DB 查询之一）；镜像 miss → 懒加载全表回填再读；Redis 任何异常 → 直查 DB
     * （publicConfigFromDb）。对调用方两条路径返回形状完全一致。
     */
    public Map<String, String> publicConfig() {
        try {
            Map<Object, Object> mirror = redisTemplate.<Object, Object>opsForHash().entries(MIRROR_KEY);
            if (mirror != null && mirror.containsKey(MIRROR_LOADED_FIELD)) {
                Map<String, String> out = new LinkedHashMap<>();
                for (String key : PUBLIC_KEYS.stream().sorted().toList()) {
                    Object v = mirror.get(key);
                    if (v != null) {
                        out.put(key, String.valueOf(v));
                    }
                }
                return out;
            }
        } catch (Exception e) {
            log.warn("sys:config 镜像读取失败（降级直查 DB）: {}", e.getMessage());
        }
        Map<String, String> dbView = publicConfigFromDb();
        // 懒回填闸门：事务内读到的是"本事务视角"的值（可能是未提交改动，测试类级
        // 事务/生产内层调用都会命中），写进镜像就是把脏值灌给所有后续读者——
        // 所以仅在无事务上下文时回填；事务内的读直接消费 DB 值，不污染共享镜像。
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            try {
                reloadMirror();
            } catch (Exception e) {
                log.warn("sys:config 镜像回填失败（下次读再试）: {}", e.getMessage());
            }
        }
        return dbView;
    }

    /** DB 直查公开参数（镜像不可用时的回源路径，与 M8 原实现同口径） */
    private Map<String, String> publicConfigFromDb() {
        List<SysConfig> rows = sysConfigMapper.selectList(new LambdaQueryWrapper<SysConfig>()
                .in(SysConfig::getConfigKey, PUBLIC_KEYS));
        Map<String, String> out = new LinkedHashMap<>();
        for (SysConfig row : rows) {
            out.put(row.getConfigKey(), row.getConfigValue());
        }
        return out;
    }

    /**
     * 全量镜像加载：DEL 后整表 HSET（启动引导与懒回填共用）。
     * 先删后灌而不是 putAll 增量覆盖：DB 里已删除的键必须从镜像消失，
     * 增量写做不到这一点（墓碑残留=幽灵配置）。
     */
    public long reloadMirror() {
        List<SysConfig> rows = sysConfigMapper.selectList(null);
        redisTemplate.delete(MIRROR_KEY);
        Map<String, String> hash = new LinkedHashMap<>();
        for (SysConfig row : rows) {
            hash.put(row.getConfigKey(), row.getConfigValue());
        }
        hash.put(MIRROR_LOADED_FIELD, String.valueOf(System.currentTimeMillis()));
        redisTemplate.opsForHash().putAll(MIRROR_KEY, hash);
        return rows.size();
    }

    /** 管理端可编辑清单（含类型/说明，前端据此渲染动态表单控件） */
    public List<Map<String, Object>> adminList() {
        List<SysConfig> rows = sysConfigMapper.selectList(new LambdaQueryWrapper<SysConfig>()
                .in(SysConfig::getConfigKey, EDITABLE_KEYS)
                .orderByAsc(SysConfig::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SysConfig row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", row.getConfigKey());
            item.put("value", row.getConfigValue());
            item.put("type", row.getConfigType());
            item.put("description", row.getDescription());
            out.add(item);
        }
        return out;
    }

    /**
     * 批量修改（PUT /api/admin/system/config）。
     *
     * @return 修改后的全量可编辑清单（与 adminList 同构，表单直接重渲染，
     *         省一次 GET 往返且避免"保存后立刻 GET 读到主从延迟旧值"的边角）
     */
    @Transactional
    public List<Map<String, Object>> update(SystemConfigUpdateDTO dto, Long adminId) {
        // 同键重复提交是表单脏数据信号（两行不同值最后写哪行看 Map 顺序），直接拒
        Set<String> seen = new HashSet<>();
        for (SystemConfigUpdateDTO.Item item : dto.getItems()) {
            if (!seen.add(item.getKey())) {
                throw new BizException(BizCode.PARAM_ERROR, "配置键重复提交: " + item.getKey());
            }
        }

        // 两阶段结构（阶段一：整批校验并收集待写行；阶段二：统一落库）。
        // 为什么不只靠 @Transactional 回滚：事务语义依赖代理调用链（同类自调用、
        // 或外层测试事务共享时回滚时机不可见），而"全校验通过才写第一行"是代码
        // 结构自证的原子性——任何环境下都不可能出现半提交。校验在前也让错误
        // 信息更友好（第一个坏键即报错，而不是"改了一半才发现第二个键非法"）。
        List<SysConfig> pending = new ArrayList<>();
        for (SystemConfigUpdateDTO.Item item : dto.getItems()) {
            String key = item.getKey().trim();
            String value = item.getValue().trim();
            if (!EDITABLE_KEYS.contains(key)) {
                // 不可编辑≠不存在：对"键不存在"与"键存在但不许从这改"统一报 5001
                // （不给攻击者"哪些内部键真实存在"的探测反馈，语义与文章防枚举同族）
                throw new BizException(BizCode.CONFIG_NOT_FOUND,
                        "配置项不存在或不允许在此修改: " + key, 404);
            }
            SysConfig row = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                    .eq(SysConfig::getConfigKey, key));
            if (row == null) {
                throw new BizException(BizCode.CONFIG_NOT_FOUND, "配置项不存在: " + key, 404);
            }
            validateByType(row.getConfigType(), key, value);
            row.setConfigValue(value);
            row.setUpdatedBy(adminId);
            pending.add(row);
        }
        for (SysConfig row : pending) {
            sysConfigMapper.updateById(row);
        }

        // 镜像失效·第一删（写后立即 DEL）：本事务内的后续读（adminList/回源）不受
        // 镜像影响，但并发到达的下一个 publicConfig 请求从此刻起会 miss 并懒回填——
        // 回填读到的可能是未提交旧值（READ_COMMITTED），所以才要有第二删兜底。
        // Redis 故障绝不能回滚业务修改：try-catch 吞掉，第二删失败也只留痕
        //（镜像 5min 后被下一次读重建，最长脏窗口=下次回填前，站点门面参数可接受；
        //  安全开关根本不走镜像，见类注释的边界论证）。
        try {
            redisTemplate.delete(MIRROR_KEY);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                try {
                                    redisTemplate.delete(MIRROR_KEY);
                                } catch (Exception e) {
                                    log.warn("sys:config 第二删失败: {}", e.getMessage());
                                }
                            }
                        });
            }
        } catch (Exception e) {
            log.warn("sys:config 镜像失效失败（镜像将于下次读自然重建）: {}", e.getMessage());
        }
        return adminList();
    }

    /** 按库中类型列校验新值：脏值在写入前拦截，而不是留给读取方各显神通兜底 */
    private void validateByType(String type, String key, String value) {
        switch (type == null ? "string" : type) {
            case "boolean" -> {
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw new BizException(BizCode.PARAM_ERROR,
                            key + " 需要 true/false，收到: " + value);
                }
            }
            case "number" -> {
                try {
                    new BigDecimal(value);
                } catch (NumberFormatException e) {
                    throw new BizException(BizCode.PARAM_ERROR, key + " 需要数字，收到: " + value);
                }
            }
            default -> {
                // string/json：本期无 json 型可编辑键，json 暂按 string 收（校验器
                // M9+ 若引入 JSON 表单再补，不提前背 Jackson 解析的边角成本）
            }
        }
    }
}
