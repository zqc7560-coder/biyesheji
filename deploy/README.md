# 部署与运维指南（M12 · 部署文档终稿）

> 本文是《部署与测试.md》§1 的工程化落地。文件清单：
> `deploy/docker-compose.yml`（编排）、`backend/Dockerfile`（后端镜像）、
> `deploy/nginx/conf.d/blog.conf`（反代/静态/gzip）、`deploy/.env.example`（密钥模板）、
> `deploy/scripts/{backup,restore}-mysql.sh`（备份恢复）、`deploy/scripts/smoke-deploy.sh`（部署后验收）。

## 1. 拓扑（与《部署与测试.md》§1.2 一致）

```
浏览器 :80 ─▶ nginx ─┬─ /            → frontend/dist 静态（gzip, hash 资源 7d）
                     ├─ /uploads     → uploads 卷直出（30d 强缓存）
                     └─ /api/*       → blog-server:8080（X-Real-IP 透传, 读超时 60s）
blog-server:8080 ──▶ mysql:3306（卷 mysql-data；首启自动执行 schema.sql+data.sql）
                └─▶ redis:6379（卷 redis-data；AOF everysec, requirepass, 256mb LRU）
```

## 2. 首次部署（目标机 Ubuntu 22.04 + Docker Engine/Compose v2）

```bash
git clone <repo> && cd <repo>/deploy
cp .env.example .env && chmod 600 .env
vi .env            # 按模板注释生成各密钥（openssl rand -hex 32）
cd ../frontend && npm ci && npm run build && cd ../deploy   # dist 由 Nginx 挂载
docker compose up -d --build
docker compose ps                      # 4 个 healthy（约 2~3 分钟）
bash scripts/smoke-deploy.sh           # 全 PASS 即交付可用（验收清单 §5 的机器版）
```

访问 `http://<IP>/`，管理员 `admin` / `.env 里 BLOG_ADMIN_PASSWORD`，**登录后立即改密**。

- 真实 AI Key 想让系统"开箱即用"：填 `.env` 的 `BLOG_AI_KEY_DEEPSEEK` / `BLOG_AI_KEY_QWEN`，
  首启由 BuiltinModelKeySeeder AES-GCM 加密入库；留空则后台手工录入，其余功能不受影响。
- 资源紧张/长跑演示机追加生产护栏：
  `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d`
  （内存限额 + restart 策略 + 日志封顶，见该文件注释）。

## 3. 密钥与配置说明（红线自查）

| 变量 | 用途 | 纪律 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | 建库+健康检查+备份 | 不进 git；compose 内经 `$$` 在容器侧展开 |
| `REDIS_PASS` | requirepass | 同上 |
| `BLOG_JWT_SECRET` | JWT HS256 | **≥32 字节**，弱密钥 jjwt 启动即抛（M2 闸门） |
| `BLOG_AI_MASTER_KEY` | ai_config Key 的 AES-GCM 主密钥 | 换密钥=历史密文作废（R-06），部署前定稿 |
| `BLOG_AI_KEY_*` | 内置模型开箱 Key | 只进 `.env`（gitignored）→ 加密落库，接口响应永不明文 |

仓库内**零真实密钥**：`deploy/.env`、`backend/.env` 均在 .gitignore；
`schema.sql/data.sql` 种子只有占位密文。提交前红线自查（CI 同款）：
`git ls-files | xargs grep -lE "sk-[a-zA-Z0-9]{20,}" || echo CLEAN`

## 4. 已知坑与注意点

1. **uploads 卷属主**：镜像内以 bloguser(uid 10001) 运行，命名卷首挂时继承镜像目录属主，
   正常；若手工把宿主机目录 bind 进 uploads 会写权限炸——用命名卷（compose 默认即命名卷）。
2. **`BLOG_AI_MASTER_KEY` ≠ `AI_KEY_ENC_SECRET`**：application.yml（dev）读后者、
   docker profile（M13 起经 `spring.profiles.group` 真实存在并级联 prod）读前者——
   同一把值两处环境变量名都要给（或部署机直接 export 两个名字）。
3. **ngram 参数只在容器首启生效**：`--ngram_token_size=2` 是启动 command 参数，
   已建卷的 MySQL 改它无效；全文搜索异常时 `docker compose rm -s -v mysql` 重灌（开发期）。
4. **升级后端**：`docker compose build blog-server && docker compose up -d blog-server`（<30s）。
5. **HTTPS**：blog.conf 443 段默认整体注释；有证书时解开注释并在 nginx 服务加
   `./nginx/certs:/etc/nginx/certs:ro` 挂载，同时把 80 段改为 301 跳转。
