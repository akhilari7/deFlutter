#!/system/bin/sh
# FlutterInspect action script -- by Akshay Khilari
# Tapped from the Magisk/KSU module list: prints current config and latest results.
echo "=== FlutterInspect config ==="
cat /data/local/tmp/flutterlens_config.json 2>/dev/null || echo "(no config found)"
echo ""
echo "=== Latest result files ==="
ls /data/local/tmp/flutterlens_*.json 2>/dev/null || echo "(no results yet)"
