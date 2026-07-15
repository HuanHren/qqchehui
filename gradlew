#!/usr/bin/env sh
set -eu
GRADLE_VERSION=8.9
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BOOT="$ROOT/.gradle-bootstrap"
DIST="$BOOT/gradle-$GRADLE_VERSION"
ZIP="$BOOT/gradle-$GRADLE_VERSION-bin.zip"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$BOOT"
  URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -L "$URL" -o "$ZIP"
  else
    wget -O "$ZIP" "$URL"
  fi
  unzip -q -o "$ZIP" -d "$BOOT"
  rm -f "$ZIP"
fi
exec "$DIST/bin/gradle" "$@"
