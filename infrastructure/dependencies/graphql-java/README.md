# GraphQL Java security backport

The platform uses project-owned `com.graphql-java:graphql-java:25.1-omp-guava-33.7.2`. Upstream [25.1 source](https://github.com/graphql-java/graphql-java/tree/f16f4de6faef18ed141a8454f5d40947ffa3fcc5) shades Guava 32.1.2 into its JAR. A normal Maven Guava override cannot replace those classes. [Guava's advisory](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94) identifies the deserialization allocation fix in 33.7.2.

`source.lock` pins the exact upstream commit/archive, original build files and Gradle distribution checksum. The source builder changes Guava to 33.7.2-jre and makes archive order/timestamps/BND metadata reproducible. It retains upstream GraphQL production source, grammar generation, shading, POM dependencies and license. Test/JMH compilation is excluded from the runtime build; platform unit/integration and packaged public-consumer suites remain required. The rebuilt JAR contains its source lock and accurate embedded Guava metadata.

The builder runs in the digest-pinned Java 21 image used by application packaging. `artifacts.sha256` records the reviewed JAR, POM and source lock hashes; every build must reproduce all three. No compiled artifact is committed or published to an external repository. Optional BuildKit `build_ca` and `build_java_trust` secrets provide public corporate trust when needed, while HTTPS certificate verification stays enabled.

Before root Maven verification or GraphQL development, run from the repository root with Docker, Java 21 and Python 3.11+ available:

```sh
python scripts/bootstrap_graphql_backport.py
./mvnw -pl graphql-gateway -am verify
```

The bootstrap verifies all hashes/provenance and installs only into the local Maven repository. `--repository` and `--settings` select an existing managed cache/settings file without printing credentials. Full-reactor CI jobs bootstrap automatically. The GraphQL Dockerfile builds and verifies the same artifact before packaging. An independent build with `docker build --no-cache -f infrastructure/dependencies/graphql-java/Dockerfile --output type=local,dest=/absolute/fresh/output .` must reproduce the pinned hashes.

For updates, review upstream source/license and security advisories, regenerate the source pins, rebuild twice from the pinned toolchain, inspect the actual shaded dependency inventory, and review new artifact hashes before changing the private version. Run complete GraphQL/public-consumer, dependency, coverage and image gates. Remove this backport and bootstrap only after an upstream release demonstrably embeds a fixed Guava version and passes the same gates. Updating artifact hashes alone is insufficient review.
