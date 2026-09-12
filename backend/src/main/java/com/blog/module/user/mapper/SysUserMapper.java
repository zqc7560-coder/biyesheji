package com.blog.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.module.user.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户 Mapper。M1 建接口骨架；M2 补充 RBAC 聚合查询。
 *
 * <p>为什么聚合查询用注解 SQL 而不写 XML：本项目规范是"单表用 BaseMapper +
 * LambdaQueryWrapper，多表聚合才写 XML"（见 ArticleMapper 注释），但这两条
 * 联表只是取 code 列的只读小查询，XML 收益低；注解 @Select 让 SQL 与调用点
 * 同屏，评审 RBAC 链路时不用翻文件。SQL 兼容 MySQL 8 与 H2(MODE=MySQL)：
 * 只用标准 JOIN + DISTINCT，无方言函数。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 查用户拥有的角色码（user_role → sys_role 联查）。
     * 登录时聚合一次写进 JWT 声明（《数据库设计.md》§3.5"避免每请求查库"）。
     * deleted=0 条件必须手写：原生 SQL 不经过 MP 逻辑删除插件的自动拼接。
     */
    @Select("""
            SELECT r.role_code FROM user_role ur
            JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    /**
     * 查用户聚合权限点（user_role → role_permission → sys_permission 三级联查）。
     * DISTINCT：多角色持有同一权限点时去重（如 ADMIN 兼 USER 角色）。
     */
    @Select("""
            SELECT DISTINCT p.perm_code FROM user_role ur
            JOIN role_permission rp ON rp.role_id = ur.role_id
            JOIN sys_permission p ON p.id = rp.permission_id AND p.deleted = 0
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectPermCodesByUserId(@Param("userId") Long userId);
}
