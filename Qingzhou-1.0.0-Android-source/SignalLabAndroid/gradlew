#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=8.9
GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$HOME/.gradle"}
DIST_DIR="$GRADLE_USER_HOME/wrapper/dists/gradle-${GRADLE_VERSION}-bin/signallab"
GRADLE_BIN="$DIST_DIR/gradle-${GRADLE_VERSION}/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$DIST_DIR"
  ZIP="$DIST_DIR/gradle-${GRADLE_VERSION}-bin.zip"
  echo "Downloading Gradle ${GRADLE_VERSION}..."
  curl -fL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$ZIP"
  unzip -q -o "$ZIP" -d "$DIST_DIR"
fi
exec "$GRADLE_BIN" -p "$APP_HOME" "$@"
