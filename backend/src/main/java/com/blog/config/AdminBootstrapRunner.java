package com.blog.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blog.module.user.entity.SysUser;
import com.blog.module.user.entity.UserRole;
import com.blog.module.user.mapper.SysUserMapper;
import com.blog.module.user.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * admin 账号引导（M2）—— 落实《数据库设计.md》§5 与 data.sql 的约定：
 * "admin 口令由后端 CommandLineRunner 生成真实 BCrypt 散列后插入，种子脚本不放明文"。
 *
 * <p>为什么在启动时程序化创建而不是往 data.sql 里写死散列：
 * 1) data.sql 若含固定 BCrypt 串，等于把初始明文口令（及其散列）永久留在 git 历史；
 * 2) 启动生成可以走环境变量 BLOG_ADMIN_PASSWORD 注入，本地默认值仅供课设演示，
 *    部署文档要求首次登录后立即改密（改密会自动作废旧令牌，链路 M2 已闭环）。
 *
 * <p>幂等性：每次启动先查（username=admin 且未删），存在即跳过——重复重启不会
 * 重置管理员密码。注意 MP 查询自动拼 deleted=0：若管理员被逻辑删除，
 * 引导会撞上 uk_username 唯一键，这里捕获后保持沉默（"删掉的 admin"属于
 * 管理员数据治理决策，不该被启动流程复活）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    /** 角色编号常量：ROLE_ADMIN=2（data.sql 显式主键约定） */
    private static final long ROLE_ADMIN_ID = 2L;

    private final SysUserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    /** 初始口令：默认值明文只出现在"本地开发引导"场景（任务书允许），
     *  生产经 BLOG_ADMIN_PASSWORD 注入；日志绝不打印该值 */
    @Value("${blog.admin.init-password:Admin@123}")
    private String initPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "admin"));
        if (exists > 0) {
            return; // 幂等：已有 admin 直接放行，不覆盖口令
        }
        SysUser admin = new SysUser();
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode(initPassword));
        admin.setNickname("超级管理员");
        admin.setEmail("admin@blog.local");
        admin.setStatus(1);
        try {
            userMapper.insert(admin);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.warn("admin 用户名被已删除行占用，跳过引导（如需复活请数据治理介入）");
            return;
        }
        UserRole ur = new UserRole();
        ur.setUserId(admin.getId());
        ur.setRoleId(ROLE_ADMIN_ID);
        userRoleMapper.insert(ur);
        // 只提示"创建了"，不打印口令：启动日志常被收集到文件/平台，口令进日志=泄露
        log.info("已引导创建管理员账号 admin（初始口令取自配置，请尽快登录修改）");
    }
}
