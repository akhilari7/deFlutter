// FlutterInspect native module -- by Akshay Khilari
//
// Zygisk module entry point. Forked from FlutterTap by Eduardo Lopes.
//
// Lifecycle:
//   onLoad()              — called in zygote; do NOTHING that mutates global state
//                           (ZN Linker preloads modules in zygote and signal
//                            dispositions survive fork)
//   preAppSpecialize()    — check if this process is a target; DLCLOSE if not
//   postAppSpecialize()   — spawn background monitoring thread
//   [monitoring thread]   — poll dl_iterate_phdr for libapp.so / libflutter.so
//                           → parse ELF segments
//                           → scan .rodata for strings
//                           → resolve function addresses via Capstone
//                           → write results to logcat + /data/local/tmp/
#include <pthread.h>
#include <unistd.h>

#include <memory>
#include <string>
#include <vector>

#include "companion.h"
#include "dart_resolver.h"
#include "elf_utils.h"
#include "log.h"
#include "mem_scan.h"
#include "module_config.h"
#include "result_writer.h"
#include "rodata_scan.h"
#include "dart_tracer.h"
#include "snapshot_walker.h"
#include "zygisk.hpp"

namespace {

// Libraries to watch for — libapp.so is the primary Dart AOT snapshot host;
// libflutter.so is the fallback for builds that embed the snapshot inline.
constexpr const char *kAppLib     = "libapp.so";
constexpr const char *kFlutterLib = "libflutter.so";

// Fast polling (2 ms) during initial startup for up to 2 seconds, then 50 ms.
constexpr int kFastPollIntervalUs   = 2 * 1000;
constexpr int kNormalPollIntervalUs = 50 * 1000;
constexpr int kFastPollAttempts     = 1000; // First 2 seconds
constexpr int kMaxPollAttempts      = 20 * 60 * (1000000 / kNormalPollIntervalUs);

struct MonitorContext {
    std::string  processName;
    ModuleConfig cfg;
};

// ---------------------------------------------------------------------------
// Enumeration pipeline — runs on the background thread after the target
// library loads.
// ---------------------------------------------------------------------------
void runEnumeratePipeline(const std::string  &processName,
                          const ModuleConfig &cfg,
                          const MappedModule &targetMod) {

    // Install SIGSEGV/SIGBUS guard ONLY here, never in onLoad() or before we
    // know this process is a target (see companion comment block in main.cpp).
    mem_scan_install_crash_guard();

    fi_log_info("%s: target library loaded at 0x%zx (%s)",
                processName.c_str(), targetMod.base, targetMod.path.c_str());

    ElfSegments segs;
    if (!parse_elf_segments(targetMod, segs)) {
        fi_log_error("%s: failed to parse ELF segments — aborting", processName.c_str());
        return;
    }

    fi_log_info("%s: .text  @ 0x%zx + 0x%zx bytes",
                processName.c_str(), targetMod.base + segs.text_vaddr, segs.text_memsz);
    fi_log_info("%s: .rodata @ 0x%zx + 0x%zx bytes (memsz 0 = packed into text)",
                processName.c_str(), targetMod.base + segs.rodata_vaddr, segs.rodata_memsz);

    // Collect string matches according to mode
    std::vector<StringMatch> matches;

    // In case companion was not available in preAppSpecialize, reload config with full
    // fallback chain: app cache → global adb config → tmp. This ensures keywords deployed
    // via the Manager App are always used, never stale hardcoded module defaults.
    ModuleConfig activeCfg = cfg;
    std::string appCacheConfigPath = "/data/data/" + processName + "/cache/flutterlens_config.json";
    const char *fallbackPaths[] = {
        "/data/local/tmp/flutterlens_config.json",
        "/data/adb/flutterlens/config.json",
        appCacheConfigPath.c_str(),
        nullptr
    };
    for (int pi = 0; fallbackPaths[pi] != nullptr; ++pi) {
        FILE *cf = fopen(fallbackPaths[pi], "rb");
        if (!cf) continue;
        std::string content;
        char buf[4096];
        size_t n;
        while ((n = fread(buf, 1, sizeof(buf), cf)) > 0) content.append(buf, n);
        fclose(cf);
        if (!content.empty()) {
            ModuleConfig parsed = module_config_parse(content);
            // Only accept if this config targets our process (guards against reading
            // a config left over from a different target app)
            if (parsed.isTargeted(processName) || parsed.target_packages.empty()) {
                activeCfg = parsed;
                fi_log_info("%s: reloaded config from %s (%zu keywords)",
                            processName.c_str(), fallbackPaths[pi], activeCfg.keywords.size());
                break;
            }
        }
    }

    bool isAppLib = (targetMod.path.find("libapp.so") != std::string::npos);

    // Fast-path: Initialize dart_tracer and apply native hooks immediately before
    // any expensive snapshot cluster walking or string scanning. This guarantees
    // early RASP / anti-root / anti-tamper hooks are active before Dart initialization completes.
    dart_tracer_init(targetMod.base, segs.text_memsz, processName);
    if (!activeCfg.hooks.empty()) {
        fi_log_info("%s: applying %zu native hooks from config (immediate early attach)",
                    processName.c_str(), activeCfg.hooks.size());
        dart_tracer_sync_hooks(activeCfg.hooks);
    }

    if (isAppLib) {
        DartSnapshotInfo snapInfo = walk_dart_snapshot(targetMod, segs);

        std::vector<FunctionEntry> entries;

        if (activeCfg.discovery_enabled) {
            if (activeCfg.mode == ModuleConfig::Mode::KEYWORD_SEARCH) {
                std::vector<std::string> searchKeywords = activeCfg.keywords;

                auto to_lower = [](const std::string &s) {
                    std::string res = s;
                    for (char &c : res) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));
                    return res;
                };

                auto strip_us = [](const std::string &s) {
                    std::string res;
                    for (char c : s) if (c != '_') res.push_back(c);
                    return res;
                };

                // Match user keywords against discovered snapshot packages and enrich keyword set
                for (const auto &kw : activeCfg.keywords) {
                    std::string lkw = to_lower(kw);
                    std::string skw = strip_us(lkw);

                    for (const auto &pkg : snapInfo.symbols) {
                        std::string lpkg = to_lower(pkg);
                        std::string spkg = strip_us(lpkg);

                        if (lpkg.find(lkw) != std::string::npos || (!skw.empty() && spkg.find(skw) != std::string::npos)) {
                            size_t lastSlash = pkg.rfind('/');
                            std::string baseFile = (lastSlash != std::string::npos && lastSlash + 1 < pkg.size())
                                                       ? pkg.substr(lastSlash + 1)
                                                       : pkg;
                            if (baseFile.size() > 5 && baseFile.substr(baseFile.size() - 5) == ".dart") {
                                baseFile = baseFile.substr(0, baseFile.size() - 5);
                            }

                            if (std::find(searchKeywords.begin(), searchKeywords.end(), baseFile) == searchKeywords.end()) {
                                searchKeywords.push_back(baseFile);
                                fi_log_info("%s: snapshot package '%s' matched keyword '%s' -> added '%s'",
                                            processName.c_str(), pkg.c_str(), kw.c_str(), baseFile.c_str());
                            }

                            std::string cleanBase = strip_us(baseFile);
                            if (cleanBase != baseFile && std::find(searchKeywords.begin(), searchKeywords.end(), cleanBase) == searchKeywords.end()) {
                                searchKeywords.push_back(cleanBase);
                            }
                        }
                    }
                }

                fi_log_info("%s: resolving Dart functions matching %zu keyword(s) (expanded from %zu) in libapp.so",
                            processName.c_str(), searchKeywords.size(), activeCfg.keywords.size());
                entries = resolve_dart_snapshot_functions(targetMod, segs, searchKeywords);
            } else {
                fi_log_info("%s: running Dart AOT function enumerator on libapp.so", processName.c_str());
                entries = enumerate_dart_functions(targetMod, segs);
            }
        } else {
            fi_log_info("%s: live discovery mode is disabled; skipping rodata scan (using snapshot metadata & direct tracer)",
                        processName.c_str());
        }

