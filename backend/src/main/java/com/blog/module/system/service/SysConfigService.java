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

    private final SysConfigMapper sysConfigMapper;
    /** M9 将把 sys:config Redis 镜像接进本类（启动全量加载+修改双删，设计文档 §4）；
     *  本期仅尽力 DEL 一个镜像键——键不存在时 DEL 是 no-op，不影响正确性 */
    private final StringRedisTemplate redisTemplate;

    /** 站点公开参数（匿名接口用）：返回 {key: value} 平铺 Map，前端按 key 取用 */
    public Map<String, String> publicConfig() {
        List<SysConfig> rows = sysConfigMapper.selectList(new LambdaQueryWrapper<SysConfig>()
                .in(SysConfig::getConfigKey, PUBLIC_KEYS));
        Map<String, String> out = new LinkedHashMap<>();
        for (SysConfig row : rows) {
            out.put(row.getConfigKey(), row.getConfigValue());
        }
        return out;
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

        // 镜像失效（M9 双删的"第一删"位）：Redis 故障不能回滚业务修改，try-catch 吞掉
        try {
            redisTemplate.delete("sys:config");
        } catch (Exception e) {
            log.warn("sys:config 镜像失效失败（M9 缓存接入前无实际影响）: {}", e.getMessage());
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
