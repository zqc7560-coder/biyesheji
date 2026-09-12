# API 接口设计（个人博客系统）

> RESTful 风格 ｜ Base URL：`/api` ｜ 数据格式：JSON ｜ 编码：UTF-8 ｜ v1.0 ｜ 2026-09-12

## 1. 通用约定

### 1.1 统一响应体

所有接口（含出错）返回同一信封，HTTP 状态码表达粗粒度结果（200/401/403/404/429/500），业务细分靠 `code`：

```json
{
  "code": 0,
  "message": "success",
  "data": { },
  "timestamp": 1789123456789
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | int | 0 成功；非 0 见错误码表 |
| message | string | 可直接展示给用户的中文提示（服务端已本地化） |
| data | any | 业务载荷；出错时为 null 或字段级校验明细 |
| timestamp | long | 服务器毫秒时间戳 |

### 1.2 分页规范

请求：`pageNum`（默认 1）、`pageSize`（默认 10，上限 100）、`sort`（如 `published_at,desc`）。
响应 `data`：`{ "total": 132, "pages": 14, "pageNum": 1, "pageSize": 10, "list": [...] }`。

### 1.3 认证方式

登录接口外，全部需 `Authorization: Bearer <JWT>`。权限列标注：
- `公开`：无需登录；`登录`：持有效 Token；`作者`：仅资源属主或管理员；`ADMIN`：仅 `ROLE_ADMIN`。

### 1.4 错误码表（BizCode）

| 区段 | code | 含义 | HTTP |
|------|------|------|------|
| 通用 | 0 | 成功 | 200 |
| 通用 | 500 | 系统内部异常（兜底，不泄露堆栈） | 500 |
| 参数 | 400 | 参数校验失败（data 含字段明细） | 200/400 |
| 认证 | 1001 | 未认证或 Token 失效/已登出 | 401 |
| 认证 | 1002 | 用户名或密码错误 | 401 |
| 认证 | 1003 | 账号被禁用 | 403 |
| 认证 | 1004 | Token 过期（前端据此静默刷新或跳登录） | 401 |
| 授权 | 1005 | 无权限访问该资源（RBAC/属主校验失败） | 403 |
| 用户 | 1101 | 用户名已存在 | 200 |
| 用户 | 1102 | 邮箱已被注册 | 200 |
| 文章 | 2001 | 文章不存在或已删除 | 404 |
| 文章 | 2002 | 无权操作他人文章 | 403 |
| 文章 | 2003 | 分类不存在 | 200 |
| 文章 | 2004 | 乐观锁冲突（他人已更新，请刷新） | 200 |
| 分类 | 2101 | 分类下仍有文章，禁止删除 | 200 |
| 分类 | 2102 | 存在子分类，禁止删除或挂接（M4 新增，两级约束护栏） | 200 |
| 分类 | 2103 | 分类名已存在（M4 新增，与 2201 标签重名对称） | 200 |
| 标签 | 2201 | 标签名重复 | 200 |
| 标签 | 2202 | 标签不存在（M4 新增，与 2003 分类不存在对称） | 200 |
| 评论 | 2301 | 文章不允许评论 | 200 |
| 评论 | 2302 | 父评论不存在或层级超限 | 200 |
| 文件 | 3001 | 文件类型不合法 | 200 |
| 文件 | 3002 | 文件超过 5MB 限制 | 200 |
| AI | 4001 | AI 服务未配置（管理员未设默认配置） | 200 |
| AI | 4002 | AI 服务调用失败/超时（已降级，可手动填写） | 200 |
| AI | 4003 | 今日 AI 调用次数已达上限 | 429 |
| AI | 4004 | AI 功能已被管理员关闭 | 200 |
| AI | 4005 | AI 返回格式非法（解析失败，建议重试） | 200 |
| AI | 4101 | API Key 无效（连接测试 401） | 200 |
| AI | 4102 | Base URL 不可达（连接测试网络异常） | 200 |
| 配置 | 5001 | 配置项不存在 | 404 |

## 2. 认证模块 `/api/auth`

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | /api/auth/register | 注册 | 公开 |
| POST | /api/auth/login | 登录，返回 JWT | 公开 |
| POST | /api/auth/logout | 登出（Token 拉黑） | 登录 |
| POST | /api/auth/refresh | 滑动续期（剩余<30min 才换发新 Token） | 登录 |
| GET | /api/auth/me | 当前登录用户信息+权限点 | 登录 |

**POST /api/auth/register** 请求体：

```json
{ "username": "zhangsan", "password": "***@1234", "nickname": "张三", "email": "zhang@example.com" }
```
校验：username 4~20 位字母数字下划线；password ≥8 位含字母数字；email 格式。
响应：`{ "code":0, "message":"success", "data": { "userId": 3 } }`
失败：`{ "code":1101, "message":"用户名已存在", "data":null }`

**POST /api/auth/login** 请求体 `{ "username":"admin", "password":"***" }`
成功响应：

```json
{ "code":0, "message":"success",
  "data": { "token": "***", "expiresIn": 7200,
    "user": { "id":1, "username":"admin", "nickname":"超级管理员", "avatar":"/uploads/a.png", "roles":["ROLE_ADMIN"], "perms":["ai:config","user:manage"] } } }
