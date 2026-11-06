# Build from the repository root: docker build -f legacy-bank-service/Dockerfile .
# Parent POM and shared modules are resolved by the same Maven reactor as CI.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    bash mvnw --batch-mode --no-transfer-progress -pl legacy-bank-service -am -DskipTests package
RUN mkdir /layers && cp legacy-bank-service/target/*-exec.jar /layers/application.jar
WORKDIR /layers
RUN java -Djarmode=layertools -jar application.jar extract

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 1000 -S app && adduser -u 1000 -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app /layers/dependencies/ ./
COPY --from=build --chown=app:app /layers/spring-boot-loader/ ./
COPY --from=build --chown=app:app /layers/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /layers/application/ ./
USER app
EXPOSE 8443
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --no-check-certificate -q -O- https://localhost:8443/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
