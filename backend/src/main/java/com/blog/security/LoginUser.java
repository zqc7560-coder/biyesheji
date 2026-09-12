package com.blog.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 登录态用户模型（M2）—— JWT 验签通过后放进 SecurityContext 的 principal。
 *
 * <p>实现 UserDetails 是为了无缝接入 Spring Security 的授权体系：
 * {@code @PreAuthorize("hasAuthority('article:save')")} 读取的 authority 集合
 * 就是这里 getAuthorities() 的返回值。
 *
 * <p>权限点来源：登录时从 user_role→role_permission→sys_permission 聚合一次
 * （《数据库设计.md》§3.5 注释"避免每请求联表查库"），角色/权限点随 JWT 声明
 * 携带进 token，JwtAuthFilter 每次只解签不查库——这是无状态认证的性能基础。
 * 代价是"改权限需重签 token 才生效"，本期角色集合视为常量（M1 决策），可接受。
 */
@Getter
public class LoginUser implements UserDetails {

    /** 用户 ID：Controller 里取"当前用户"做属主校验用（如 PUT /api/users/profile） */
    private final Long userId;

    /** 登录名 */
    private final String username;

    /** 昵称：/api/auth/me 回显 */
    private final String nickname;

    /** 头像 URL：/api/auth/me 回显 */
    private final String avatar;

    /** 角色码列表（ROLE_USER/ROLE_ADMIN）：同时是 granted authority（带 ROLE_ 前缀，
     *  与 Spring Security 约定一致，hasRole('ADMIN') 即匹配 ROLE_ADMIN） */
    private final List<String> roles;

    /** 权限点列表（article:save / ai:config...）：直接作为 granted authority，
     *  供 hasAuthority('xxx') 判定 */
    private final List<String> perms;

    /** 本次登录令牌的 jti：登出/改密时拉黑它（《系统设计.md》§3.1） */
    private final String jti;

    /** 令牌剩余有效秒数：拉黑时作为黑名单键 TTL（剩余多久就黑多久，之后键自然过期清理） */
    private final long tokenRemainingSeconds;

    /** 令牌签发时刻（毫秒）：与用户级失效水位比较判定"被踢前的旧令牌" */
    private final long issuedAtMs;

    /** 由过滤器在验签成功后组装；authority = 角色 + 权限点 两类的并集 */
    public LoginUser(Long userId, String username, String nickname, String avatar,
                     List<String> roles, List<String> perms, String jti, long tokenRemainingSeconds,
                     long issuedAtMs) {
        this.userId = userId;
        this.username = username;
        this.nickname = nickname;
        this.avatar = avatar;
        this.roles = roles;
        this.perms = perms;
        this.jti = jti;
        this.tokenRemainingSeconds = tokenRemainingSeconds;
        this.issuedAtMs = issuedAtMs;
    }

    /**
     * Spring Security 授权判定读取这里：角色与权限点合并成 authority 集合。
     * 角色保留 ROLE_ 前缀原样放入（hasRole 会自动补前缀比对），权限点原样放入（hasAuthority 精确匹配）。
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return java.util.stream.Stream.concat(
                        roles.stream().map(SimpleGrantedAuthority::new),
                        perms.stream().map(SimpleGrantedAuthority::new))
                .toList();
    }

    /** 无状态 JWT 方案下不存在"密码"概念：返回空串，绝不让框架误用 */
    @Override
    public String getPassword() {
        return "";
    }

    // ==== 以下账号状态位全部返回默认值：禁用状态在登录时已拦截（1003），
    // 令牌内不重复携带状态；过期/吊销由 JwtAuthFilter 统一处理 ====
    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }
}
