# Export only the exact-source, project-owned security backport.
FROM eclipse-temurin:21-jdk@sha256:8ad118b146aa92da815b576441acd6b42b38236797ad0b6edc8c200b85bb0734 AS build
WORKDIR /build
COPY scripts/build_graphql_backport.sh scripts/build_graphql_backport.sh
COPY infrastructure/dependencies/graphql-java infrastructure/dependencies/graphql-java
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=secret,id=build_ca \
    --mount=type=secret,id=build_java_trust \
    if [ -s /run/secrets/build_ca ]; then export CURL_CA_BUNDLE=/run/secrets/build_ca; fi; \
    if [ -s /run/secrets/build_java_trust ]; then export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/run/secrets/build_java_trust -Djavax.net.ssl.trustStorePassword=changeit"; fi; \
    bash scripts/build_graphql_backport.sh /artifacts
FROM scratch
COPY --from=build /artifacts/ /
