// FlutterInspect native module
// Forked from FlutterTap by Eduardo Lopes. FlutterInspect by Akshay Khilari.
#pragma once

#include <string>
#include <vector>

// Config written by the user (via adb push in Phase 1) and read by the
// native module through the root companion process.
// Config file path: /data/local/tmp/flutterlens_config.json
//
// Example JSON:
// {
//   "enabled": true,
//   "target_packages": ["com.example.flutterapp"],
//   "mode": "keyword_search",
//   "keywords": ["LoginBloc", "HttpClient", "authenticate"]
// }
// For full enumeration:
// {
//   "enabled": true,
//   "target_packages": ["com.example.flutterapp"],
//   "mode": "full_enumerate"
// }
#include "dart_tracer.h"

struct ModuleConfig {
    bool enabled = true;
    std::vector<std::string> target_packages = {"co.ostorlab.insecure_app"};

    enum class Mode {
        KEYWORD_SEARCH,  // scan .rodata for user-supplied keyword strings
        FULL_ENUMERATE,  // dump all Dart-looking strings from .rodata
    };
    Mode mode = Mode::KEYWORD_SEARCH;
    bool discovery_enabled = false;
    std::vector<std::string> keywords = {"HttpClient", "Authentication", "LocalAuth"};

    // Native Dobby tracing targets
    std::vector<HookTarget> hooks;

    // Returns true if this process name is one of the configured targets.
    // Also matches named isolate sub-processes: "com.pkg:remote" matches "com.pkg".
    bool isTargeted(const std::string &process_name) const;
};

ModuleConfig module_config_parse(const std::string &json_text);
