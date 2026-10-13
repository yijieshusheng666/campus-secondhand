# 后端（Spring Boot 3.5 / Java 17）两阶段构建
#
# 构建上下文是 demo1/ 目录（docker-compose 已指定）
#   docker build -t campus-backend ./demo1

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷 pom.xml 预热依赖层：改代码时不必重下依赖
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 时区设成东八区，否则日志和 MySQL 时间对不上
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx512m"

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/api/goods > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]