package com.blog.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.module.user.entity.SysUser;

/**
 * 用户 Mapper（M1 落地数据访问层；注册/登录业务在 M2 AuthService 展开）。
 * 规范同 ArticleMapper：单表用 BaseMapper + LambdaQueryWrapper，多表聚合才写 XML。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {
}
