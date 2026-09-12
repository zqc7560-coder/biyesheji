package com.blog.module.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录日志实体 —— 对应《数据库设计.md》§3.15 login_log。
 *
 * <p>设计意图：登录失败也要留痕（user_id 为 NULL + result 区分 2密码错/3禁用/4账号不存在），
 * 这是暴力破解排查与安全审计的数据源；因此本表只有 created_at、无 updated/deleted——
 * 日志行不可变更（append-only）。
 */
@Data
@TableName("login_log")
public class LoginLog {

    /** 主键：数据库自增（《数据库设计.md》§1.2：选自增而非雪花，便于课设演示阅读） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录成功时的用户 ID；失败为 NULL（账号不存在时无从关联） */
    private Long userId;

    /** 尝试的账号名：即使不存在也原样记录（用户名枚举攻击的观测面） */
    private String username;

    /** 客户端 IP：VARCHAR(45) 兼容 IPv6 全文 */
    private String ip;

    /** User-Agent 原文（截断存） */
    private String userAgent;

    /** 1成功 / 2密码错误 / 3账号禁用 / 4账号不存在 */
    private Integer result;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;
}
