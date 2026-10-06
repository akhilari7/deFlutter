#!/system/bin/sh
# FlutterInspect installer script -- by Akshay Khilari
# Forked from FlutterTap by Eduardo Lopes.

SKIPUNZIP=1

ui_print "- FlutterInspect"
ui_print "  by Akshay Khilari"
ui_print "- Extracting module files"

unzip -o "$ZIPFILE" -x 'META-INF/*' -d "$MODPATH" >&2 || abort "! Failed to extract module files"
[ -f "$MODPATH/zygisk/arm64-v8a.so" ] || abort "! Module payload is missing after extraction"

if [ -n "$KSU" ]; then
  ui_print "- Detected KernelSU"
elif [ -n "$APATCH" ]; then
  ui_print "- Detected APatch"
elif [ -n "$MAGISK_VER_CODE" ]; then
  ui_print "- Detected Magisk"
fi
# Persistent config directory under /data/adb
mkdir -p /data/adb/flutterlens
if [ ! -f /data/adb/flutterlens/config.json ]; then
  cat << 'EOF' > /data/adb/flutterlens/config.json
{
  "enabled": true,
  "target_packages": ["co.ostorlab.insecure_app"],
  "mode": "keyword_search",
  "keywords": ["HttpClient", "Authentication", "LocalAuth"]
}
EOF
  chmod 644 /data/adb/flutterlens/config.json
fi

ui_print "- Config location:"
ui_print "    /data/adb/flutterlens/config.json"
ui_print "- Then read results:"
ui_print "    adb logcat -s FlutterInspect:V"
ui_print "    adb pull /data/local/tmp/flutterlens_<pkg>.json"
