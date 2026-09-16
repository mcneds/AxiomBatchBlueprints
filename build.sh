#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

JAVA_MAJOR="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
if [[ "$JAVA_MAJOR" != "25" ]]; then
    echo "Java 25 is required. Current:"
    java -version
    exit 1
fi

GRADLE_VERSION="9.5.1"
BOOTSTRAP="$PWD/.gradle-bootstrap"
GRADLE_HOME="$BOOTSTRAP/gradle-$GRADLE_VERSION"

if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
    mkdir -p "$BOOTSTRAP"
    ZIP="$BOOTSTRAP/gradle-$GRADLE_VERSION-bin.zip"

    echo "Downloading Gradle $GRADLE_VERSION..."
    curl -fL \
      "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
      -o "$ZIP"

    rm -rf "$GRADLE_HOME"
    unzip -q "$ZIP" -d "$BOOTSTRAP"
fi

"$GRADLE_HOME/bin/gradle" --no-daemon clean build

echo
echo "Built:"
find build/libs -maxdepth 1 -type f \
  -name '*.jar' -not -name '*sources*' -print
