# Build from the repository root: docker build -f product-service/Dockerfile .
# Parent POM and shared modules are resolved by the same Maven reactor as CI.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    bash mvnw --batch-mode --no-transfer-progress -pl product-service -am -DskipTests package
RUN mkdir /layers && cp product-service/target/*-exec.jar /layers/application.jar
WORKDIR /layers
RUN java -Djarmode=layertools -jar application.jar extract

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 1000 app && useradd --uid 1000 --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /layers/dependencies/ ./
COPY --from=build --chown=app:app /layers/spring-boot-loader/ ./
COPY --from=build --chown=app:app /layers/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /layers/application/ ./
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