```

**POST /api/auth/logout** 响应 `{ "code":0, "data":null }`（幂等，无 Token 也返回成功）。

## 3. 用户模块 `/api/users`

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/users/profile | 查看资料（`?userId=` 缺省为自己） | 登录 |
| PUT | /api/users/profile | 修改昵称/简介/邮箱 | 作者(本人) |
| PUT | /api/users/password | 修改密码 `{oldPassword,newPassword}` | 登录 |
| POST | /api/users/avatar | 上传头像（multipart `file`） | 登录 |
| GET | /api/users/{id}/articles | 某作者公开文章 | 公开 |

**PUT /api/users/password** 请求 `{ "oldPassword":"Abcd@1234", "newPassword":"NewPwd@5678" }`
成功：`{ "code":0, "message":"密码已更新，请重新登录", "data":null }`（服务端同时拉黑旧 jti、`DEL login:user:{uid}`）。

## 4. 文章模块 `/api/articles`

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/articles | 分页列表。查询参数：`pageNum,pageSize,categoryId,tagId,keyword,status(仅作者后台),mine,sort` | 公开(mine=ADMIN/作者) |
| GET | /api/articles/{id} | 详情（草稿/下线仅作者与 ADMIN 可见；含 pv+1 异步） | 公开+条件 |
| POST | /api/articles | 新建（草稿或发布，`status` 字段决定） | 登录 |
| PUT | /api/articles/{id} | 更新（携带 `version` 乐观锁） | 作者 |
| PATCH | /api/articles/{id}/status | 状态流转 `{ "status": 1 }` 发布/下线/回草稿 | 作者 |
| DELETE | /api/articles/{id} | 逻辑删除 | 作者 |
| GET | /api/articles/archive | 归档树 `{ "2026":{ "09": [ {id,title,publishedAt} ] } }` | 公开 |

**POST /api/articles** 请求体：

```json
{
  "title": "Spring Boot 3 实战：统一响应体设计",
  "categoryId": 1,
  "tags": ["SpringBoot", "后端"],
  "cover": "/uploads/2026/09/cover.png",
  "summary": "本文介绍…（可由 AI 生成后人工确认）",
  "summarySource": 1,
  "contentMd": "# 标题\n\n正文 **Markdown** …",
  "status": 1,
  "allowComment": 1
}
```
响应：`{ "code":0, "data": { "id": 42, "wordCount": 1580 } }`

**GET /api/articles/42** 响应 `data`：

```json
{
  "id": 42, "title": "…", "cover": "…", "summary": "…",
  "contentMd": "# 标题\n…", "html": "<h1>…（服务端预渲染可选）",
  "author": { "id":1, "nickname":"张三", "avatar":"…" },
  "category": { "id":1, "name":"技术" }, "tags": [ {"id":5,"name":"SpringBoot"} ],
  "viewCount": 1024, "likeCount": 18, "commentCount": 6,
  "status": 1, "publishedAt": "2026-09-10T10:00:00", "createdAt": "…", "updatedAt": "…"
}
```
失败：`{ "code":2001, "message":"文章不存在或已删除" }`

**PATCH /api/articles/42/status** `{ "status":2 }` → `{ "code":0, "message":"已下线" }`；非法流转（如 2→1 需作者）统一 `1005`。

## 5. 分类与标签模块

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/categories | 分类树（含 article_count） | 公开 |
| POST | /api/categories | 新增 `{name,parentId,sortOrder}` | ADMIN |
| PUT | /api/categories/{id} | 修改 | ADMIN |
| DELETE | /api/categories/{id} | 删除（有文章则 `2101`） | ADMIN |
| GET | /api/tags | 标签列表；`?cloud=true` 返回热度权重 | 公开 |
| POST | /api/tags | 新增 `{name,color}` | ADMIN |
| PUT | /api/tags/{id} | 修改 | ADMIN |
| DELETE | /api/tags/{id} | 删除并解除文章关联 | ADMIN |
| POST | /api/tags/merge | 合并 `{fromTagId,toTagId}` | ADMIN |

## 6. 评论模块 `/api/comments`

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/comments | `?articleId=` 已通过审核的评论（两级嵌套） | 公开 |
| POST | /api/comments | 发表 `{articleId,parentId,replyToUid,content}` | 登录 |
| DELETE | /api/comments/{id} | 删除（本人或 ADMIN） | 作者 |
| GET | /api/admin/comments | 管理端分页 `?status=0` 待审列表 | ADMIN |
| PATCH | /api/admin/comments/{id}/audit | 审核 `{ "status":1 }` 通过/`2` 屏蔽 | ADMIN |

**GET /api/comments?articleId=42** 响应 `data.list`：

```json
[ { "id":9, "content":"写得好！", "user":{"id":3,"nickname":"李四","avatar":"…"},
    "createdAt":"2026-09-11T08:00:00",
    "replies":[ { "id":10, "content":"谢谢", "replyTo":{"id":3,"nickname":"李四"}, "user":{…} } ] } ]
