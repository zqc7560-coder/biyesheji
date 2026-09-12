package com.blog.module.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.module.auth.dto.LoginDTO;
import com.blog.module.auth.dto.RegisterDTO;
import com.blog.module.auth.entity.LoginLog;
import com.blog.module.auth.mapper.LoginLogMapper;
import com.blog.module.auth.vo.LoginVO;
import com.blog.module.system.entity.SysConfig;
import com.blog.module.system.mapper.SysConfigMapper;
import com.blog.module.user.entity.SysUser;
import com.blog.module.user.entity.UserRole;
import com.blog.module.user.mapper.SysUserMapper;
import com.blog.module.user.mapper.UserRoleMapper;
import com.blog.module.user.vo.UserVO;
import com.blog.security.JwtProperties;
import com.blog.security.JwtService;
import com.blog.security.LoginUser;
import com.blog.security.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 认证服务（M2 核心）—— 注册 / 登录 / 登出 / 滑动续期。
 * 流程蓝本：《系统设计.md》§3.1 登录时序图；错误码契约：《API接口设计.md》§1.4、§2。
 *
 * <p>登录防枚举决策（时序图第 5 行）：用户不存在与密码错误统一回 1002
 * "用户名或密码错误"——攻击者无法通过响应差异探测账号是否存在；但 login_log
 * 内部用 result=2/4 区分留痕（《数据库设计.md》§3.15），审计与攻防两全。
 *
 * <p>注册并发决策：先 count 查重给友好提示（1101/1102），插入时仍捕获
 * DuplicateKeyException 再转同码——"查后插"竞态窗口由 uk_username/uk_email
 * 唯一键兜底（SysUser 实体注释的约定，M1 建表时已埋好）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** login_log.result 枚举值（《数据库设计.md》§3.15：1成功 2密码错误 3账号禁用 4不存在）。
     *  用常量而非魔法数字散落在 if-else 里，日志口径一处定义。 */
    private static final int RESULT_SUCCESS = 1;
    private static final int RESULT_BAD_PASSWORD = 2;
    private static final int RESULT_DISABLED = 3;
    private static final int RESULT_USER_NOT_FOUND = 4;

    /** 种子角色编号：ROLE_USER=1（data.sql 显式主键约定，注册默认授予） */
    private static final long ROLE_USER_ID = 1L;

    private final SysUserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final LoginLogMapper loginLogMapper;
    private final SysConfigMapper sysConfigMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final TokenBlacklistService blacklistService;

    /**
     * 自身代理注入（@Lazy 打断构造环）：writeLoginLog 的 REQUIRES_NEW 事务语义
     * 只有经 Spring 代理调用才生效——login() 内部直接 this.writeLoginLog() 会绕过
     * 代理让"独立事务"变成空话（登录失败抛 BizException 回滚时日志一起被吞，
     * 恰恰丢掉最该留痕的爆破记录）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private AuthService self;

    // ============================== 注册 ==============================

    /**
     * 注册（POST /api/auth/register）。
     *
     * @return 新用户 userId（响应 data.userId，对齐 API 文档示例）
     */
    @Transactional
    public Long register(RegisterDTO dto) {
        // 1. 注册开关：sys_config.allow_register=false 时拒绝新注册（项目计划阶段三任务 2）。
        //    读库而非硬编码：管理员后台可热开关，不重启生效。
        if (!isRegisterOpen()) {
            throw new BizException(BizCode.FORBIDDEN, "系统已关闭注册", 403);
        }

        String username = dto.getUsername().trim();
        String email = dto.getEmail().trim().toLowerCase();
        // nickname 也 trim：展示字段，首尾空格是用户误输入

        // 2. 友好查重（先查后插，给用户明确的 1101/1102 区分；并发穿透窗口由唯一键兜底）
        Long dupName = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (dupName > 0) {
            throw new BizException(BizCode.USERNAME_EXISTS);
        }
        Long dupEmail = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmail, email));
        if (dupEmail > 0) {
            throw new BizException(BizCode.EMAIL_EXISTS);
        }

        // 3. 落库：密码只存 BCrypt 散列，明文在这一行之后即不可恢复（安全红线）
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname().trim());
        user.setEmail(email);
        user.setStatus(1); // 注册用户默认正常；禁用只能由管理员发起
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 唯一键兜底转业务码：与上面的友好查重同一语义，保证并发下错误信息不失真
            throw new BizException(isUsernameTaken(username) ? BizCode.USERNAME_EXISTS : BizCode.EMAIL_EXISTS);
        }

        // 4. 授予默认角色 ROLE_USER（一人一角色；角色集合本期为常量，M1 决策）
        UserRole ur = new UserRole();
        ur.setUserId(user.getId());
        ur.setRoleId(ROLE_USER_ID);
        userRoleMapper.insert(ur);

        return user.getId();
    }

    private boolean isUsernameTaken(String username) {
        return userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)) > 0;
    }

    /** 读注册开关；配置行缺失时默认开放（与 data.sql 种子 true 一致，避免误锁站） */
    private boolean isRegisterOpen() {
        SysConfig config = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, "allow_register"));
        return config == null || !"false".equalsIgnoreCase(config.getConfigValue());
    }

    // ============================== 登录 ==============================

    /**
     * 登录：BCrypt 校验 → 签 JWT → 写登录快照与 last_login_at → 记录登录日志（成败都记）。
     *
     * <p>注意 login_log 在"抛业务异常之前"也要落库：失败留痕是暴力破解审计的数据源
     * （《数据库设计.md》§3.15 设计意图），因此日志写入不能放在事务回滚路径里被撤销——
     * 本方法用"先写日志再抛异常 + 异常发生在写日志之后"的顺序保证留痕；
     * 又因 BizException 是 RuntimeException 会触发回滚，所以日志插入走独立事务边界
     * （REQUIRES_NEW 的 writeLoginLog）。
     */
    public LoginVO login(LoginDTO dto, String ip, String userAgent) {
        String username = dto.getUsername().trim();
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        // 本期仅用户名登录（API 文档 §2 登录体只有 username）。
        // 不做"邮箱也能登"的原因：登录名与展示身份解耦（邮箱可换绑），且避免
        // 同一人两把钥匙带来的查重/枚举复杂度。

        if (user == null) {
            writeLoginFail(username, ip, userAgent, RESULT_USER_NOT_FOUND);
            // 对外统一 1002：不给枚举账号的机会（见类注释决策）
            throw new BizException(BizCode.LOGIN_FAILED, BizCode.LOGIN_FAILED.getMessage(), 401);
        }

        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            writeLoginFail(username, ip, userAgent, RESULT_BAD_PASSWORD);
            throw new BizException(BizCode.LOGIN_FAILED, BizCode.LOGIN_FAILED.getMessage(), 401);
        }

        if (user.getStatus() == null || user.getStatus() != 1) {
            writeLoginFail(username, ip, userAgent, RESULT_DISABLED);
            throw new BizException(BizCode.ACCOUNT_DISABLED, BizCode.ACCOUNT_DISABLED.getMessage(), 403);
        }

        // —— 校验全部通过：聚合 RBAC → 签发令牌 ——
        List<String> roles = userMapper.selectRoleCodesByUserId(user.getId());
        List<String> perms = userMapper.selectPermCodesByUserId(user.getId());
        String token = jwtService.issue(user.getId(), user.getUsername(),
                user.getNickname(), user.getAvatar(), roles, perms);

        // 登录快照（《系统设计.md》§3.1：SET login:user:{uid}，踢人水位寄生于此）。
        // 写失败仅告警不阻断登录（JwtAuthFilter 决策 3 的口径：Redis 故障降级，
        // 代价是"本次会话期间管理员无法用该水位踢人"，2h TTL 自然收敛）
        try {
            blacklistService.writeLoginSnapshot(user.getId(), user.getUsername(),
                    jwtProperties.getExpireSeconds());
        } catch (Exception e) {
            log.warn("登录快照写入失败（Redis 降级）uid={}：{}", user.getId(), e.getMessage());
        }

        // 回写最后登录时间（仪表盘活跃统计用）；独立 update 只动这一列，避免整行覆盖并发编辑
        SysUser touch = new SysUser();
        touch.setId(user.getId());
        touch.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(touch);

        self.writeLoginLog(user.getId(), username, ip, userAgent, RESULT_SUCCESS);

        return LoginVO.builder()
                .token(token)
                .expiresIn(jwtProperties.getExpireSeconds())
                .user(UserVO.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .nickname(user.getNickname())
                        .avatar(user.getAvatar())
                        .roles(roles)
                        .perms(perms)
                        .build())
                .build();
    }

    // ============================== 登出 ==============================

    /**
     * 登出：拉黑当前会话 jti + 清登录快照。
     * 幂等设计（API 文档 §2）：调用方是"登录"权限（SecurityConfig anyRequest().authenticated()），
     * jti 与剩余 TTL 都来自过滤器已验证的上下文，重复登出只是重写同一个黑名单键。
     */
    public void logout(LoginUser loginUser) {
        blacklistService.blacklistJti(loginUser.getJti(), loginUser.getTokenRemainingSeconds());
        blacklistService.removeLoginSnapshot(loginUser.getUserId());
    }

    // ============================== 滑动续期 ==============================

    /**
     * 续期（POST /api/auth/refresh）：剩余 ≥30min 原令牌照用（返回 null，Controller 转
     * "无需续期"语义）；<30min 才换发新令牌。
     *
     * <p>为什么限制"临期才换"：refresh 接口若无限换发，等于把 2h TTL 变相取消，
     * 被盗令牌可永续续命（D-6 短 TTL 设计被架空）。同时旧 jti 主动拉黑——续期即换会话，
     * 旧令牌立即作废（旋转式刷新，防"新旧两把钥匙并行可用"）。
     */
    public LoginVO refresh(LoginUser loginUser) {
        // 用令牌自身剩余 TTL 判断（过滤器已算好，避免二次解析）
        if (loginUser.getTokenRemainingSeconds() > 30 * 60) {
            return null;
        }
        blacklistService.blacklistJti(loginUser.getJti(), loginUser.getTokenRemainingSeconds());
        String token = jwtService.issue(loginUser.getUserId(), loginUser.getUsername(),
                loginUser.getNickname(), loginUser.getAvatar(),
                loginUser.getRoles(), loginUser.getPerms());
        return LoginVO.builder()
                .token(token)
                .expiresIn(jwtProperties.getExpireSeconds())
                .user(toVO(loginUser))
                .build();
    }

    // ============================== me / 装配辅助 ==============================

    /** 当前登录用户信息 + 权限点（GET /api/auth/me）：roles/perms 直接取令牌声明，零查库 */
    public UserVO me(LoginUser loginUser) {
        SysUser db = userMapper.selectById(loginUser.getUserId());
        if (db == null) {
            // 令牌合法但账号已被（逻辑）删除：按失效处理，前端跳重新登录
            throw new BizException(BizCode.UNAUTHORIZED, BizCode.UNAUTHORIZED.getMessage(), 401);
        }
        UserVO vo = toVO(loginUser);
        // me 是本人场景：补齐邮箱/简介/状态等只有本人（或管理员单查）可见的字段
        vo.setEmail(db.getEmail());
        vo.setBio(db.getBio());
        vo.setStatus(db.getStatus());
        return vo;
    }

    /** LoginUser → UserVO（roles/perms 取令牌声明；库内展示字段可被调用方覆盖） */
    public UserVO toVO(LoginUser loginUser) {
        return UserVO.builder()
                .id(loginUser.getUserId())
                .username(loginUser.getUsername())
                .nickname(loginUser.getNickname())
                .avatar(loginUser.getAvatar())
                .roles(loginUser.getRoles())
                .perms(loginUser.getPerms())
                .build();
    }

    /** 供改密等场景重新聚合最新 RBAC 装配 VO（角色变更后 /me 不必等重登的补救查询） */
    public UserVO assembleVO(SysUser user) {
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .email(user.getEmail())
                .bio(user.getBio())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .roles(userMapper.selectRoleCodesByUserId(user.getId()))
                .perms(userMapper.selectPermCodesByUserId(user.getId()))
                .build();
    }

    // ============================== 登录日志（独立事务） ==============================

    private void writeLoginFail(String username, String ip, String ua, int result) {
        // 走 self 代理：保证 REQUIRES_NEW 生效（见 self 字段注释）
        self.writeLoginLog(null, username, ip, ua, result);
    }

    /**
     * 写登录日志。
     *
     * <p>@Transactional(REQUIRES_NEW)：无论外层登录走到"抛 1002 回滚"还是"成功提交"，
     * 日志都必须落库——审计数据不参与业务事务的成败语义（append-only 表，无删除场景）。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void writeLoginLog(Long userId, String username, String ip, String ua, int result) {
        LoginLog log = new LoginLog();
        log.setUserId(userId);
        log.setUsername(username);
        log.setIp(ip == null ? "unknown" : ip);
        // UA 列长 255：截断入库而不是拒绝请求——日志尽力保留即可
        log.setUserAgent(ua == null ? null : (ua.length() > 255 ? ua.substring(0, 255) : ua));
        log.setResult(result);
        loginLogMapper.insert(log);
    }
}
