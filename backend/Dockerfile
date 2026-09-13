# =====================================================================
# 个人博客系统后端 · 生产镜像（M12 部署落地，M13 构建提速定稿）
# 设计依据：《部署与测试.md》§1.3 —— 多阶段构建，构建期用带 Maven 的 JDK 镜像、
#           运行期只留 JRE，避免把编译器与仓库缓存带进最终镜像。
#
# 注意：**故意不加** #syntax=docker/dockerfile:1 头——本机 Engine 29.7 的
#           内建前端原生支持 --mount=type=cache；而 syntax 行会让每次 build 前
#           先去 docker.io 拉前端镜像，国内网络（无代理时）直接超时炸构建
#           （M13 首跑真实踩中）。DeployAssetsM12Test 有断言拦"照教程补回"。
# 与原设计稿的两处有意偏差（M12 定稿记录）：
# 1) JDK 17 → 21：《系统设计.md》§4 锁定 Spring Boot 3.2 + Java 17，但开发环境
#    全程 JDK 21 编译（向下兼容 release=17），镜像统一 21 可消除"开发/生产行为漂移"
#    这一类最难查的隐性差异；若答辩机要求 17，仅需换回两个镜像名，代码零改动。
# 2) 构建产物固定命名 blog-server.jar（见 pom finalName），COPY 路径不再用通配，
#    版本升级不碎镜像层。
#
# 构建上下文 = backend/（compose 里 build.context: ../backend），所以
# COPY 路径都从 backend 根算起。
# =====================================================================

# ---------- 阶段一：构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 【M13 提速①·国内网络根治】容器内 Maven 默认直连 Central（repo.maven.apache.org），
# 国内无代理实测 7~54 kB/s、整棵 Spring Boot 依赖树 10~20 分钟（真机数据见
# docs/容器化部署验证报告.md §7）。构建期覆盖 settings.xml 走阿里云聚合仓
# （mirrorOf 只钉 central 不钉 *，将来若加私有仓不被劫持）。海外机器/CI 不经此
# 文件的路径只有本镜像——GitHub Actions 与本机 mvn 都不走 docker build。
#
# 【M13 提速②·cache mount 遮蔽陷阱】settings.xml 必须落在挂载点**之外**：
# BuildKit 把 target 目录整体挂上缓存卷，镜像层里写进 /root/.m2 的文件会被
# 遮蔽——若缓存卷挂 /root/.m2（M13 中途版本，实测发现），settings.xml 与
# go-offline 的成果都"见不到"，改动静默失效。正确姿势=只挂仓库子目录
# /root/.m2/repository（DeployAssetsM12Test 机器钉死挂载目标，写错即 CI 红）。
COPY docker/settings-aliyun.xml /root/.m2/settings.xml

# 【M13 提速③·删 go-offline】原模板"pom 层独立 + dependency:go-offline"是给
# Docker 层缓存用的省钱手法；跨构建复用改由 cache mount 承担后，go-offline
# 变成纯冗余——真机三连证据（同一 daemon，改前/冷/暖）：
#   改前 --no-cache: go-offline 567.7s + package ~30s（全程 602s）
#   冷缓存:          go-offline 477.8s + package 22.8s
#   暖缓存:          go-offline **仍要 397.8s**（每次完整重走依赖树解析，
#                    网络元数据往返是主耗时，缓存卷救不了）；package 仅 17.8s。
# 合并为单次 mvn：暖缓存下"改一行代码重建"≈20s；pom 变动也只增量解析一次。
# 跳过测试执行：接口/单测在 CI 与本地 H2 全量跑过，镜像构建期再跑一遍只会把
# 构建拖慢一倍；覆盖率门禁在 CI（mvn verify -Pcoverage-gate）。
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -DskipTests package \
    && cp target/blog-server.jar /build/app.jar

# ---------- 阶段二：运行 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# 非 root 运行：容器逃逸场景下最小权限原则（毕设安全答辩可讲的点）。
# 预建 uploads/logs 并 chown，配合 compose 的命名卷挂载——挂载卷属主问题见 deploy/README §4。
RUN useradd -r -u 10001 bloguser && mkdir -p uploads logs && chown -R bloguser:bloguser /app
USER bloguser

# 数据目录（compose 卷挂载点）：uploads=图片直出，logs=按日滚动日志（§1.1 环境矩阵）
COPY --from=builder /build/app.jar app.jar

EXPOSE 8080

# 容器内内存上限：compose 已给 512m，这里 -XX 参数与《部署与测试.md》§1.4 容量基线一致。
# -XX:+ExitOnOutOfMemoryError：OOM 时退出交给 compose 重启策略，不留半死进程骗健康检查。
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
