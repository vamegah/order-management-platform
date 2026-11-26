# Build from the repository root: docker build -f graphql-gateway/Dockerfile .
# Parent POM and shared modules are resolved by the same Maven reactor as CI.
FROM eclipse-temurin:21-jdk@sha256:8ad118b146aa92da815b576441acd6b42b38236797ad0b6edc8c200b85bb0734 AS build
ARG SOURCE_DATE_EPOCH=315532800
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.gradle \
    bash scripts/build_graphql_backport.sh /graphql-backport && \
    bash mvnw --batch-mode --no-transfer-progress -f /graphql-backport/graphql-java.pom \
      org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file \
      -Dfile=/graphql-backport/graphql-java.jar -DpomFile=/graphql-backport/graphql-java.pom && \
    bash mvnw --batch-mode --no-transfer-progress -pl graphql-gateway -am -Dproject.build.outputTimestamp=${SOURCE_DATE_EPOCH} -DskipTests package
RUN mkdir /layers && cp graphql-gateway/target/*-exec.jar /layers/application.jar
WORKDIR /layers
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-alpine@sha256:5d7689f47d670aca766b9503a70bccc2d73b4eb9221ae8f714ae958313c4ce70
ARG SOURCE_DATE_EPOCH=315532800
RUN addgroup -g 1000 -S app && adduser -u 1000 -S app -G app && sed -i 's/^app:\([^:]*\):[0-9]*:/app:\1:0:/' /etc/shadow
WORKDIR /app
COPY --from=build --chown=app:app /extracted/dependencies/ ./
COPY --from=build --chown=app:app /extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /extracted/application/ ./
USER app
EXPOSE 8000
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=55.0", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
