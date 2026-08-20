# syntax=docker/dockerfile:1.7

FROM maven:3.9.14-eclipse-temurin-21 AS build

WORKDIR /workspace

# pom.xml 没变化时，可以复用 Maven 依赖缓存
COPY pom.xml ./

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline

# 依赖准备完成后再复制源码，避免每次改源码都重新下载依赖
COPY src ./src

# GitHub Actions 会先运行 clean verify，这里不重复执行测试
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp clean package -DskipTests


FROM eclipse-temurin:21.0.11_10-jre-alpine-3.23 AS runtime

# curl 供 Docker Compose 健康检查使用；
# 应用使用固定的非 root UID/GID 运行
RUN apk add --no-cache curl \
    && addgroup -S -g 10001 orderplatform \
    && adduser -S -D -H -u 10001 \
       -G orderplatform orderplatform

WORKDIR /app

COPY --from=build \
    --chown=orderplatform:orderplatform \
    /workspace/target/order-platform-*.jar \
    /app/app.jar

USER orderplatform:orderplatform

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
