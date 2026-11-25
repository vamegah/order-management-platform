# Build from the repository root: docker build -f legacy-banking-adapter/Dockerfile .
# Parent POM and shared modules are resolved by the same Maven reactor as CI.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    bash mvnw --batch-mode --no-transfer-progress -pl legacy-banking-adapter -am -DskipTests package
RUN mkdir /layers && cp legacy-banking-adapter/target/*-exec.jar /layers/application.jar
WORKDIR /layers
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 1000 -S app && adduser -u 1000 -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app /extracted/dependencies/ ./
COPY --from=build --chown=app:app /extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /extracted/application/ ./
USER app
ARG VCS_REF=development
LABEL org.opencontainers.image.revision=$VCS_REF
ENV MANAGEMENT_SERVER_PORT=9090
EXPOSE 8085
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=10 \
    CMD wget -q -O- http://localhost:9090/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
