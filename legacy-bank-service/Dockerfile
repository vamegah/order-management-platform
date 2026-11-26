# Build from the repository root: docker build -f legacy-bank-service/Dockerfile .
# Parent POM and shared modules are resolved by the same Maven reactor as CI.
FROM eclipse-temurin:21-jdk@sha256:8ad118b146aa92da815b576441acd6b42b38236797ad0b6edc8c200b85bb0734 AS build
ARG SOURCE_DATE_EPOCH
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    bash mvnw --batch-mode --no-transfer-progress -pl legacy-bank-service -am -Dproject.build.outputTimestamp=${SOURCE_DATE_EPOCH} -DskipTests package
RUN mkdir /layers && cp legacy-bank-service/target/*-exec.jar /layers/application.jar
WORKDIR /layers
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-alpine@sha256:5d7689f47d670aca766b9503a70bccc2d73b4eb9221ae8f714ae958313c4ce70
RUN addgroup -g 1000 -S app && adduser -u 1000 -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app /extracted/dependencies/ ./
COPY --from=build --chown=app:app /extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /extracted/application/ ./
USER app
ARG VCS_REF=development
LABEL org.opencontainers.image.revision=$VCS_REF
ENV MANAGEMENT_SERVER_PORT=9090 MANAGEMENT_SERVER_SSL_ENABLED=false
EXPOSE 8443
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O- http://localhost:9090/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=55.0", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
