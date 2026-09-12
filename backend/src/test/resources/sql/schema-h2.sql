-- =====================================================================
-- H2（MODE=MySQL）测试建表脚本 —— 与《数据库设计.md》§5 DDL 保持同步。
-- M0 骨架期先落地 article + sys_user（供 ORM 插件冒烟测试），
-- M1 完成全部 15 表并与此处逐表核对。
-- 与 MySQL 版的差异（H2 不支持，测试语义不受影响）：
--   1) 去掉 UNSIGNED（H2 2.x 不支持，BIGINT 足够）
--   2) 去掉 FULLTEXT KEY ngram 全文索引（搜索链路是 M3/M4 功能，测试不依赖）
--   3) updated_at 去掉 ON UPDATE CURRENT_TIMESTAMP（H2 语法不同，改由应用层维护）
-- =====================================================================

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
