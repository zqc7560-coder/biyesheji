# JMeter 正式压力测试报告（M16 / 阶段七交付物）

> 关联文档：《部署与测试.md》§2 性能测试行（四场景定义）、NFR-01（200 并发 P95<300ms）、
> TC-AI-07（AI 不占 Web 线程）。M9 台账写明"正式 JMeter 报告随阶段七"——即本报告。
> 简易 python 线程压测（M9）与本报告不冲突：前者验证缓存链路功能正确性，
> 后者按《部署与测试.md》§2 场景矩阵出答辩口径的性能数字。

**实测时间**：2026-09-14 04:14 ~ 06:12（含两轮缺陷修复复测）
**执行者**：Qwen Bot（工具链与产物见 §1.4）

---

## 1. 测试环境

### 1.1 被测系统（Docker Compose 全栈，与交付形态一致）

| 组件 | 形态 | 资源约束 |
|---|---|---|
| Nginx 1.25-alpine | 入口 :80（**全部场景经 Nginx 反代**，即真实用户路径） | WSL2 后端 |
| blog-server（temurin-21-jre） | prod 护栏 mem_limit=768m | JVM MaxRAMPercentage=75 |
| MySQL 8.0 | mem_limit=1g | 压测库已灌 30 篇样本文章 |
| Redis 7-alpine | mem_limit=256m，AOF everysec | 全命中缓存路径 |

宿主机：Windows 11，Docker Desktop 4.90.0（WSL2，内存上限 8GB，`.wslconfig`）。
Spring profile：`docker`（级联 `prod`，M13 接线）。

### 1.2 压测机与数据

- Apache JMeter 5.6.3（`E:\develop\apache-jmeter-5.6.3`），与 server 同机——
  回环网络开销趋近 0，P95 反映的是**服务端处理耗时**，这正是 NFR-01 的口径；
  同机压测的已知代价是压测端与被压端抢 CPU（容器实测 load 峰值 ~40/6 核），
  数字偏保守而非偏乐观，可接受。
- 数据：`deploy/loadtest/seed-loadtest-data.py` 灌 30 篇发布态文章
  （id 1..30，真实长度中文正文+代码块，标题与 AI 请求体同源）；
  `ai_bodies/ai_00..29.json` 为 30 份 ASCII 化摘要请求体。
- AI 场景经 PRIME 线程组真实调用 DeepSeek 30 发暖缓存（§2 场景③实录），
  之后压测流量全部走缓存命中路径——《部署与测试.md》§2 场景②"缓存命中路径"
  口径，测服务端开销、不测厂商时延（厂商时延不可控，写进 NFR 就是给答辩埋雷）。

### 1.3 场景定义（对齐《部署与测试.md》§2）

| 场景 | 内容 | 并发电压 |
|---|---|---|
| ① list | GET /api/articles（分页+条件，Redis 列表缓存路径） | 200 线程 × 300s，ramp 10s |
| ② detail | GET /api/articles/{id}（id 1..30 轮播，详情缓存命中） | 200 线程 × 300s，ramp 10s |
| ③ ai | POST /api/ai/summary（30 请求体随机，缓存全命中）+ **与场景①叠加并发**（TC-AI-07） | 20 线程 × 120s，ramp 5s |
| ④ degraded | ai_enabled=false 熔断后打场景③同款流量（验证 4004 闸门） | 500 线程 × 10 轮，ramp 15s |

### 1.4 资产与复现

```
deploy/loadtest/blog-load-test.jmx     四场景一模板，线程数全 __P 属性、缺省 0
deploy/loadtest/run-load-test.sh       执行器：场景开合/PRIME 收口/降级注入 trap/产物归档
deploy/loadtest/{seed-loadtest-data,generate-ai-bodies}.py
deploy/tools/ai_switch.py              降级场景的 ai_enabled API 形态开关
回归锁：backend/src/test/java/com/blog/deploy/LoadTestAssetsM16Test.java（11 例纯文件断言）

复现（栈已起的前提下）：
  cd deploy/loadtest
  python seed-loadtest-data.py --base http://localhost        # 打印 id_min/id_max
  JMETER_HOME=E:/develop/apache-jmeter-5.6.3 BASE=http://localhost \
    ID_MIN=1 ID_MAX=30 bash run-load-test.sh --scenario list  # detail/ai/degraded 同理
```

产物目录 `E:/develop/loadtest-output/<场景>-<时间戳>/`（jtl + JMeter HTML 报告 +
参数快照；体积大且是环境快照，不入库——本报告 §2 摘录即权威数字）。

---

## 2. 结果（全部为 jtl 机械汇总，非估）

### 2.1 四场景主指标

| 场景 | 样本数 | 错误率 | P50 | P95 | P99 | Max | 结论 |
|---|---|---|---|---|---|---|---|
| ① 列表 200 并发 | 2,292,354 | **0.00%** | 24ms | **44ms** | 78ms | 297ms | **PASS**（目标 P95<300ms，余量 6.8×） |
| ② 详情 200 并发 | 2,381,530 | **0.00%** | 24ms | **36ms** | 45ms | 710ms | **PASS** |
| ③ AI 20 并发（缓存命中） | 19,122 | **0.00%** | 117ms | **183ms** | 227ms | 469ms | **PASS**（含 Nginx 反代+JWT 全链路） |
| ④ 降级 500 并发 | 5,000 | **0.00%** | 3ms | **4ms** | 6ms | 9ms | **PASS**（4004 闸门在 DB/上游之前） |

