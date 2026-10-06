#!/system/bin/sh
# FlutterInspect uninstaller -- by Akshay Khilari
# Deliberately NOT removing /data/local/tmp/flutterlens_config.json or results:
# config survives reinstall, results are kept for offline review.
rm -rf /data/local/tmp/flutterlens_*.json 2>/dev/null || true
