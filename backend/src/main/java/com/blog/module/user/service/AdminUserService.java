package com.blog.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.common.PageResult;
import com.blog.module.auth.service.AuthService;
import com.blog.module.user.entity.SysRole;
import com.blog.module.user.entity.SysUser;
import com.blog.module.user.entity.UserRole;
import com.blog.module.user.mapper.SysRoleMapper;
import com.blog.module.user.mapper.SysUserMapper;
import com.blog.module.user.mapper.UserRoleMapper;
import com.blog.module.user.vo.UserVO;
import com.blog.security.JwtProperties;
import com.blog.security.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 管理端用户治理服务（M2）—— GET /api/admin/users、PATCH status、POST roles
 * （《API接口设计.md》§8，权限点 user:manage）。
 *
 * <p>安全护栏（写进设计注释防止后人"顺手优化"掉）：
 * <ul>
 *   <li>禁用即拉黑登录态（文档 §8 注）：status=0 时抬该用户失效水位，所有端旧令牌
 *       立刻 401——否则被禁用户在 2h TTL 内仍是"合法登录"，禁用形同虚设；</li>
 *   <li>禁止禁用自己 / 禁止移除自己的 ADMIN 角色：单人课设系统一旦误操作就是
 *       全员锁死、再无管理员能进后台，属于"必须显式拒绝"的毁灭性操作。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final AuthService authService;
    private final TokenBlacklistService blacklistService;
    private final JwtProperties jwtProperties;

    /**
     * 用户分页（?keyword&status&pageNum&pageSize）。
     * keyword 模糊匹配用户名/昵称/邮箱（后台找人没有更精准的入口了）；
     * status 精确过滤（1/0，null=全部）。分页防腐由 PageResult + MP 分页插件保证
     * （M0 骨架），pageSize 上限在 PageResult 统一钳制。
     */
    public PageResult<UserVO> pageUsers(String keyword, Integer status, long pageNum, long pageSize) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<SysUser>()
                .orderByDesc(SysUser::getId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            // or() 组合三列 LIKE：MP 会把条件正确加括号，不存在布尔优先级事故
            qw.and(w -> w.like(SysUser::getUsername, kw)
                    .or().like(SysUser::getNickname, kw)
                    .or().like(SysUser::getEmail, kw));
        }
        if (status != null) {
            qw.eq(SysUser::getStatus, status);
        }
        Page<SysUser> page = userMapper.selectPage(new Page<>(pageNum, pageSize), qw);

        // 逐行装配 VO 会 N+1 查角色/权限（后台一页 ≤100 行，量级可接受；
        // 若列表性能成为问题在 M9 用 Redis 快照或 JOIN 聚合优化——先正确后快）
        List<UserVO> list = page.getRecords().stream().map(authService::assembleVO).toList();
        // 手动构造 PageResult：PageResult.of(IPage) 的防腐签名只透传 records，
        // 这里载荷做过实体→VO 映射，走全参构造保持同一输出契约
        return new PageResult<>(page.getTotal(), page.getPages(),
                page.getCurrent(), page.getSize(), list);
    }

    /**
     * 启用/禁用账号（PATCH /api/admin/users/{id}/status）。
     * 幂等：目标状态与当前一致直接返回（接口幂等约定，API 文档 §8.4）。
     */
    @Transactional
    public void changeStatus(Long operatorId, Long targetUserId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(BizCode.PARAM_ERROR, "status 仅接受 0(禁用)/1(启用)");
        }
        if (operatorId.equals(targetUserId)) {
            throw new BizException(BizCode.FORBIDDEN, "不能禁用自己的账号", 403);
        }
        SysUser target = requireUser(targetUserId);
        if (target.getStatus().equals(status)) {
            return; // 幂等空操作
        }
        SysUser update = new SysUser();
        update.setId(targetUserId);
        update.setStatus(status);
        userMapper.updateById(update);

        if (status == 0) {
            // 禁用即踢线：抬失效水位作废其全部已签发令牌（见类注释护栏 1）
            blacklistService.invalidateAllTokens(targetUserId, jwtProperties.getExpireSeconds());
            log.info("管理员 {} 禁用用户 {}，其全部登录令牌已作废", operatorId, targetUserId);
        }
    }

    /**
     * 分配角色（POST /api/admin/users/{id}/roles，body {roleIds:[1,2]}）。
     * 语义是"整组覆盖"而非追加：关联表即删即建（M1 实体注释），重算覆盖最简单。
     */
    @Transactional
    public void assignRoles(Long operatorId, Long targetUserId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new BizException(BizCode.PARAM_ERROR, "roleIds 不能为空");
        }
        // 角色必须都存在（防止前端传脏 id 造成"user_role 指向幽灵角色"）
        List<SysRole> roles = roleMapper.selectBatchIds(roleIds);
        if (roles.size() != roleIds.stream().distinct().count()) {
            throw new BizException(BizCode.PARAM_ERROR, "包含不存在的角色ID");
        }
        requireUser(targetUserId);

        boolean keepSelfAdmin = operatorId.equals(targetUserId)
                && roles.stream().noneMatch(r -> "ROLE_ADMIN".equals(r.getRoleCode()));
        if (keepSelfAdmin) {
            throw new BizException(BizCode.FORBIDDEN, "不能移除自己的管理员角色", 403);
        }

        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, targetUserId));
        for (SysRole role : roles) {
            UserRole ur = new UserRole();
            ur.setUserId(targetUserId);
            ur.setRoleId(role.getId());
            userRoleMapper.insert(ur);
        }
        // 角色集写进令牌声明，变更后旧令牌的权限视图是"改前快照"——抬水位强制重登，
        // 保证鉴权口径与库内一致（宁可踢用户下线，也不放"权限漂移"的令牌过关）
        blacklistService.invalidateAllTokens(targetUserId, jwtProperties.getExpireSeconds());
    }

    private SysUser requireUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(BizCode.PARAM_ERROR, "用户不存在", 404);
        }
        return user;
    }
}
