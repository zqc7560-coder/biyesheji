package com.blog.module.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 附件实体 —— 对应《数据库设计.md》§3.13 file 表（类名 FileRecord 而非 File，
 * 避免与 java.io.File 在 import 处混战）。
 * storedPath 落盘相对路径（uploads/yyyy/MM/uuid.ext，重命名防冲突+防路径穿越），
 * url 是可访问相对链接；fileSize 用 Long 对应 MySQL INT UNSIGNED（5MB 上限在 M8 校验）。
 */
@Data
@TableName("file")
public class FileRecord {

    /** 主键：数据库自增（《数据库设计.md》§1.2：选自增而非雪花，便于课设演示阅读） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上传者 sys_user.id */
    private Long userId;

    /** 原始文件名（仅展示/下载名用，不参与落盘命名） */
    private String originalName;

    /** 磁盘相对路径：uk_stored 唯一 */
    private String storedPath;

    /** 访问 URL（/uploads/... 静态映射直出） */
    private String url;

    /** MIME 主类型：image/png 等（白名单 jpg/png/webp/gif） */
    private String fileType;

    /** 字节数 */
    private Long fileSize;

    /** 0本地磁盘 / 1 OSS（本期只做本地，列预留） */
    private Integer storageType;

    /** 被引用次数：图片清理依据（P2） */
    private Integer refCount;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP，insert 时省略即可 */
    private LocalDateTime createdAt;

    /** 更新时间：MySQL 由 ON UPDATE 维护；H2 测试库由应用层负责（差异见 schema-h2.sql 头注释） */
    private LocalDateTime updatedAt;

    /** 逻辑删除标记（全局配置 logic-delete-field=deleted，所有 MP 查询自动拼 deleted=0） */
    @TableLogic
    private Integer deleted;
}
