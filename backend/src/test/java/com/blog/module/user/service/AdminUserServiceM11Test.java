package com.blog.module.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.blog.common.BizException;
import com.blog.module.auth.service.AuthService;
import com.blog.module.user.entity.SysRole;
import com.blog.module.user.entity.SysUser;
import com.blog.module.user.entity.UserRole;
import com.blog.module.user.mapper.SysRoleMapper;
import com.blog.module.user.mapper.SysUserMapper;
import com.blog.module.user.mapper.UserRoleMapper;
import com.blog.security.JwtProperties;
import com.blog.security.TokenBlacklistService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M11 测试完善 · 管理端用户治理纯单元（AuthApiM2Test 覆盖了 RBAC 闸门的
 * HTTP 面，但**护栏逻辑的行为分支**——自禁拒绝、幂等空操作、禁号踢线、
 * 换角色抬水位——在接口测试里只能各测一条正例；异常分支的"没做什么"
 * （没 update/没踢线）用 Mockito verify(never) 才能精确钉死）。
 *
 * <p>护栏即契约（AdminUserService 类注释的两条毁灭性操作防线）：
 * ① 禁用即踢线 + 启用不踢线；② 不能禁自己 + 不能摘自己的 ADMIN；
 * ③ assignRoles 后必抬水位（角色进 JWT 声明，不重登权限视图就是旧快照）；
 * ④ 幽灵用户 404；⑤ 幂等：目标态=当前态时零副作用。
 */
@DisplayName("M11 用户治理: 护栏矩阵/踢线时机/幂等零副作用")
class AdminUserServiceM11Test {

    private SysUserMapper userMapper;
    private SysRoleMapper roleMapper;
    private UserRoleMapper userRoleMapper;
    private AuthService authService;
    private TokenBlacklistService blacklist;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        roleMapper = mock(SysRoleMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        authService = mock(AuthService.class);
        blacklist = mock(TokenBlacklistService.class);
        service = new AdminUserService(userMapper, roleMapper, userRoleMapper,
                authService, blacklist, new JwtProperties());
        // changeStatus/assignRoles 的 wrapper 构造依赖 MP 元数据缓存（同 M11 其他纯 mock 类）
        for (Class<?> e : List.of(SysUser.class, UserRole.class)) {
            if (TableInfoHelper.getTableInfo(e) == null) {
                TableInfoHelper.initTableInfo(
                        new MapperBuilderAssistant(new MybatisConfiguration(), ""), e);
            }
        }
    }

    private static SysUser user(long id, int status) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setStatus(status);
        return u;
    }

    private static SysRole role(long id, String code) {
        SysRole r = new SysRole();
        r.setId(id);
        r.setRoleCode(code);
        return r;
    }

    @Test
    @DisplayName("禁用他人：落库 status=0 + 抬失效水位（禁用不踢线=2h TTL 内形同虚设）")
    void disableOthersInvalidatesTokens() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, 1));

        service.changeStatus(1L, 5L, 0);

        verify(userMapper).updateById(any(SysUser.class));
        verify(blacklist).invalidateAllTokens(eqLong(5L), anyLong());
    }

    private static long eqLong(long v) {
        return org.mockito.ArgumentMatchers.eq(v);
    }

    @Test
    @DisplayName("启用不踢线：只翻状态位——复活账号没有要作废的旧令牌")
    void enableDoesNotInvalidate() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, 0));

        service.changeStatus(1L, 5L, 1);

        verify(userMapper).updateById(any(SysUser.class));
        verify(blacklist, never()).invalidateAllTokens(anyLong(), anyLong());
    }

    @Test
    @DisplayName("自禁护栏：操作者=目标直接 1005，先于查库（连目标行都不许读）")
    void cannotDisableSelf() {
        assertThatThrownBy(() -> service.changeStatus(1L, 1L, 0))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能禁用自己");
        verify(userMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("幂等：目标态=当前态零副作用（不 update、不踢线、不打日志噪音）")
    void sameStatusIsNoop() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, 0));

        service.changeStatus(1L, 5L, 0);

        verify(userMapper, never()).updateById(any(SysUser.class));
        verify(blacklist, never()).invalidateAllTokens(anyLong(), anyLong());
    }

    @Test
    @DisplayName("非法 status（null/2/-1）在查库前拒绝：400 文案给出合法值域")
    void illegalStatusRejected() {
        assertThatThrownBy(() -> service.changeStatus(1L, 5L, null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.changeStatus(1L, 5L, 2))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("0");
        verify(userMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("幽灵用户：404 + 文案'用户不存在'（防前端脏 id 静默成功）")
    void ghostUser404() {
        when(userMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.changeStatus(1L, 999L, 0))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getHttpStatus()).isEqualTo(404));
    }

    @Test
    @DisplayName("assignRoles 正常流：先删后建整组覆盖 + 必抬水位（角色进 JWT 声明的连带义务）")
    void assignRolesCoversAndInvalidates() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, 1));
        when(roleMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(role(2L, "ROLE_USER")));

        service.assignRoles(1L, 5L, List.of(2L));

        verify(userRoleMapper).delete(any());
        verify(userRoleMapper).insert(any(UserRole.class));
        verify(blacklist).invalidateAllTokens(eqLong(5L), anyLong());
    }

    @Test
    @DisplayName("摘自己 ADMIN 被拒（单人系统自锁死防线）：关联表一行不许动")
    void cannotRemoveOwnAdminRole() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, 1));
        when(roleMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(role(2L, "ROLE_USER")));

        assertThatThrownBy(() -> service.assignRoles(1L, 1L, List.of(2L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("管理员角色");
        verify(userRoleMapper, never()).delete(any());
        verify(userRoleMapper, never()).insert(any(UserRole.class));
    }

    @Test
    @DisplayName("空 roleIds / 幽灵角色 ID 都拒 400：宁可报错不写脏关联（user_role 指向不存在角色）")
    void roleIdsValidation() {
        assertThatThrownBy(() -> service.assignRoles(1L, 5L, List.of()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.assignRoles(1L, 5L, null))
                .isInstanceOf(BizException.class);

        // 传 [1,2] 库里只查到 1 个 → 含幽灵 ID，整批拒
        when(roleMapper.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(role(1L, "ROLE_ADMIN")));
        assertThatThrownBy(() -> service.assignRoles(1L, 5L, List.of(1L, 2L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        // 重复 ID 去重后不误判（[2,2] → distinct=1，查到 1 行即放行）
        when(userMapper.selectById(5L)).thenReturn(user(5L, 1));
        when(roleMapper.selectBatchIds(List.of(2L, 2L))).thenReturn(List.of(role(2L, "ROLE_USER")));
        service.assignRoles(1L, 5L, List.of(2L, 2L));   // 不抛即通过
    }

    @Test
    @DisplayName("pageUsers：records 逐行经 assembleVO 装配，PageResult 五字段透传口径")
    void pageUsersAssemblesVOs() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysUser> p =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(2, 20);
        p.setRecords(List.of(user(3L, 1)));
        p.setTotal(21);
        when(userMapper.selectPage(any(), any())).thenReturn(p);
        // UserVO 是 @Builder 形态（无空参构造）；本用例只数装配次数，mock 实例即可
        when(authService.assembleVO(any())).thenReturn(mock(com.blog.module.user.vo.UserVO.class));

        var result = service.pageUsers("  张三  ", 1, 2, 20);

        assertThat(result.getTotal()).isEqualTo(21);
        assertThat(result.getList()).hasSize(1);
        verify(authService).assembleVO(any(SysUser.class));
    }
}
