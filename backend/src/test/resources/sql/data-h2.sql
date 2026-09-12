-- =====================================================================
-- H2 测试种子数据：两个测试用户（口令散列是 BCrypt 对明文 "password" 的预计算值，
-- 即 Spring Security 官方文档示例散列）。
-- 说明：M2 认证测试若要断言"密码校验通过"，应在测试内用 BCryptPasswordEncoder
-- 现算现比，不在 SQL 里伪造口令语义——此处种子只保证用户行存在与唯一键生效。
-- id 显式给出便于测试按 id 引用；H2 AUTO_INCREMENT 会自动跳过已占用值。
-- =====================================================================
INSERT INTO sys_user (id, username, password, nickname, email, status) VALUES
  (1, 'testuser', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '测试用户', 'test@blog.local', 1),
  (2, 'other',    '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '另一位用户', 'other@blog.local', 1);
