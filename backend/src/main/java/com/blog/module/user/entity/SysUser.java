package com.blog.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体 —— 对应《数据库设计.md》§3.1 sys_user。
 *
 * <p>username/email 的唯一键（uk_username/uk_email）是注册接口 1101/1102 错误的底层保障：
 * Service 层先查重是"友好提示"，唯一键兜底才是并发下的最后防线（查后插的竞态窗口靠它关闭）。
 * password 只存 BCrypt 散列（$2a$10$... 60 字符，列长 100 留余量），任何日志/序列化场景
 * 都不得输出该字段——M2 会在 DTO 层做白名单出参。
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 主键：数据库自增（《数据库设计.md》§1.2：选自增而非雪花，便于课设演示阅读） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名：4~20 位，注册时 trim 后大小写敏感判重 */
    private String username;

    /** BCrypt 散列（M2 认证模块写入；测试种子用官方示例散列代表明文 password） */
    private String password;

    /** 昵称：展示名，允许重复（区别于 username 的唯一性） */
    private String nickname;

    /** 邮箱：唯一，与 username 共同构成登录凭证 */
    private String email;

    /** 头像 URL：指向 file 表相对路径（上传链路 M8 落地） */
    private String avatar;

    /** 个人简介（用户中心可编辑） */
    private String bio;

    /** 1 正常 / 0 禁用：管理员启禁账号；禁用用户登录返回 1003 且发言被拦截（M5 读取） */
    private Integer status;

    /** 最后登录时间：登录成功后回写，仪表盘统计活跃用 */
    private LocalDateTime lastLoginAt;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP，insert 时省略即可 */
    private LocalDateTime createdAt;

    /** 更新时间：MySQL 由 ON UPDATE 维护；H2 测试库由应用层负责（差异见 schema-h2.sql 头注释） */
    private LocalDateTime updatedAt;

    /** 逻辑删除标记（全局配置 logic-delete-field=deleted，所有 MP 查询自动拼 deleted=0） */
    @TableLogic
    private Integer deleted;
}