        write_results(processName, targetMod, entries, snapInfo);
        return;
    }

    if (cfg.mode == ModuleConfig::Mode::KEYWORD_SEARCH) {
        if (cfg.keywords.empty()) {
            fi_log_warn("%s: keyword_search mode but no keywords configured", processName.c_str());
            return;
        }
        for (const auto &kw : cfg.keywords) {
            auto kmatches = scan_rodata_for_keyword(targetMod, segs, kw);
            matches.insert(matches.end(), kmatches.begin(), kmatches.end());
        }
    } else {
        // FULL_ENUMERATE
        matches = scan_rodata_all_dart_strings(targetMod, segs);
        if (matches.size() > 5000) {
            fi_log_warn("%s: %zu candidates — capping at 5000 for this run",
                        processName.c_str(), matches.size());
            matches.resize(5000);
        }
    }

    fi_log_info("%s: %zu string match(es) to resolve", processName.c_str(), matches.size());
    if (matches.empty()) return;

    // Resolve each string to the function(s) that reference it
    auto entries = resolve_functions_for_strings(targetMod, segs, matches);

    // Write to logcat + /data/local/tmp/flutterlens_<pkg>.json
    write_results(processName, targetMod, entries);
}

// ---------------------------------------------------------------------------
// Background monitoring thread — polls for the target library
// ---------------------------------------------------------------------------
void *monitorThread(void *arg) {
    auto *ctx = static_cast<MonitorContext *>(arg);
    std::string processName = ctx->processName;
    ModuleConfig cfg        = ctx->cfg;
    delete ctx;

    fi_log_info("%s: monitor thread started", processName.c_str());

    bool scannedApp = false;
    bool scannedFlutter = false;

    for (int attempt = 0; attempt < kMaxPollAttempts; ++attempt) {
        // Always prioritize libapp.so (Dart AOT snapshot)
        MappedModule appMod;
        if (!scannedApp && find_module_by_suffix(kAppLib, appMod)) {
            fi_log_info("%s: found Dart snapshot library: %s (attempt %d)",
                        processName.c_str(), appMod.path.c_str(), attempt);
            runEnumeratePipeline(processName, cfg, appMod);
            scannedApp = true;
        }

        // If after 5 seconds libapp.so has not appeared, scan libflutter.so as fallback
        if (!scannedApp && !scannedFlutter && attempt > 100) {
            MappedModule flutterMod;
            if (find_module_by_suffix(kFlutterLib, flutterMod)) {
                fi_log_info("%s: libapp.so not yet found, scanning libflutter.so: %s",
                            processName.c_str(), flutterMod.path.c_str());
                runEnumeratePipeline(processName, cfg, flutterMod);
                scannedFlutter = true;
            }
        }

        if (scannedApp) {
            break;
        }

        int intervalUs = (attempt < kFastPollAttempts) ? kFastPollIntervalUs : kNormalPollIntervalUs;
        usleep(static_cast<useconds_t>(intervalUs));
    }

    if (scannedApp) {
        fi_log_info("%s: Dart analysis finished; entering dynamic hook monitor loop", processName.c_str());
            std::string appCacheConfigPath = "/data/data/" + processName + "/cache/flutterlens_config.json";
            while (true) {
                usleep(500 * 1000); // Poll every 500ms
                FILE *cf = fopen("/data/local/tmp/flutterlens_config.json", "rb");
                if (!cf) cf = fopen("/data/adb/flutterlens/config.json", "rb");
                if (!cf) cf = fopen(appCacheConfigPath.c_str(), "rb");
                if (cf) {
                    std::string content;
                    char buf[4096];
                    size_t n;
                    while ((n = fread(buf, 1, sizeof(buf), cf)) > 0) content.append(buf, n);
                    fclose(cf);
                    if (!content.empty()) {
                        ModuleConfig updated = module_config_parse(content);
                        if (updated.isTargeted(processName) || updated.target_packages.empty()) {
                            dart_tracer_sync_hooks(updated.hooks);
                        }
                    }
                }
            }
            return nullptr;
        }

    fi_log_warn("%s: timed out waiting for libapp.so", processName.c_str());
    return nullptr;
}

