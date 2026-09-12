-- =====================================================================
-- H2（MODE=MySQL）测试建表脚本 —— 与生产 schema（src/main/resources/db/mysql/schema.sql，
-- 依据《数据库设计.md》§5）逐表同步维护，M1 已补齐全部 15 表。
-- 与 MySQL 版的差异（H2 不支持或语义不同，对测试无影响）：
--   1) 去掉 UNSIGNED（H2 2.x 不支持，BIGINT 取值范围足够）
--   2) 去掉 FULLTEXT KEY ... WITH PARSER ngram（全文搜索是 M3 功能，测试链路不依赖）
--   3) updated_at 去掉 ON UPDATE CURRENT_TIMESTAMP（H2 语法不同，由应用层维护）
--   4) 唯一键从表内 UNIQUE KEY 改为表外 CONSTRAINT，且名字全局唯一
--      （MySQL 索引名只需表内唯一，H2 约束名要求 schema 内唯一，如 uk_category_name）
-- =====================================================================

-- 1. 用户表
CREATE TABLE IF NOT EXISTS sys_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  username      VARCHAR(50)  NOT NULL COMMENT '登录名',
  password      VARCHAR(100) NOT NULL COMMENT 'BCrypt散列',
  nickname      VARCHAR(50)  NOT NULL COMMENT '昵称',
  email         VARCHAR(100) NOT NULL COMMENT '邮箱',
  avatar        VARCHAR(255) NULL COMMENT '头像URL',
  bio           VARCHAR(500) NULL COMMENT '个人简介',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常0禁用',
  last_login_at TIMESTAMP    NULL COMMENT '最后登录时间',
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '0未删1已删',
  PRIMARY KEY (id),
  CONSTRAINT uk_username UNIQUE (username),
  CONSTRAINT uk_email UNIQUE (email)
);
CREATE INDEX IF NOT EXISTS idx_user_status_deleted ON sys_user (status, deleted);

-- 2. 角色表
CREATE TABLE IF NOT EXISTS sys_role (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  role_code   VARCHAR(30) NOT NULL COMMENT '如ROLE_ADMIN',
  role_name   VARCHAR(50) NOT NULL,
  description VARCHAR(200) NULL,
  created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted     TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_role_code UNIQUE (role_code)
);

-- 3. 权限表
CREATE TABLE IF NOT EXISTS sys_permission (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  perm_code  VARCHAR(50) NOT NULL COMMENT '权限点:article:save/ai:config',
  perm_name  VARCHAR(50) NOT NULL,
  module     VARCHAR(30) NOT NULL,
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted    TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_perm_code UNIQUE (perm_code)
);
CREATE INDEX IF NOT EXISTS idx_perm_module ON sys_permission (module);

-- 4. 用户角色关联
CREATE TABLE IF NOT EXISTS user_role (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  user_id    BIGINT   NOT NULL,
  role_id    BIGINT   NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_user_role UNIQUE (user_id, role_id)
);

-- 5. 角色权限关联
CREATE TABLE IF NOT EXISTS role_permission (
  id            BIGINT   NOT NULL AUTO_INCREMENT,
  role_id       BIGINT   NOT NULL,
  permission_id BIGINT   NOT NULL,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_role_perm UNIQUE (role_id, permission_id)
);

-- 6. 分类表
CREATE TABLE IF NOT EXISTS category (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  name          VARCHAR(50) NOT NULL,
  slug          VARCHAR(60) NULL,
  parent_id     BIGINT      NOT NULL DEFAULT 0 COMMENT '0为顶级',
  sort_order    INT         NOT NULL DEFAULT 0,
  description   VARCHAR(200) NULL,
  article_count INT         NOT NULL DEFAULT 0 COMMENT '冗余统计',
  created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted       TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_category_name UNIQUE (name)
);
CREATE INDEX IF NOT EXISTS idx_category_parent_sort ON category (parent_id, sort_order);

-- 7. 标签表
CREATE TABLE IF NOT EXISTS tag (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  name          VARCHAR(30) NOT NULL,
  color         VARCHAR(20) NULL DEFAULT '#409EFF',
  article_count INT         NOT NULL DEFAULT 0 COMMENT '标签云权重',
  created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted       TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_tag_name UNIQUE (name)
);

-- 8. 文章表
CREATE TABLE IF NOT EXISTS article (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  user_id        BIGINT       NOT NULL COMMENT '作者',
  category_id    BIGINT       NULL COMMENT '分类',
  title          VARCHAR(200) NOT NULL,
  slug           VARCHAR(220) NULL,
  cover          VARCHAR(255) NULL COMMENT '封面图',
  summary        VARCHAR(500) NULL COMMENT '摘要(手填或AI)',
  summary_source TINYINT      NOT NULL DEFAULT 0 COMMENT '0手动1AI',
  content_md     CLOB         NOT NULL COMMENT 'Markdown原文',
  content_text   CLOB         NULL COMMENT '纯文本冗余',
  word_count     INT          NOT NULL DEFAULT 0,
  status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0草稿1发布2下线',
  top_flag       TINYINT      NOT NULL DEFAULT 0 COMMENT '置顶',
  allow_comment  TINYINT      NOT NULL DEFAULT 1,
  view_count     BIGINT       NOT NULL DEFAULT 0,
  like_count     INT          NOT NULL DEFAULT 0,
  comment_count  INT          NOT NULL DEFAULT 0,
  published_at   TIMESTAMP    NULL,
  version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁',
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted        TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_article_slug UNIQUE (slug)
);
CREATE INDEX IF NOT EXISTS idx_article_user_status ON article (user_id, status, deleted);
CREATE INDEX IF NOT EXISTS idx_article_status_publish ON article (status, deleted, published_at);
CREATE INDEX IF NOT EXISTS idx_article_category ON article (category_id);

