package com.blog.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.module.auth.service.AuthService;
import com.blog.module.storage.service.MinimalFileStorageService;
import com.blog.module.user.dto.ChangePasswordDTO;
import com.blog.module.user.dto.ProfileUpdateDTO;
import com.blog.module.user.entity.SysUser;
import com.blog.module.user.mapper.SysUserMapper;
import com.blog.module.user.vo.UserVO;
import com.blog.security.JwtProperties;
import com.blog.security.LoginUser;
import com.blog.security.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;


/**
 * 用户自助服务（M2）：资料查询/修改、改密、头像上传。
 * 接口契约：《API接口设计.md》§3（GET/PUT /api/users/profile、PUT password、POST avatar）。
 *
 * <p>越权三防的第三层在这里：Controller 只传"当前登录用户 id"（取自令牌，不可伪造），
 * Service 一律 {@code WHERE id = 登录者id} 更新，不接受请求体里的任意 userId 写操作
 * ——查看他人（?userId=）是只读且隐去邮箱等 PII，修改永远只能改自己。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final AuthService authService;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final TokenBlacklistService blacklistService;
    private final JwtProperties jwtProperties;
    private final MinimalFileStorageService storageService;

    /**
     * 查看资料（GET /api/users/profile?userId=，缺省为自己）。
     *
     * <p>隐私分级：查自己返回全量（含邮箱/bio）；查他人只返回公开字段
     * （id/昵称/头像/简介/角色列表）。email 是 PII，非本人一律置空——
     * 这是"查作者主页"场景与"改资料"场景共用一个端点时的最小暴露策略。
     */
    public UserVO getProfile(LoginUser me, Long targetUserId) {
        Long id = (targetUserId == null || targetUserId.equals(0L)) ? me.getUserId() : targetUserId;
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(BizCode.PARAM_ERROR, "用户不存在", 404);
        }
        boolean self = id.equals(me.getUserId());
        // 管理员场景（user:manage 权限点）可看全量——后台用户管理页要用
        boolean admin = me.getPerms().contains("user:manage");
        if (self || admin) {
            return authService.assembleVO(user);
        }
        UserVO vo = authService.assembleVO(user);
        vo.setEmail(null);   // 抹除 PII
        vo.setStatus(null);
        vo.setCreatedAt(null);
        return vo;
    }

    /**
     * 修改资料（PUT /api/users/profile，作者=本人）：昵称/邮箱/简介。
     *
     * <p>邮箱换绑重新查重（uk_email）：1102 语义与注册一致；查重后到 update 之间的
     * 并发窗口同样由唯一键兜底（DuplicateKeyException → 1102）。
     */
    @Transactional
    public UserVO updateProfile(LoginUser me, ProfileUpdateDTO dto) {
        String email = dto.getEmail().trim().toLowerCase();
        Long dup = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmail, email)
                .ne(SysUser::getId, me.getUserId()));   // 排除自己占用自己邮箱的误报
        if (dup > 0) {
            throw new BizException(BizCode.EMAIL_EXISTS);
        }
        SysUser update = new SysUser();
        update.setId(me.getUserId());
        update.setNickname(dto.getNickname().trim());
        update.setEmail(email);
        update.setBio(dto.getBio());
        try {
            userMapper.updateById(update);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BizException(BizCode.EMAIL_EXISTS);
        }
        // 头像不在本接口：POST /api/users/avatar 单独走上传通道（multipart 语义不同，
        // 且图片处理失败不应连带资料更新回滚）
        SysUser fresh = userMapper.selectById(me.getUserId());
        return authService.assembleVO(fresh);
    }

    /**
     * 修改密码（PUT /api/users/password）。
     *
     * <p>"改密后全端强制重登"的两级实现（API 文档 §3 注：拉黑旧 jti、DEL login:user:{uid}）：
     * <ol>
     *   <li>当前会话的 jti 拉黑（立刻失效）；</li>
     *   <li>用户级失效水位抬到当前时刻——该用户其它端上的旧令牌（签发时间早于水位）
     *       同样被 JwtAuthFilter 拒绝。这是文档里 "DEL login:user:{uid}" 语义的等价实现：
     *       直接 DEL 会让水位消失，反而放行其它端旧令牌，故改为"写水位"而非"删快照"，
     *       决策已回写台账迭代日志。</li>
     * </ol>
     * 顺序讲究：先校验旧密码 → 落新散列 → 最后作废令牌（校验失败不动任何状态）。
     */
    @Transactional
    public void changePassword(LoginUser me, ChangePasswordDTO dto) {
        SysUser user = userMapper.selectById(me.getUserId());
        if (user == null) {
            throw new BizException(BizCode.UNAUTHORIZED, BizCode.UNAUTHORIZED.getMessage(), 401);
        }
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            // 复用 1002 语义：旧密码错误与登录失败同类，不新造错误码
            throw new BizException(BizCode.LOGIN_FAILED, "旧密码错误", 401);
        }
        if (passwordEncoder.matches(dto.getNewPassword(), user.getPassword())) {
            throw new BizException(BizCode.PARAM_ERROR, "新密码不能与当前密码相同");
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        userMapper.updateById(update);

        // 全端下线：当前 jti + 用户级水位（见方法注释）
        blacklistService.blacklistJti(me.getJti(), me.getTokenRemainingSeconds());
        blacklistService.invalidateAllTokens(user.getId(), jwtProperties.getExpireSeconds());
    }

    /**
     * 头像上传（POST /api/users/avatar，multipart file）。
     *
     * <p>存储走 MinimalFileStorageService（storage 模块"先行最小版"，项目计划阶段三
     * 任务 5 的口径；通用文件接口 /api/files/upload 在 M8 完整落地后复用同一实现）。
     * 上传成功后把 URL 写进 sys_user.avatar 并在响应里回传，前端立即刷新显示。
     */
    @Transactional
    public UserVO uploadAvatar(LoginUser me, MultipartFile file) {
        String url = storageService.storeImage(file, "avatar", me.getUserId());
        SysUser update = new SysUser();
        update.setId(me.getUserId());
        update.setAvatar(url);
        userMapper.updateById(update);
        SysUser fresh = userMapper.selectById(me.getUserId());
        return authService.assembleVO(fresh);
    }
}