```
发表成功（先审后发开启时）：`{ "code":0, "message":"评论已提交，等待审核" }`；
关闭审核时 `message:"评论发表成功"` 且 data 含评论对象（前端直接插入）。

## 7. AI 模块 `/api/ai` ★（设计重点，详见《AI模块设计.md》）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | /api/ai/summary | 生成文章摘要 | 登录 |
| POST | /api/ai/tags | 生成文章标签 | 登录 |
| POST | /api/ai/batch | 对未填摘要/标签的已发布文章批量补全（异步，管理员治理工具） | ADMIN |
| GET | /api/ai/status | 前台按钮可用态探测（是否有默认配置、功能开关、今日剩余额度） | 公开 |
| GET | /api/admin/ai/configs | AI 配置分页（**仅掩码**） | ADMIN |
| POST | /api/admin/ai/configs | 新增配置（Key 仅写入不回显） | ADMIN |
| PUT | /api/admin/ai/configs/{id} | 修改配置（`apiKey` 传空串=不变更） | ADMIN |
| DELETE | /api/admin/ai/configs/{id} | 停用/删除 | ADMIN |
| PATCH | /api/admin/ai/configs/{id}/default | 设为默认（事务互斥） | ADMIN |
| POST | /api/admin/ai/configs/{id}/test | 连通性测试（用小 prompt 试调一次） | ADMIN |
| GET | /api/admin/ai/logs | 调用日志分页 `?bizType&status&dateFrom&dateTo&userId` | ADMIN |
| GET | /api/admin/ai/stats | 成本统计 `{ byDay:[…], byBiz:{…}, cacheHitRate:0.42, todayCost:0.18 }` | ADMIN |

**GET /api/ai/status** 响应（前端据此决定 AI 按钮灰不灰）：

```json
{ "code":0, "data": { "enabled": true, "configured": true, "defaultModel": "deepseek-chat",
  "remainingToday": 47, "dailyQuota": 50 } }