支撑组参考：PRIME 真实模型调用 30/30 成功，P50=142ms / max=215ms——DeepSeek
缓存未命中路径的真实单发生成成本（暖缓存前的首帧体验参考）。

### 2.2 场景③ TC-AI-07 隔离性专项：AI 流量不得拖垮 Web 线程

设计承诺（M6）：AI 走 `aiExecutor` 专用线程池 + DeferredResult，Tomcat
`http-nio` 线程不被模型调用阻塞。验证方法：场景①（300s）与场景③（120s）
双进程叠加并发，比对列表 P95 劣化。

| 运行 | 条件 | 列表样本 | 列表 P95 | 劣化 |
|---|---|---|---|---|
| 基线 | 仅场景① | 2,292,354 | 44ms | — |
| 叠加 | 场景① + 场景③ 同时在跑（重叠 ~126s） | 2,115,369 | 48ms | **+9.1%** |

门槛 <30%（《部署与测试.md》§2 场景③），实测 +9.1% → **PASS**。
叠加窗口内列表错误率仍 0.00%，AI 侧错误率 0.00%——两条线程池互不拖累。

### 2.3 降级路径剖析（场景④）

`ai_switch.py` 经 PUT /api/admin/system/config 将 ai_enabled 置 false
（走 M8 白名单口+M9 双删镜像，非裸 SQL）后压 5000 发：P95=4ms、全部
HTTP 403 + 信封 code=4004，无一发触达模型上游。对照 R-03 应急预案
"AI 故障时一键熔断、主链路零感知"——数字成立。runner 的 trap 保证
压完必恢复 true（本轮实测恢复成功，/api/ai/status enabled=true）。

---

## 3. 压测抓出的真实部署缺陷（本报告的"过程价值"）

### 3.1 【已修·必现】Nginx 直写 proxy_pass 在千级 req/s 下 65% 请求 502

**现象**：场景①经 Nginx 首跑（04:54），406,294 样本中 **265,140 个 502（65.3%）**，
error.log 成片 `connect() to 172.18.0.x:8080 failed (99: Cannot assign requested
address)`（errno 99 = 临时端口耗尽）。直连 8080 同一场景 0 错误——问题在反代层。

**根因**：`proxy_pass http://blog-server:8080` 直写目标时 Nginx 每请求新建一条
到后端的 TCP 连接；7000+ req/s 下连接开合速度远超 TIME_WAIT 回收，容器网段
本地端口见底。

**修复**（`deploy/nginx/conf.d/blog.conf`）：`upstream blog_api { keepalive 64; }`
连接池三件套（upstream + `proxy_http_version 1.1` + `proxy_set_header Connection ""`，
少任一件静默失效）。修复后同场景 2,292,354 样本 **0 错误**（§2.1 场景①即修复后数字）。
回归锁：`DeployAssetsM12Test.nginxUpstreamKeepaliveTrio`（三件套逐条机械断言）。

### 3.2 【已修·假绿】smoke-deploy.sh 在 compose ps 空输出时宣布"全部 healthy"

`grep -cv 'healthy'` 对空输入返回 0 → 栈没起来/project 名敲错时第 1 项照样 PASS。
修复：显式空输出闸门 + 非空才判 healthy；`verify-deploy.sh` 等待循环同族防线。
回归锁：`DeployAssetsM12Test.healthyVerdictCannotBeVacuous`（四服务 healthcheck
齐备性——nginx 同轮补了 healthcheck，"全部容器 healthy"口径自此对四服务闭合）。

### 3.3 【判读记录，非缺陷】两次作废运行

- `ai-20260914-044642` / `ai-20260914-052842`：90%+ 请求
  `HttpHostConnectException :8080 refused`——当时 blog-server 容器被手工重启，
  8080 不对宿主机发布（M12 设计：容器网内供 Nginx 反代，属预期拓扑）。作废。
- `ai-20260914-053645`：跑至中途全量转 401。根因：同机另一个场景的 SETUP-login
  重新登录 admin → M2 登录水位（`invalid_before`）作废了先前签发的全部令牌。
  **这是"改密/登录踢线"设计的正确行为**，不是安全缺陷；教训已转化为操作纪律：
  *需要 admin 令牌的场景（ai/detail）不得与其他场景同时段跑*。本轮 §2.2 的
  叠加运行特意选 list（匿名 GET，不吃 401）与 ai 配对，正是这条纪律的应用。
  （JMX 已把 SETUP 线程组排最前 + serialize_threadgroups，同一次 JMeter 调用内
  天然安全；风险只在双进程叠加时。）

---

## 4. 结论与验收勾选

| 指标 | 门槛 | 实测 | 判定 |
|---|---|---|---|
| NFR-01 列表 200 并发 | P95<300ms、错误率 0 | 44ms、0.00% | ✅ |
| 详情 200 并发（缓存命中） | P95<300ms | 36ms | ✅ |
| TC-AI-07 AI 隔离 | 列表 P95 劣化<30% | +9.1% | ✅ |
| R-03 降级闸门 | 不触上游、快速失败 | P95=4ms、全 4004 | ✅ |
| 压测过程抓出部署缺陷 | — | nginx keepalive 502（修复+回归锁） | ✅ |

残余诚实清单：① 未测真实厂商时延分布（刻意口径，见 §1.2）；② 未做 >1 节点
横向扩展验证（毕设单机交付）；③ 同机压测对 CPU 竞争的影响使数字偏保守。