// ---------------------------------------------------------------------------
// Zygisk module implementation
// ---------------------------------------------------------------------------
class FlutterInspect : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
        // DO NOT install signal handlers or mutate any global state here.
        // onLoad() runs in zygote under Zygisk Next Linker. Signal dispositions
        // survive fork(), so a handler installed here would be inherited by
        // every app on the device, including ones that DLCLOSE us — leaving the
        // kernel with a handler pointer into unmapped memory.
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        const char *rawName = args->nice_name
                                  ? env_->GetStringUTFChars(args->nice_name, nullptr)
                                  : nullptr;
        processName_ = rawName ? rawName : "";
        if (rawName) env_->ReleaseStringUTFChars(args->nice_name, rawName);

        // 1. Try reading config via the root companion process
        int companionFd = api_->connectCompanion();
        std::string configJson;
        if (companionFd >= 0) {
            fi_log_info("%s: connectCompanion connected (fd=%d)", processName_.c_str(), companionFd);
            configJson = companion_read_config(companionFd);
            close(companionFd);
            fi_log_info("%s: config via companion: %zu bytes", processName_.c_str(), configJson.size());
        } else {
            fi_log_warn("%s: connectCompanion failed (fd=%d)", processName_.c_str(), companionFd);
        }

        // 2. Direct read fallback (in case companion IPC is empty or restricted)
        if (configJson.empty()) {
            std::string appCachePath = "/data/data/" + processName_ + "/cache/flutterlens_config.json";
            FILE *f = fopen(appCachePath.c_str(), "rb");
            if (!f) f = fopen("/data/adb/flutterlens/config.json", "rb");
            if (!f) f = fopen("/data/local/tmp/flutterlens_config.json", "rb");
            if (f) {
                char buf[4096];
                size_t n;
                while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
                    configJson.append(buf, n);
                }
                fclose(f);
                fi_log_info("%s: direct read fallback succeeded: %zu bytes", processName_.c_str(), configJson.size());
            } else {
                fi_log_warn("%s: all direct config fallback paths failed", processName_.c_str());
            }
        }

        cfg_ = module_config_parse(configJson);

        if (!cfg_.isTargeted(processName_)) {
            // Not a target — unload this library immediately and SILENTLY.
            // Zero footprint, zero logcat noise on non-target apps.
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        fi_log_info("=== [FlutterInspect] %s TARGETED! mode=%s keywords=%zu ===",
                    processName_.c_str(),
                    cfg_.mode == ModuleConfig::Mode::FULL_ENUMERATE
                        ? "full_enumerate" : "keyword_search",
                    cfg_.keywords.size());
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!cfg_.isTargeted(processName_)) return;

        // Spawn the monitoring thread. The Zygisk API (api_) is only valid
        // until postAppSpecialize returns, but the thread needs no Zygisk API
        // calls — it only uses dl_iterate_phdr, safe_read, and Capstone.
        auto *ctx       = new MonitorContext{processName_, cfg_};
        pthread_t tid;
        if (pthread_create(&tid, nullptr, monitorThread, ctx) != 0) {
            fi_log_error("%s: failed to start monitor thread", processName_.c_str());
            delete ctx;
            return;
        }
        pthread_detach(tid);
    }

private:
    zygisk::Api  *api_  = nullptr;
    JNIEnv       *env_  = nullptr;
    std::string   processName_;
    ModuleConfig  cfg_;
};

} // namespace

// ---------------------------------------------------------------------------
// Zygisk registration
// ---------------------------------------------------------------------------
REGISTER_ZYGISK_MODULE(FlutterInspect)

REGISTER_ZYGISK_COMPANION(companion_handler)
