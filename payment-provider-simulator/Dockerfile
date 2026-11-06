# Build from the repository root: docker build -f payment-provider-simulator/Dockerfile .
# Parent POM and shared modules are resolved by the same Maven reactor as CI.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    bash mvnw --batch-mode --no-transfer-progress -pl payment-provider-simulator -am -DskipTests package
RUN mkdir /layers && cp payment-provider-simulator/target/*-exec.jar /layers/application.jar
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
EXPOSE 8090 8091
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
