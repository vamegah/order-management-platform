#!/usr/bin/env bash
# Offline smoke test for minimal JDK images without unzip, and checksum rejection.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEST_DIR"' EXIT
mkdir -p "$TEST_DIR/project/.mvn/wrapper" "$TEST_DIR/bin" "$TEST_DIR/fixture/apache-maven-test/bin"
cp "$ROOT/mvnw" "$TEST_DIR/project/mvnw"
printf '#!/bin/sh\necho verified-wrapper-fixture\n' > "$TEST_DIR/fixture/apache-maven-test/bin/mvn"
(cd "$TEST_DIR/fixture" && "$JAVA_HOME/bin/jar" cf "$TEST_DIR/maven.zip" apache-maven-test)
CHECKSUM="$(sha256sum "$TEST_DIR/maven.zip" | cut -d ' ' -f 1)"
printf 'distributionUrl=https://fixture.invalid/org/apache/maven/apache-maven/test/apache-maven-test-bin.zip\ndistributionSha256Sum=%s\n' "$CHECKSUM" > "$TEST_DIR/project/.mvn/wrapper/maven-wrapper.properties"
# Restrict PATH so unzip is absent even on a developer machine that has it.
for tool in bash sh uname dirname basename tr mktemp mkdir rm mv cp chmod sha256sum cat sed; do
  ln -s "$(command -v "$tool")" "$TEST_DIR/bin/$tool"
done
cat > "$TEST_DIR/bin/wget" <<'FETCH'
#!/bin/sh
while [ "$#" -gt 0 ]; do
  if [ "$1" = -O ]; then cp "$WRAPPER_TEST_ARCHIVE" "$2"; exit; fi
  shift
done
exit 1
FETCH
chmod +x "$TEST_DIR/bin/wget"
export WRAPPER_TEST_ARCHIVE="$TEST_DIR/maven.zip"
export MAVEN_USER_HOME="$TEST_DIR/cache"
RESULT="$(cd "$TEST_DIR/project" && PATH="$TEST_DIR/bin" bash mvnw --version)"
[[ "$RESULT" == verified-wrapper-fixture ]]
sed -i "s/$CHECKSUM/0000000000000000000000000000000000000000000000000000000000000000/" "$TEST_DIR/project/.mvn/wrapper/maven-wrapper.properties"
export MAVEN_USER_HOME="$TEST_DIR/rejected-cache"
if (cd "$TEST_DIR/project" && PATH="$TEST_DIR/bin" bash mvnw --version > "$TEST_DIR/rejected.log" 2>&1); then
  echo 'Incorrect Maven checksum was accepted' >&2
  exit 1
fi
grep -q 'Failed to validate Maven distribution SHA-256' "$TEST_DIR/rejected.log"
echo 'Verified ZIP extraction without unzip and rejected an incorrect checksum.'
