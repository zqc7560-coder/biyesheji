-- =====================================================================
-- H2 测试种子数据 —— 与生产种子（src/main/resources/db/mysql/data.sql）逐行对齐，
-- M1 起：角色/权限/授权/系统参数/默认分类/内置 AI 模型 全部进测试库，
-- 让 M2（认证）与 M6（AI 配置）的测试直接跑在真实种子语义上。
-- 差异说明：
--   1) 生产版依赖 USE blog_db 与 INSERT IGNORE 幂等；H2 每次 JVM 全新建库，
--      仍统一用 INSERT IGNORE 保持两库脚本形态一致、可互相对照；
--   2) 保留 M0 的两个测试用户（口令为 BCrypt("password") 预计算散列），
--      id 显式给出便于测试按 id 引用。
-- 安全红线：ai_config 只放占位密文，任何环境（含测试库）都不出现真实 Key。
-- =====================================================================

-- 全部 INSERT IGNORE：同 JVM 里多个 Spring 上下文共享 blog_test（DB_CLOSE_DELAY=-1），
-- 第二个上下文启动会重放本脚本，非幂等写法会主键冲突炸掉容器。
INSERT IGNORE INTO sys_user (id, username, password, nickname, email, status) VALUES
  (1, 'testuser', '$2a$10$wLikrZ4XOUcypnC6TdY/u.6ga5JcEykuc16AfBxh336TwJC2mq2CG', '测试用户', 'test@blog.local', 1),
  (2, 'other',    '$2a$10$wLikrZ4XOUcypnC6TdY/u.6ga5JcEykuc16AfBxh336TwJC2mq2CG', '另一位用户', 'other@blog.local', 1);

-- 角色：显式 id 与生产 data.sql 一致（ROLE_USER=1 / ROLE_ADMIN=2，授权 SQL 依赖该编号）
INSERT IGNORE INTO sys_role (id, role_code, role_name, description) VALUES
  (1, 'ROLE_USER',  '普通用户', '注册默认角色：发文、用AI'),
  (2, 'ROLE_ADMIN', '管理员',   '全站治理：用户/内容/审核/配置');

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

-- 管理员全权限；普通用户最小三权限（与生产种子逐字一致，测试 RBAC 时直接可用）
INSERT IGNORE INTO role_permission (role_id, permission_id)
  SELECT 2, id FROM sys_permission;
INSERT IGNORE INTO role_permission (role_id, permission_id)
  SELECT 1, id FROM sys_permission WHERE perm_code IN ('article:save','article:delete','ai:use');

INSERT IGNORE INTO sys_config (config_key, config_value, config_type, description) VALUES
  ('site_name','我的博客','string','站点名称'),
  ('allow_register','true','boolean','是否开放注册'),
  ('comment_audit','false','boolean','评论是否先审后发'),
  ('ai_enabled','true','boolean','AI功能总开关'),
  ('ai_daily_quota','50','number','每用户每日AI调用上限'),
  ('ai_price_per_1k_prompt','1.00','number','输入每千token价格(元)'),
  ('ai_price_per_1k_completion','2.00','number','输出每千token价格(元)');

INSERT IGNORE INTO category (id, name, slug, sort_order) VALUES
  (1, '技术', 'tech', 1),
  (2, '生活', 'life', 2),
  (3, '随笔', 'essay', 3);

-- 内置 AI 模型两行：is_builtin=1 禁止删除；DeepSeek 为默认（is_default=1）；
-- api_key_cipher 为占位串（非合法密文），真实 Key 由 M6 从本地 .env 加密回写。
INSERT IGNORE INTO ai_config
  (id, name, provider, base_url, api_key_cipher, api_key_mask, model,
   temperature, max_tokens, timeout_ms, is_builtin, is_default, enabled) VALUES
  (1, 'DeepSeek-V4-Flash', 'deepseek',  'https://api.deepseek.com',
      '__PENDING_KEY_IMPORT__', '未导入Key', 'deepseek-v4-flash',
      0.30, 512, 30000, 1, 1, 1),
  (2, 'Qwen3.8-Flash',     'dashscope', 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      '__PENDING_KEY_IMPORT__', '未导入Key', 'qwen3.8-flash',
      0.30, 512, 30000, 1, 0, 1);

-- ---------- M2 测试 RBAC 挂载：给两个种子用户接上角色（生产库里注册/引导流程会写
-- user_role，测试种子直插 sys_user 绕过了流程，这里补齐授权链，
-- 否则 M2 的登录测试拿不到任何角色权限点）----------
-- testuser(id=1) → ROLE_USER；other(id=2) → ROLE_ADMIN（越权矩阵测试需要现成的管理员样本）
INSERT IGNORE INTO user_role (user_id, role_id) VALUES (1, 1), (2, 2);

-- admin 账号不在种子中预插：M2 的 AdminBootstrapRunner 在每次上下文启动时
-- "查无 admin 则创建"，测试链路正好覆盖这条生产同款引导路径
-- （口令取 blog.admin.init-password，见 application-test.yml）。

-- 禁用账号样本（登录 1003 用例）：口令 password123 的 BCrypt 散列（单向产物，非敏感，可入库）
INSERT IGNORE INTO sys_user (id, username, password, nickname, email, status) VALUES
  (3, 'disabled', '$2a$10$wLikrZ4XOUcypnC6TdY/u.6ga5JcEykuc16AfBxh336TwJC2mq2CG', '被封号的', 'banned@blog.local', 0);
INSERT IGNORE INTO user_role (user_id, role_id) VALUES (3, 1);
