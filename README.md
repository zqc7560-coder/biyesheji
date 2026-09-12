# 个人博客系统（Personal Blog System）— 毕业设计项目总览

> 软件工程专业毕业设计 ｜ 版本 v1.0 ｜ 更新日期 2026-09-12

## 一、选题背景

个人博客是软件工程专业最经典、也是最容易被做"浅"的选题。本课题在传统的"文章发布 + 评论互动"博客功能之上，引入两个具有工程价值的增强点：

1. **AI 辅助创作能力**：接入大语言模型（LLM），实现「AI 自动生成文章摘要」「AI 自动生成文章标签」。区别于"人手一个 API Key"的玩具式接入，本系统采用**管理员统一配置大模型 API、后端封装统一调用服务、普通用户零配置直接 usage** 的三层架构——Key 不落前端、不入明文、可审计、可限流、可降级，这也是本设计的重点与答辩核心亮点。
2. **完整的企业级工程实践**：JWT 认证授权、Redis 缓存与热点数据加速、Markdown 编辑/渲染与安全过滤、逻辑删除、统一响应体与错误码、Docker Compose 一键部署，覆盖一门后端 + 一门前端 + 数据库 + 缓存 + 外部 API 集成的完整技术链。

## 二、技术栈一览

| 层次 | 技术 | 版本建议 | 用途 |
|------|------|----------|------|
| 前端框架 | Vue 3（Composition API） | 3.4+ | 页面与交互 |
| 前端构建 | Vite | 5.x | 开发服务器与打包 |
| UI 组件库 | Element Plus | 2.x | 表单、表格、弹窗等 |
| 前端辅助 | Vue Router / Pinia / Axios | — | 路由 / 状态管理 / HTTP |
| Markdown | md-editor-v3（编辑）+ markdown-it + DOMPurify（渲染与防 XSS） | — | 编辑与渲染 |
| 后端框架 | Spring Boot | 3.2+（Java 17） | Web 服务 |
| ORM | MyBatis-Plus | 3.5.x | 数据访问 |
| 数据库 | MySQL | 8.0 | 持久化 |
| 缓存 | Redis | 7.x | 热点缓存、AI 结果缓存、限流计数 |
| 认证 | JWT（jjwt 0.12.x）+ BCrypt | — | 无状态登录态与密码散列 |
| 接口文档 | SpringDoc OpenAPI + Knife4j | — | 在线调试 |
| AI 能力 | OpenAI 兼容 Chat Completions 协议（可对接 DeepSeek / 通义千问 / Kimi / OpenAI） | — | 摘要生成、标签生成 |
| 部署 | Docker + Docker Compose + Nginx | — | 容器化交付 |

## 三、AI 架构（设计重点）

```
管理员（后台）──配置──▶ ai_config 表（Key 密文存储）
                              │
普通用户（前台）─「生成摘要/生成标签」▶ 后端统一大模型调用服务 LlmService
                              │            （缓存 → 限流 → 线程池异步 → 重试/降级 → 日志）
                              ▼
                    外部大模型 API（Base URL + Model + Key）
```

- 管理员：在后台维护 API Key（AES-GCM 加密入库）、Base URL、模型名、超时、配额；前台接口**永不回传明文 Key**。
- 后端：屏蔽厂商差异（统一走 OpenAI 兼容协议），集中处理提示词模板、超时重试、Redis 结果缓存、按用户限流、调用日志与成本统计、异常降级。
- 用户：前台点击按钮即得结果，无需感知任何 Key 的存在。

## 四、目录结构说明

```
D:\毕业设计\
├── README.md            本文件：项目总览与阅读指引
├── 项目计划.md           核心交付物：目标范围、WBS、8 阶段计划（14 周）、里程碑、风险、难点预研
├── backend\             Spring Boot 3 后端（blog-server）：src/main/java/com/blog/{common,config,module,job}
├── frontend\            Vue3 + Vite 前端（blog-web）：src/{api,router,stores,utils,views}
└── docs\
    ├── 需求分析.md       角色-用例、功能/非功能需求、用例图（Mermaid）、需求优先级
    ├── 系统设计.md       分层架构图、模块划分、三大核心时序图（登录/发文/AI 摘要）、选型理由
    ├── 数据库设计.md     15 张表字段级设计 + ER 图 + 完整 MySQL DDL
    ├── API接口设计.md    RESTful 接口清单（按模块分组）、统一响应体、错误码表
    ├── AI模块设计.md     ★ 设计重点：Key 加密、统一调用服务、提示词、缓存/限流/异步/降级、安全隐私
    ├── 部署与测试.md     Docker Compose 部署方案、三层测试策略、验收清单
    └── 进度.md           模块清单与迭代日志（每轮开发后由 Qwen Bot 更新）
```

## 五、如何阅读本套文档

建议按答辩答辩的逻辑链阅读（即"为什么做 → 做什么 → 怎么做 → 怎么验"）：

1. **README.md**（本文）：5 分钟了解全貌。
2. **docs/需求分析.md**：明确系统边界与优先级，答辩中回答"做了什么"。
3. **docs/系统设计.md**：整体架构与核心流程，答辩中回答"怎么设计的"。
4. **docs/数据库设计.md** + **docs/API接口设计.md**：实现层两大契约，开发时直接照此建表、照此联调。
5. **docs/AI模块设计.md**：本课题的差异化重点，答辩时建议展开 8~10 分钟。
6. **docs/部署与测试.md**：证明系统是"可运行、可验证"的工程而非 PPT。
7. **项目计划.md**：过程管理文档，对应开题报告 / 中期检查的进度承诺，答辩中回答"怎么排期、风险怎么控"。

各文档之间通过相同的表名、接口路径、模块名互相引用（如 `ai_config`、`/api/ai/summary`），可交叉检索。

## 六、快速开始（工程搭建后）

```bash
# 1. 基础设施：本机 MySQL(127.0.0.1:3306) 与 Redis(127.0.0.1:6379) 运行中
#    执行 docs/数据库设计.md §5 的 DDL 建库 blog_db（M1 提供独立 schema.sql）
# 2. 后端（Java 17+；本机命令用 Maven 全路径，详见 docs/进度.md 环境约束）
cd backend && mvn spring-boot:run
# 3. 前端（Node 20+）
cd frontend && npm install && npm run dev
# 4. 访问 http://localhost:5173 （/api 由 Vite 代理到 8080），管理员 admin / Admin@123（M2 落地后生效）
# 5. 测试：后端 mvn test（H2 内存库，无需任何外部中间件）；前端 npm run test（Vitest）
```
