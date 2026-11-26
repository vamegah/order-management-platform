#!/usr/bin/env bash
# Builds a private-version upstream artifact; no artifact is published externally.
set -euo pipefail
script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock_root="$script_root/infrastructure/dependencies/graphql-java"
source "$lock_root/source.lock"
output="${1:?An absolute output directory is required}"
[[ "$output" = /* && ! -e "$output" ]] || { echo 'Use a fresh absolute output directory' >&2; exit 1; }
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
archive="$work/source.tar.gz"
curl --fail --silent --show-error --location --retry 6 --retry-all-errors --retry-max-time 180 --connect-timeout 15 --max-time 180 --proto '=https' --tlsv1.2 \
  "https://codeload.github.com/graphql-java/graphql-java/tar.gz/$SOURCE_REVISION" -o "$archive"
printf '%s  %s\n' "$SOURCE_SHA256" "$archive" | sha256sum --check --status
mkdir "$work/source"
tar -xf "$archive" -C "$work/source" --strip-components=1
cd "$work/source"
printf '%s  %s\n' "$BUILD_GRADLE_SHA256" build.gradle "$WRAPPER_PROPERTIES_SHA256" gradle/wrapper/gradle-wrapper.properties | sha256sum --check --status
[[ "$(grep -Fxc "def guavaVersion = '32.1.2-jre'" build.gradle)" = 1 ]]
sed -i "s/def guavaVersion = '32.1.2-jre'/def guavaVersion = '$GUAVA_VERSION'/" build.gradle
# Match only the BND directive, not its explanatory comment.
sed -i '/^-exportcontents: graphql\.\*/i-reproducible: true' build.gradle
cat "$lock_root/reproducible.gradle" >> build.gradle
sed -i 's/^networkTimeout=10000$/networkTimeout=60000/' gradle/wrapper/gradle-wrapper.properties
printf '\ndistributionSha256Sum=%s\n' "$GRADLE_SHA256" >> gradle/wrapper/gradle-wrapper.properties
mkdir -p src/main/resources/META-INF/omp
cp "$lock_root/source.lock" src/main/resources/META-INF/omp/graphql-java-backport.lock
export RELEASE_VERSION="$BACKPORT_VERSION"
# This private dependency has fixed archive timestamps independent of the outer release.
unset SOURCE_DATE_EPOCH
# Production classes alone are the minimizer roots. Platform tests and real
# packaged consumers verify compatibility; test/JMH compilers are not runtime inputs.
distribution_cache="${GRADLE_USER_HOME:-$HOME/.gradle}/omp-distributions"
mkdir -p "$distribution_cache"
distribution="$distribution_cache/gradle-8.13-bin.zip"
if ! printf '%s  %s\n' "$GRADLE_SHA256" "$distribution" | sha256sum --check --status 2>/dev/null; then
    curl --fail --silent --show-error --location --retry 6 --retry-all-errors --retry-max-time 180 \
      --connect-timeout 15 --max-time 180 --proto '=https' --tlsv1.2 \
      https://downloads.gradle.org/distributions/gradle-8.13-bin.zip -o "$work/gradle.zip"
    printf '%s  %s\n' "$GRADLE_SHA256" "$work/gradle.zip" | sha256sum --check --status
    mv "$work/gradle.zip" "$distribution"
fi
(cd "$work" && jar -xf "$distribution")
bash "$work/gradle-8.13/bin/gradle" --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1024m \
  shadowJar generatePomFileForGraphqlJavaPublication \
  -x compileTestJava -x compileTestGroovy -x compileJmhJava -x compileJmhGroovy
mkdir -p "$output"
cp "build/libs/graphql-java-$BACKPORT_VERSION.jar" "$output/graphql-java.jar"
cp build/publications/graphqlJava/pom-default.xml "$output/graphql-java.pom"
cp "$lock_root/source.lock" "$output/source.lock"
cp LICENSE.md "$output/LICENSE-graphql-java.md"
(cd "$output" && sha256sum graphql-java.jar graphql-java.pom source.lock > SHA256SUMS)

(cd "$output" && sha256sum --check "$lock_root/artifacts.sha256")
