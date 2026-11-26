# Build from the repository root: docker build -f payment-service/Dockerfile .
# Parent POM and shared modules are resolved by the same Maven reactor as CI.
FROM eclipse-temurin:21-jdk@sha256:8ad118b146aa92da815b576441acd6b42b38236797ad0b6edc8c200b85bb0734 AS build
ARG SOURCE_DATE_EPOCH=315532800
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    bash mvnw --batch-mode --no-transfer-progress -pl payment-service -am -Dproject.build.outputTimestamp=${SOURCE_DATE_EPOCH} -DskipTests package
RUN mkdir /layers && cp payment-service/target/*-exec.jar /layers/application.jar
WORKDIR /layers
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination /extracted

# Netty's bundled native TLS library requires glibc.
FROM eclipse-temurin:21-jre-jammy@sha256:a8bff88f760e87e0c8844f8461f807c203fd5e8f78c951c843024647e965eaf5
ARG SOURCE_DATE_EPOCH=315532800
RUN groupadd --gid 1000 app && useradd --no-log-init --uid 1000 --gid app --no-create-home app && sed -i 's/^app:\([^:]*\):[0-9]*:/app:\1:0:/' /etc/shadow
WORKDIR /app
COPY --from=build --chown=app:app /extracted/dependencies/ ./
COPY --from=build --chown=app:app /extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /extracted/application/ ./
USER app
EXPOSE 8080 9093 9094
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=55.0", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
