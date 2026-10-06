#!/usr/bin/env bash
# setup.sh — one-time project setup for FlutterInspect
# Run this after cloning (on a machine with git, or in WSL/Git Bash).
set -e

echo "[1/3] Initialising git repo..."
git init

echo "[2/3] Cloning Capstone (arm64+x86_64 only)..."
git submodule add https://github.com/capstone-engine/capstone.git \
    module/src/main/cpp/third_party/capstone

echo "[3/3] Setting up Gradle wrapper..."
# If you have Gradle installed locally, run: gradle wrapper
# Otherwise, copy gradlew/gradlew.bat from the FlutterTap repo or any Android project.
# Minimum Gradle version: 8.10

echo ""
echo "Setup complete. Build with:"
echo "  ./gradlew :module:assembleRelease"
echo ""
echo "Before testing, push a config to your device:"
echo "  adb push config_keyword_example.json /data/local/tmp/flutterlens_config.json"
