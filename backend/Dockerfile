# =====================================================================
# 个人博客系统后端 · 生产镜像（M12 部署落地）
# 设计依据：《部署与测试.md》§1.3 —— 多阶段构建，构建期用带 Maven 的 JDK 镜像、
#           运行期只留 JRE，避免把编译器与仓库缓存带进最终镜像。
#
# 与原设计稿的两处有意偏差（M12 定稿记录）：
# 1) JDK 17 → 21：《系统设计.md》§4 锁定 Spring Boot 3.2 + Java 17，但开发环境
#    全程 JDK 21 编译（向下兼容 release=17），镜像统一 21 可消除"开发/生产行为漂移"
#    这一类最难查的隐性差异；若答辩机要求 17，仅需换回两个镜像名，代码零改动。
# 2) 构建产物固定命名 blog-server.jar（见 pom finalName），COPY 路径不再用通配，
#    版本升级不碎镜像层。
#
# 构建上下文 = backend/（compose 里 build.context: ../backend），所以
# ADD/COPY 路径都从 backend 根算起。
# =====================================================================

# ---------- 阶段一：构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 先只拷 pom：依赖层与代码层分离，代码改动不重建依赖下载层（Docker 缓存友好的标准手法）。
# 本仓库构建上下文内无 .mvn/ci/scripts 目录，ADD pom.xml 会退化为普通 COPY。
ADD pom.xml .
RUN mvn -B -q dependency:go-offline

# 依赖缓存就绪后再拷源码。跳过测试执行：接口/单测在 CI 与本地 H2 全量跑过，
# 镜像构建期再跑一遍只会把构建拖慢一倍；覆盖率门禁在 CI（mvn verify -Pcoverage-gate）。
COPY src ./src
RUN mvn -B -DskipTests package \
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