```

**POST /api/ai/summary** 请求体：

```json
{ "contentMd": "# Spring Boot 3 实战……（全文 Markdown）", "title": "可选，提高效果", "style": "zh" }
```
成功响应：

```json
{ "code":0, "message":"success",
  "data": { "summary":"本文基于 Spring Boot 3 讲解统一响应体与全局异常处理的最佳实践，涵盖 Result 封装、异常分层与错误码设计，适合后端开发者参考。",
            "fromCache": false, "tokensUsed": {"prompt":1802,"completion":64}, "latencyMs": 4120 } }
```
失败（降级）：`{ "code":4002, "message":"AI 服务暂不可用，请稍后重试或手动填写摘要", "data": null }`

**POST /api/ai/tags** 请求体同 summary（无 style）。成功响应：

```json
{ "code":0, "data": { "tags":["SpringBoot","后端开发","异常处理"],
  "existingTagIds":[5,8], "fromCache":false, "tokensUsed":{"prompt":1810,"completion":28}, "latencyMs": 3350 } }
```
（`existingTagIds` 为已入库标签的复用提示，前端勾选确认后随文章保存生效。）

**POST /api/admin/ai/configs** 请求体：

```json
{ "name":"DeepSeek 生产", "provider":"deepseek", "baseUrl":"https://api.deepseek.com/v1",
  "apiKey":"***", "model":"deepseek-chat", "temperature":0.3, "maxTokens":512,
  "timeoutMs":30000, "isDefault":true }
```
响应 `data`（注意无明文 Key）：

```json
{ "id":1, "name":"DeepSeek 生产", "baseUrl":"https://api.deepseek.com/v1",
  "apiKeyMask":"sk-****3dAb", "model":"deepseek-chat", "isDefault":1, "enabled":1 }
```

**POST /api/admin/ai/configs/1/test** 响应：
`{ "code":0, "data": { "ok": true, "latencyMs": 850, "reply":"OK" } }` 或 `{ "code":4101, "message":"API Key 无效（上游返回 401）" }`

**GET /api/admin/ai/logs?bizType=SUMMARY&status=2** 响应 `data.list`：

```json
[ { "id":99, "userId":3, "nickname":"李四", "bizType":"SUMMARY", "model":"deepseek-chat",
    "status":2, "retryCount":2, "latencyMs":30120, "errorMsg":"timeout after 30s",
    "cacheHit":0, "promptTokens":null, "completionTokens":null, "costEst":null,
    "createdAt":"2026-09-11T14:22:31" } ]
```

## 8. 系统配置与存储模块

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | /api/system/config | 站点公开参数（site_name/logo/备案号/注册开关） | 公开 |
| PUT | /api/admin/system/config | 批量修改 `{items:[{key,value}]}` | ADMIN |
| GET | /api/admin/dashboard | 仪表盘聚合（文章/用户/评论数、7 日 PV 趋势、AI 调用次数） | ADMIN |
| POST | /api/files/upload | multipart `file`+`type`(article/cover/avatar)；≤5MB；白名单 image/png,jpeg,webp,gif | 登录 |
| GET | /api/admin/users | 用户分页 `?keyword&status` | ADMIN |
| PATCH | /api/admin/users/{id}/status | 启禁（禁用即拉黑其登录态） | ADMIN |
| POST | /api/admin/users/{id}/roles | 分配角色 `{roleIds:[1,2]}` | ADMIN |

**POST /api/files/upload** 响应：`{ "code":0, "data": { "url":"/uploads/2026/09/7f3a…c2.png", "fileId": 15, "size": 204811 } }`

## 9. 接口与安全设计小结

1. **AI Key 全链路不出服务端**：管理端列表/详情接口返回 `apiKeyMask`；`apiKey` 字段仅作为写入 DTO 存在，响应 DTO 无此字段——从结构上杜绝回传。
2. 所有列表接口走参数对象绑定 + `@Validated`，杜绝拼接 SQL；MyBatis-Plus `LambdaQueryWrapper` 保证列名映射。
3. 越权三防：JWT 角色 + `@PreAuthorize` 权限点 + Service 层属主校验（`WHERE id=? AND user_id=?`），测试用例见《部署与测试.md》§4。
4. 接口幂等：点赞、状态流转、设默认配置均以当前状态为前置条件更新，重复调用返回 0 行而非报错。