-- 9. 文章标签关联
CREATE TABLE IF NOT EXISTS article_tag (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  article_id BIGINT   NOT NULL,
  tag_id     BIGINT   NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_article_tag UNIQUE (article_id, tag_id)
);
CREATE INDEX IF NOT EXISTS idx_articletag_tag ON article_tag (tag_id);

-- 10. 评论表
CREATE TABLE IF NOT EXISTS comment (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  article_id   BIGINT        NOT NULL,
  user_id      BIGINT        NOT NULL,
  parent_id    BIGINT        NOT NULL DEFAULT 0 COMMENT '0顶级,两级结构',
  reply_to_uid BIGINT        NULL COMMENT '被回复用户',
  content      VARCHAR(1000) NOT NULL COMMENT '纯文本',
  status       TINYINT       NOT NULL DEFAULT 0 COMMENT '0待审1通过2屏蔽',
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted      TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
-- 索引与生产 DDL 对齐（生产里 created_at 用 DESC，H2 测试不验证降序索引语义）
CREATE INDEX IF NOT EXISTS idx_comment_article_status ON comment (article_id, status, deleted, created_at);
CREATE INDEX IF NOT EXISTS idx_comment_user ON comment (user_id);

-- 11. AI 配置表（★课题重点）
CREATE TABLE IF NOT EXISTS ai_config (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  name             VARCHAR(50)  NOT NULL COMMENT '配置名',
  provider         VARCHAR(30)  NOT NULL DEFAULT 'openai_compatible',
  base_url         VARCHAR(200) NOT NULL COMMENT 'OpenAI兼容Base URL',
  api_key_cipher   VARCHAR(500) NOT NULL COMMENT 'AES-256-GCM密文(Base64,含IV+Tag)',
  api_key_mask     VARCHAR(30)  NOT NULL COMMENT '展示掩码 sk-****abcd',
  model            VARCHAR(80)  NOT NULL COMMENT '模型名',
  temperature      DECIMAL(3,2) NOT NULL DEFAULT 0.30,
  max_tokens       INT          NOT NULL DEFAULT 512,
  timeout_ms       INT          NOT NULL DEFAULT 30000,
  summary_prompt   VARCHAR(2000) NULL COMMENT '自定义摘要模板,NULL用内置',
  tag_prompt       VARCHAR(2000) NULL COMMENT '自定义标签模板',
  is_builtin       TINYINT      NOT NULL DEFAULT 0 COMMENT '1内置模型禁止删除(0自定义)',
  is_default       TINYINT      NOT NULL DEFAULT 0 COMMENT '全站仅1行=1',
  enabled          TINYINT      NOT NULL DEFAULT 1,
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted          TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_ai_config_name UNIQUE (name)
);
CREATE INDEX IF NOT EXISTS idx_aiconfig_default_enabled ON ai_config (is_default, enabled);

-- 12. AI 调用日志表（只插不改）
CREATE TABLE IF NOT EXISTS ai_call_log (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  user_id           BIGINT        NOT NULL,
  biz_type          VARCHAR(20)   NOT NULL COMMENT 'SUMMARY/TAGS/CONNECT_TEST',
  ai_config_id      BIGINT        NOT NULL,
  model             VARCHAR(80)   NOT NULL,
  prompt_tokens     INT           NULL,
  completion_tokens INT           NULL,
  cost_est          DECIMAL(10,6) NULL COMMENT '预估费用(元)',
  latency_ms        INT           NOT NULL,
  cache_hit         TINYINT       NOT NULL DEFAULT 0,
  status            TINYINT       NOT NULL COMMENT '1成功2失败3重试后成功',
  retry_count       TINYINT       NOT NULL DEFAULT 0,
  error_msg         VARCHAR(500)  NULL,
  content_hash      CHAR(64)      NULL COMMENT '请求正文SHA256',
  created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_aicalllog_user_time ON ai_call_log (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_aicalllog_biz_time ON ai_call_log (biz_type, created_at);
CREATE INDEX IF NOT EXISTS idx_aicalllog_status ON ai_call_log (status);

-- 13. 文件/附件表
CREATE TABLE IF NOT EXISTS file (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  user_id       BIGINT       NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  stored_path   VARCHAR(255) NOT NULL,
  url           VARCHAR(255) NOT NULL,
  file_type     VARCHAR(20)  NOT NULL COMMENT 'image/png等',
  file_size     BIGINT       NOT NULL COMMENT '字节(MySQL侧INT UNSIGNED,H2用BIGINT等价覆盖)',
  storage_type  TINYINT      NOT NULL DEFAULT 0 COMMENT '0本地1OSS',
  ref_count     INT          NOT NULL DEFAULT 0,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_file_stored UNIQUE (stored_path)
);
CREATE INDEX IF NOT EXISTS idx_file_user_time ON file (user_id, created_at);

-- 14. 系统配置表
CREATE TABLE IF NOT EXISTS sys_config (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  config_key   VARCHAR(80)  NOT NULL,
  config_value VARCHAR(500) NOT NULL,
  config_type  VARCHAR(20)  NOT NULL DEFAULT 'string',
  description  VARCHAR(200) NULL,
  updated_by   BIGINT       NULL,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted      TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_config_key UNIQUE (config_key)
);

-- 15. 登录日志表
CREATE TABLE IF NOT EXISTS login_log (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  user_id    BIGINT      NULL,
  username   VARCHAR(50) NOT NULL,
  ip         VARCHAR(45) NOT NULL COMMENT '兼容IPv6',
  user_agent VARCHAR(255) NULL,
  result     TINYINT     NOT NULL COMMENT '1成功2密码错3禁用4不存在',
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_loginlog_user_time ON login_log (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_loginlog_time ON login_log (created_at);
