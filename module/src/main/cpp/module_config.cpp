// FlutterInspect native module
// Forked from FlutterTap by Eduardo Lopes. FlutterInspect by Akshay Khilari.
#include "module_config.h"

#include "json_min.h"
#include "log.h"

bool ModuleConfig::isTargeted(const std::string &process_name) const {
    if (!enabled) return false;
    for (const auto &pkg : target_packages) {
        if (process_name == pkg) return true;
        // Match named sub/isolate processes, e.g. "com.pkg:remote".
        if (process_name.size() > pkg.size() + 1 &&
            process_name.compare(0, pkg.size(), pkg) == 0 &&
            process_name[pkg.size()] == ':') {
            return true;
        }
    }
    return false;
}

ModuleConfig module_config_parse(const std::string &json_text) {
    ModuleConfig cfg; // defaults

    if (json_text.empty()) return cfg;

    JsonValue root;
    if (!json_parse(json_text, root) || !root.isObject()) {
        fi_log_warn("module_config: failed to parse JSON — using defaults");
        return cfg;
    }

    // enabled
    if (const auto *v = root.get("enabled"); v && v->isBool()) {
        cfg.enabled = v->asBool(true);
    }

    // target_packages (array form — preferred)
    if (const auto *v = root.get("target_packages"); v && v->isArray()) {
        cfg.target_packages.clear();
        for (const auto &elem : v->arrValue) {
            if (elem.isString() && !elem.strValue.empty()) {
                cfg.target_packages.push_back(elem.strValue);
            }
        }
    }
    // target_process (string form — written by Manager App for readability)
    // Only use if target_packages was absent or empty
    if (cfg.target_packages.empty()) {
        if (const auto *v = root.get("target_process"); v && v->isString() && !v->strValue.empty()) {
            cfg.target_packages.push_back(v->strValue);
        }
    }

    // mode
    if (const auto *v = root.get("mode"); v && v->isString()) {
        if (v->strValue == "full_enumerate") {
            cfg.mode = ModuleConfig::Mode::FULL_ENUMERATE;
        } else {
            cfg.mode = ModuleConfig::Mode::KEYWORD_SEARCH;
        }
    }

    // discovery_enabled
    if (const auto *v = root.get("discovery_enabled"); v && v->isBool()) {
        cfg.discovery_enabled = v->asBool(false);
    }

    // keywords (only relevant for KEYWORD_SEARCH mode)
    if (const auto *v = root.get("keywords"); v && v->isArray()) {
        cfg.keywords.clear();
        for (const auto &elem : v->arrValue) {
            if (elem.isString() && !elem.strValue.empty()) {
                cfg.keywords.push_back(elem.strValue);
            }
        }
    }

    // hooks (for native Dobby tracing)
    if (const auto *v = root.get("hooks"); v && v->isArray()) {
        cfg.hooks.clear();
        for (const auto &elem : v->arrValue) {
            if (elem.isObject()) {
                HookTarget ht;
                if (const auto *offVal = elem.get("offset")) {
                    if (offVal->isNumber()) {
                        ht.offset = static_cast<uintptr_t>(offVal->numValue);
                    } else if (offVal->isString()) {
                        ht.offset = std::strtoull(offVal->strValue.c_str(), nullptr, 0);
                    }
                }
                if (const auto *nameVal = elem.get("name"); nameVal && nameVal->isString()) {
                    ht.name = nameVal->strValue;
                }
                if (const auto *retVal = elem.get("trace_return"); retVal && retVal->isBool()) {
                    ht.trace_return = retVal->asBool(true);
                }
                if (const auto *pArr = elem.get("params"); pArr && pArr->isArray()) {
                    for (const auto &pElem : pArr->arrValue) {
                        if (pElem.isString()) {
                            ht.param_names.push_back(pElem.strValue);
                        }
                    }
                }
                if (ht.offset != 0) {
                    cfg.hooks.push_back(ht);
                }
            } else if (elem.isString()) {
                HookTarget ht;
                ht.offset = std::strtoull(elem.strValue.c_str(), nullptr, 0);
                if (ht.offset != 0) {
                    cfg.hooks.push_back(ht);
                }
            }
        }
    }

    fi_log_info("module_config: enabled=%d mode=%s targets=%zu keywords=%zu hooks=%zu",
                cfg.enabled,
                cfg.mode == ModuleConfig::Mode::FULL_ENUMERATE ? "full_enumerate" : "keyword_search",
                cfg.target_packages.size(),
                cfg.keywords.size(),
                cfg.hooks.size());

    return cfg;
}