6. **Windows 本机无 Docker 时的兜底**：直连 profile（`mvn spring-boot:run` + `npm run dev`）
   即开发环境跑法，本就是 dev 主路径；compose 交付物面向 Linux 目标机。

## 5. 备份 / 恢复 / 验收

```bash
# 每日 03:00（脚本自带 gzip 校验 + 内容体检 + 7 日保留，见文件头注释）
crontab -l 2>/dev/null; echo "0 3 * * * $PWD/scripts/backup-mysql.sh >> /var/log/blog-backup.log 2>&1" | crontab -
bash scripts/backup-mysql.sh                 # 手动跑一次验证权限与 cron cwd 差异
bash scripts/restore-mysql.sh ~/blog_backups/blog_xxx.sql.gz
```

验收对照《部署与测试.md》§5：
- [x] docker compose 一键启动 → 5 分钟内可访问（smoke-deploy.sh 6 项断言）
- [x] 备份/恢复脚本实跑（本机实跑记录见 docs/进度.md 迭代日志 M12 行）
- [x] 全库无明文 Key（§3 红线自查命令 + NFR-04 既有测试锁）

## 6. 本机构建校验状态（M13 更正）

**本机（Windows 11 + Docker Desktop 4.90.0 / WSL2）已实测跑通全栈**——早期版本本节
写的是"未装 Docker、需目标机验证"，该声明已作废（真机记录见
`docs/容器化部署验证报告.md`，2026-09-14）。已验证项：

- `docker compose up -d --build` 四服务全部 healthy，经 Nginx 端到端 6 项接口全通；
- AI 摘要/标签真实调用（DeepSeek、Qwen 双厂商），内置模型 Key 密文落库核对；
- compose/Dockerfile/脚本的静态契约由 `DeployAssetsM12Test` 在 CI 持续把关
  （M13 起追加 healthcheck 变量注入、profile 悬空、cache mount 三条回归锁）。

## 7. 国内环境部署注意事项（Windows 开发机 / 国内云主机通用）

> 来自 2026-09-14 真机实测（docs/容器化部署验证报告.md §2），每一条都是踩出来的。

1. **Docker Desktop 版本代差**：国内镜像站流传的 4.1.1（2021）与新版 WSL
   不兼容——客户端能起、守护进程永远起不来。用官网 4.9x 安装包；
   `desktop.docker.com` 直连被重置时走代理 `curl -x http://127.0.0.1:7890 -L -o ...`。
2. **数据落非系统盘**：Docker Desktop 固定读 `%LOCALAPPDATA%\Docker\wsl`，
   把它做成目录联结指向 D/E 盘（`mklink /J`，写进 .ps1 执行——脚本必须纯 ASCII）。
   联结在升级/重装 Docker Desktop 后依然有效。
3. **内存护栏配在 `.wslconfig` 不在 Docker 设置**：改 Docker 设置里的 MemoryMiB
   对 WSL2 后端无效（实测 `docker info` 不变）。正确做法 `%USERPROFILE%\.wslconfig`
   写 `[wsl2] memory=8GB`，改完 `wsl --shutdown` 并重启 Docker Desktop 才生效。
   不设限曾把 31GB 宿主机吃到只剩 0.8GB。紧急止血同样 `wsl --shutdown`。
4. **拉镜像走引擎代理**：Docker Desktop 引擎配置加 `proxies` 指向本地代理
   （`http://127.0.0.1:7890`），`docker info` 出现 `HTTP Proxy: http.docker.internal:3128`
   即生效。偶发 `auth.docker.io` 超时：先单独 `docker pull` 基础镜像拉全，
   再 build 命中缓存。Docker Hub 直连超时是**环境现象不是项目 bug**，不要试图"修"它。
5. **收尾纪律**：演示/验证完 `docker compose down` 或 `wsl --shutdown`——
   WSL 虚拟机会长期占 1~2GB 内存。
6. **构建提速（M13）**：`backend/Dockerfile` 的 mvn 阶段挂 BuildKit
   `--mount=type=cache,target=/root/.m2/repository`（**只挂 repository 子目录**——
   挂 /root/.m2 整目录会把镜像层里的 settings.xml 遮蔽掉，提速静默失效），
   并叠加阿里云镜像源 settings 解决冷缓存；**二次起增量构建 ~20s**；真机对比
   数据记在 `docs/容器化部署验证报告.md` §7。
