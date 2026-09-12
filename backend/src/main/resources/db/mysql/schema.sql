-- ============================================================
-- 个人博客系统 blog_db 建表脚本（MySQL 8.0+）—— M1 数据库初始化
-- 执行方式: mysql -h127.0.0.1 -uroot -p < schema.sql
-- 设计依据: 《docs/数据库设计.md》§5 DDL（15 表），本文件与其逐表对应；
--          唯一的 M1 修订：ai_config 增加 is_builtin 列（任务书要求内置模型
--          禁止删除的落地字段），已同步回写设计文档 §3.11/§5。
-- 约定（详见设计文档 §1）：
--   1) 全部表 utf8mb4 + InnoDB；主键 BIGINT UNSIGNED 自增（课设演示可读性优先于雪花）；
--   2) 公共审计字段 created_at/updated_at/deleted，逻辑删除由 MyBatis-Plus @TableLogic 接管；
--   3) 外键关系只建索引不建物理外键——逻辑删除与将来分库演进友好，完整性走应用层。
-- ============================================================
CREATE DATABASE IF NOT EXISTS blog_db
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE blog_db;

-- 1. 用户表：username/email 双唯一键是注册接口 1101/1102 错误的底层保障
CREATE TABLE sys_user (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  username      VARCHAR(50)  NOT NULL COMMENT '登录名',
  password      VARCHAR(100) NOT NULL COMMENT 'BCrypt散列',
  nickname      VARCHAR(50)  NOT NULL COMMENT '昵称',
  email         VARCHAR(100) NOT NULL COMMENT '邮箱',
  avatar        VARCHAR(255) NULL COMMENT '头像URL',
  bio           VARCHAR(500) NULL COMMENT '个人简介',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常0禁用',
  last_login_at DATETIME     NULL COMMENT '最后登录时间',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '0未删1已删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  UNIQUE KEY uk_email (email),
  KEY idx_status_deleted (status, deleted)
) ENGINE=InnoDB COMMENT='用户表';

