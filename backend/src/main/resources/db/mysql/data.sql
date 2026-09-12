-- ============================================================
-- 个人博客系统 blog_db 种子数据（M1 数据库初始化，配合 schema.sql 执行）
-- 安全红线：本文件绝不出现任何真实 API Key / 密码明文。
--   1) admin 账号口令由后端 CommandLineRunner 在 M2 用 BCrypt 现算生成（设计文档 §5 约定），
--      所以这里不插 sys_user；
--   2) 两个内置 AI 模型的 api_key_cipher 只放占位符，真实 Key 由 M6 启动引导从
--      backend/.env（已被 .gitignore 忽略）读取 → AES-GCM 加密后 UPDATE 回写。
-- 幂等性：全部使用 INSERT IGNORE + 显式主键，重复执行不产生脏数据。
-- ============================================================
USE blog_db;

-- ---------- 角色（显式 id：ROLE_USER=1 / ROLE_ADMIN=2，下方授权 SQL 依赖该编号） ----------
INSERT IGNORE INTO sys_role (id, role_code, role_name, description) VALUES
  (1, 'ROLE_USER',  '普通用户', '注册默认角色：发文、用AI'),
  (2, 'ROLE_ADMIN', '管理员',   '全站治理：用户/内容/审核/配置');

-- ---------- 权限点（与《API接口设计.md》各接口的 @PreAuthorize 一一对应） ----------
INSERT IGNORE INTO sys_permission (id, perm_code, perm_name, module) VALUES
  (1,  'article:save',    '保存文章',   'article'),
  (2,  'article:delete',  '删除文章',   'article'),
  (3,  'ai:use',          '使用AI功能', 'ai'),
  (4,  'ai:config',       '配置AI服务', 'ai'),
  (5,  'ai:log',          '查看AI日志', 'ai'),
  (6,  'user:manage',     '用户管理',   'user'),
  (7,  'comment:audit',   '评论审核',   'comment'),
  (8,  'category:manage', '分类管理',   'category'),
  (9,  'tag:manage',      '标签管理',   'tag'),
  (10, 'config:manage',   '系统配置',   'system');

-- 管理员角色授予全部权限；普通用户仅发文/删文/用AI（最小权限原则）
INSERT IGNORE INTO role_permission (role_id, permission_id)
  SELECT 2, id FROM sys_permission;
INSERT IGNORE INTO role_permission (role_id, permission_id)
  SELECT 1, id FROM sys_permission WHERE perm_code IN ('article:save','article:delete','ai:use');

-- ---------- 系统参数（键名与《数据库设计.md》§3.14 逐字一致） ----------
-- M8 补两枚站点门面参数（/api/system/config 公开白名单键；后台系统设置页可改）。
-- 键名以《数据库设计.md》§3.14 的列举（site_name/logo/备案号/注册开关）为准逐字对齐。
INSERT IGNORE INTO sys_config (config_key, config_value, config_type, description) VALUES
  ('site_name','我的博客','string','站点名称'),
  ('site_logo','','string','站点Logo图片URL（后台上传后经 /api/files/upload 回填）'),
  ('site_icp','','string','ICP备案号（页脚展示，空则不显示）'),
  ('allow_register','true','boolean','是否开放注册'),
  ('comment_audit','false','boolean','评论是否先审后发'),
  ('ai_enabled','true','boolean','AI功能总开关'),
  ('ai_daily_quota','50','number','每用户每日AI调用上限'),
  ('ai_price_per_1k_prompt','1.00','number','输入每千token价格(元)'),
  ('ai_price_per_1k_completion','2.00','number','输出每千token价格(元)');

-- ---------- 默认分类（前台导航种子） ----------
INSERT IGNORE INTO category (id, name, slug, sort_order) VALUES
  (1, '技术', 'tech', 1),
  (2, '生活', 'life', 2),
  (3, '随笔', 'essay', 3);

-- ---------- 内置 AI 模型（课题预设两模型, is_builtin=1 禁止删除；DeepSeek 为默认） ----------
-- api_key_cipher 为占位串（非合法密文，解密会明确报错），提醒未导入真实 Key；
-- 真实 Key 经 AES-GCM 加密后由 M6 的引导逻辑 UPDATE 覆盖，明文 Key 只存在于本地 .env。
INSERT IGNORE INTO ai_config
  (id, name, provider, base_url, api_key_cipher, api_key_mask, model,
   temperature, max_tokens, timeout_ms, is_builtin, is_default, enabled) VALUES
  (1, 'DeepSeek-V4-Flash', 'deepseek',  'https://api.deepseek.com',
      '__PENDING_KEY_IMPORT__', '未导入Key', 'deepseek-v4-flash',
      0.30, 512, 30000, 1, 1, 1),
  (2, 'Qwen3.8-Flash',     'dashscope', 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      '__PENDING_KEY_IMPORT__', '未导入Key', 'qwen3.8-flash',
      0.30, 512, 30000, 1, 0, 1);

-- ---------- admin 账号（部署时由后端 CommandLineRunner 生成真实 BCrypt 散列后插入，
-- 此处仅保留设计文档 §5 的注释占位，上线前必须重算） ----------
-- INSERT INTO sys_user (username,password,nickname,email) VALUES ('admin','$2a$10$...','超级管理员','admin@blog.local');
