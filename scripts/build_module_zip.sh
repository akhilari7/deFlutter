#!/usr/bin/env bash
# Builds the FlutterInspect native module and packages a flashable
# Magisk/KernelSU/APatch zip in dist/.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "==> Building and packaging FlutterInspect via Gradle"
if [ -f "./gradlew" ]; then
    ./gradlew :module:packageMagiskZip
elif command -v gradle &>/dev/null; then
    gradle :module:packageMagiskZip
else
    echo "Error: Neither ./gradlew nor gradle found."
    exit 1
fi

echo "==> Done. Flashable zip is located in dist/"
ls -la dist/*.zip || true