-- 2. 角色表：内置 ROLE_USER / ROLE_ADMIN 两行，由 data.sql 写入
CREATE TABLE sys_role (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  role_code  VARCHAR(30) NOT NULL COMMENT '如ROLE_ADMIN',
  role_name  VARCHAR(50) NOT NULL,
  description VARCHAR(200) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB COMMENT='角色表';

-- 3. 权限表：权限点粒度到“资源:动作”，@PreAuthorize 按 perm_code 判定
CREATE TABLE sys_permission (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  perm_code  VARCHAR(50) NOT NULL COMMENT '权限点:article:save/ai:config',
  perm_name  VARCHAR(50) NOT NULL,
  module     VARCHAR(30) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_perm_code (perm_code), KEY idx_module (module)
) ENGINE=InnoDB COMMENT='权限表';

-- 4. 用户角色关联：多对多（一人可多角色），注册默认 ROLE_USER
CREATE TABLE user_role (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id    BIGINT UNSIGNED NOT NULL,
  role_id    BIGINT UNSIGNED NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE=InnoDB COMMENT='用户角色关联表';

-- 5. 角色权限关联：登录时按 4+5 两表聚合权限点写入 JWT 声明，避免每请求查库
CREATE TABLE role_permission (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  role_id       BIGINT UNSIGNED NOT NULL,
  permission_id BIGINT UNSIGNED NOT NULL,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_role_perm (role_id, permission_id)
) ENGINE=InnoDB COMMENT='角色权限关联表';

-- 6. 分类表：两级结构（parent_id=0 为顶级），article_count 冗余计数保存文章时维护
CREATE TABLE category (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name          VARCHAR(50) NOT NULL,
  slug          VARCHAR(60) NULL,
  parent_id     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0为顶级',
  sort_order    INT NOT NULL DEFAULT 0,
  description   VARCHAR(200) NULL,
  article_count INT NOT NULL DEFAULT 0 COMMENT '冗余统计',
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_name (name), KEY idx_parent_sort (parent_id, sort_order)
) ENGINE=InnoDB COMMENT='文章分类表';

-- 7. 标签表：AI 生成标签经合法化过滤后才允许入库（见《AI模块设计.md》§5.3）
CREATE TABLE tag (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name          VARCHAR(30) NOT NULL,
  color         VARCHAR(20) NULL DEFAULT '#409EFF',
  article_count INT NOT NULL DEFAULT 0 COMMENT '标签云权重',
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_name (name)
) ENGINE=InnoDB COMMENT='标签表';

-- 8. 文章表：本项目核心表。
--    idx_status_publish 是前台列表主查询索引（status+deleted+发布时间倒序）；
--    FULLTEXT ... WITH PARSER ngram 支撑中文搜索（设计文档 §3.6；ngram_token_size 默认 2，
--    预研 D-9 要求初始化即带 ngram，避免建库后重建索引返工）。
CREATE TABLE article (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id        BIGINT UNSIGNED NOT NULL COMMENT '作者',
  category_id    BIGINT UNSIGNED NULL COMMENT '分类',
  title          VARCHAR(200) NOT NULL,
  slug           VARCHAR(220) NULL,
  cover          VARCHAR(255) NULL COMMENT '封面图',
  summary        VARCHAR(500) NULL COMMENT '摘要(手填或AI)',
  summary_source TINYINT NOT NULL DEFAULT 0 COMMENT '0手动1AI',
  content_md     MEDIUMTEXT NOT NULL COMMENT 'Markdown原文',
  content_text   MEDIUMTEXT NULL COMMENT '纯文本冗余(搜索/AI输入)',
  word_count     INT NOT NULL DEFAULT 0,
  status         TINYINT NOT NULL DEFAULT 0 COMMENT '0草稿1发布2下线',
  top_flag       TINYINT NOT NULL DEFAULT 0 COMMENT '置顶',
  allow_comment  TINYINT NOT NULL DEFAULT 1,
  view_count     BIGINT NOT NULL DEFAULT 0 COMMENT 'Redis定时回写',
  like_count     INT NOT NULL DEFAULT 0,
  comment_count  INT NOT NULL DEFAULT 0,
  published_at   DATETIME NULL,
  version        INT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted        TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_slug (slug),
  KEY idx_user_status (user_id, status, deleted),
  KEY idx_status_publish (status, deleted, published_at DESC),
  KEY idx_category (category_id),
  FULLTEXT KEY ft_title_content (title, content_text) WITH PARSER ngram
) ENGINE=InnoDB COMMENT='文章表';

-- 9. 文章标签关联：保存文章时先 DELETE 后批量 INSERT（同一事务）
CREATE TABLE article_tag (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  article_id BIGINT UNSIGNED NOT NULL,
  tag_id     BIGINT UNSIGNED NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_article_tag (article_id, tag_id), KEY idx_tag (tag_id)
) ENGINE=InnoDB COMMENT='文章标签关联表';

-- 10. 评论表：强制两级（parent_id=0 顶级；回复的回复仍挂顶级下）
CREATE TABLE comment (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  article_id   BIGINT UNSIGNED NOT NULL,
  user_id      BIGINT UNSIGNED NOT NULL,
  parent_id    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0顶级,两级结构',
  reply_to_uid BIGINT UNSIGNED NULL COMMENT '被回复用户',
  content      VARCHAR(1000) NOT NULL COMMENT '纯文本',
  status       TINYINT NOT NULL DEFAULT 0 COMMENT '0待审1通过2屏蔽',
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted      TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_article_status (article_id, status, deleted, created_at DESC),
  KEY idx_user (user_id)
) ENGINE=InnoDB COMMENT='评论表';

-- 11. AI 配置表（★课题重点：管理员统一配置大模型 API）
--     api_key_cipher 存 AES-256-GCM 密文（Base64, IV+密文+Tag），明文 Key 永不入库/入 git；
--     is_builtin 为 M1 按任务书补充：内置两模型（DeepSeek/Qwen）禁止删除，只允许启停/设默认。
CREATE TABLE ai_config (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name             VARCHAR(50) NOT NULL COMMENT '配置名',
  provider         VARCHAR(30) NOT NULL DEFAULT 'openai_compatible',
  base_url         VARCHAR(200) NOT NULL COMMENT 'OpenAI兼容Base URL',
  api_key_cipher   VARCHAR(500) NOT NULL COMMENT 'AES-256-GCM密文(Base64,含IV+Tag)',
  api_key_mask     VARCHAR(30) NOT NULL COMMENT '展示掩码 sk-****abcd',
  model            VARCHAR(80) NOT NULL COMMENT '模型名',
  temperature      DECIMAL(3,2) NOT NULL DEFAULT 0.30,
  max_tokens       INT NOT NULL DEFAULT 512,
  timeout_ms       INT NOT NULL DEFAULT 30000,
  summary_prompt   VARCHAR(2000) NULL COMMENT '自定义摘要模板,NULL用内置',
  tag_prompt       VARCHAR(2000) NULL COMMENT '自定义标签模板',
  is_builtin       TINYINT NOT NULL DEFAULT 0 COMMENT '1内置模型禁止删除(0自定义)',
  is_default       TINYINT NOT NULL DEFAULT 0 COMMENT '全站仅1行=1',
  enabled          TINYINT NOT NULL DEFAULT 1,
  created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted          TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_name (name),
  KEY idx_default_enabled (is_default, enabled)
) ENGINE=InnoDB COMMENT='AI大模型API配置表';

-- 12. AI 调用日志：只插不改（成本审计链），model 冗余防配置删除后无法审计
CREATE TABLE ai_call_log (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id           BIGINT UNSIGNED NOT NULL,
  biz_type          VARCHAR(20) NOT NULL COMMENT 'SUMMARY/TAGS/CONNECT_TEST',
  ai_config_id      BIGINT UNSIGNED NOT NULL,
  model             VARCHAR(80) NOT NULL,
  prompt_tokens     INT NULL,
  completion_tokens INT NULL,
  cost_est          DECIMAL(10,6) NULL COMMENT '预估费用(元)',
  latency_ms        INT NOT NULL,
  cache_hit         TINYINT NOT NULL DEFAULT 0,
  status            TINYINT NOT NULL COMMENT '1成功2失败3重试后成功',
  retry_count       TINYINT NOT NULL DEFAULT 0,
  error_msg         VARCHAR(500) NULL,
  content_hash      CHAR(64) NULL COMMENT '请求正文SHA256',
  created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, created_at),
  KEY idx_biz_time (biz_type, created_at),
  KEY idx_status (status)
) ENGINE=InnoDB COMMENT='AI调用日志表(成本审计)';

-- 13. 文件/附件表：白名单 jpg/png/webp/gif、上限 5MB（校验在 M8 上传接口做）
CREATE TABLE file (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id       BIGINT UNSIGNED NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  stored_path   VARCHAR(255) NOT NULL,
  url           VARCHAR(255) NOT NULL,
  file_type     VARCHAR(20) NOT NULL COMMENT 'image/png等',
  file_size     INT UNSIGNED NOT NULL COMMENT '字节',
  storage_type  TINYINT NOT NULL DEFAULT 0 COMMENT '0本地1OSS',
  ref_count     INT NOT NULL DEFAULT 0,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_stored (stored_path), KEY idx_user_time (user_id, created_at)
) ENGINE=InnoDB COMMENT='文件附件表';

-- 14. 系统参数表：启动全量加载进 Redis sys:config 哈希镜像，修改时双删
CREATE TABLE sys_config (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  config_key   VARCHAR(80) NOT NULL,
  config_value VARCHAR(500) NOT NULL,
  config_type  VARCHAR(20) NOT NULL DEFAULT 'string',
  description  VARCHAR(200) NULL,
  updated_by   BIGINT UNSIGNED NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted      TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_key (config_key)
) ENGINE=InnoDB COMMENT='系统参数表';

-- 15. 登录日志：失败也记（user_id 为 NULL），result 区分密码错/禁用/不存在
CREATE TABLE login_log (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id    BIGINT UNSIGNED NULL,
  username   VARCHAR(50) NOT NULL,
  ip         VARCHAR(45) NOT NULL COMMENT '兼容IPv6',
  user_agent VARCHAR(255) NULL,
  result     TINYINT NOT NULL COMMENT '1成功2密码错3禁用4不存在',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_user_time (user_id, created_at), KEY idx_time (created_at)
) ENGINE=InnoDB COMMENT='登录日志表';
