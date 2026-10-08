// FlutterInspect native module -- by Akshay Khilari
//
// Writes enumeration results to logcat + JSON file.
//
// Output JSON schema:
// {
//   "process": "com.example.app",
//   "lib": "libapp.so",
//   "lib_base": "0x70abc12000",
//   "count": 42,
//   "functions": [
//     {
//       "name": "LoginBloc.authenticate",
//       "abs_addr": "0x70abc56780",
//       "offset": "0x44780",
//       "xref_addr": "0x70abc56740"
//     },
//     ...
//   ]
// }
#include "result_writer.h"

#include <cstdio>
#include <ctime>
#include <sstream>
#include <string>

#include "log.h"

namespace {

// Escape a string for inclusion in a JSON value.
std::string jsonEscape(const std::string &s) {
    std::string out;
    out.reserve(s.size() + 4);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char hex[8];
                    snprintf(hex, sizeof(hex), "\\u%04x", static_cast<unsigned char>(c));
                    out += hex;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

std::string buildJson(const std::string &process_name,
                      const MappedModule &lib_mod,
                      const std::vector<FunctionEntry> &results,
                      const DartSnapshotInfo &snapshot_info) {
    char libBase[32];
    snprintf(libBase, sizeof(libBase), "0x%zx", lib_mod.base);

    std::string libName = lib_mod.path.substr(lib_mod.path.rfind('/') + 1);

    std::ostringstream j;
    j << "{\n";
    j << "  \"process\": \"" << jsonEscape(process_name) << "\",\n";
    j << "  \"lib\": \""     << jsonEscape(libName)       << "\",\n";
    j << "  \"lib_base\": \"" << libBase                  << "\",\n";
    j << "  \"count\": "     << results.size()             << ",\n";

    if (snapshot_info.valid) {
        char magicStr[16], instrOff[32], isoData[32], isoInstr[32];
        snprintf(magicStr, sizeof(magicStr), "0x%08x", snapshot_info.magic);
        snprintf(instrOff, sizeof(instrOff), "0x%zx", snapshot_info.instructions_offset);
        snprintf(isoData, sizeof(isoData), "0x%zx", snapshot_info.isolate_data_addr);
        snprintf(isoInstr, sizeof(isoInstr), "0x%zx", snapshot_info.isolate_instructions_addr);

        j << "  \"snapshot\": {\n";
        j << "    \"valid\": true,\n";
        j << "    \"magic\": \"" << magicStr << "\",\n";
        j << "    \"kind\": \"" << jsonEscape(snapshot_info.kind_name) << "\",\n";
        j << "    \"hash\": \"" << jsonEscape(snapshot_info.hash) << "\",\n";
        j << "    \"features\": \"" << jsonEscape(snapshot_info.features) << "\",\n";
        j << "    \"num_clusters\": " << snapshot_info.num_clusters << ",\n";
        j << "    \"num_objects\": " << snapshot_info.num_objects << ",\n";
        j << "    \"num_base_objects\": " << snapshot_info.num_base_objects << ",\n";
        j << "    \"instructions_offset\": \"" << instrOff << "\",\n";
        j << "    \"isolate_data_addr\": \"" << isoData << "\",\n";
        j << "    \"isolate_instructions_addr\": \"" << isoInstr << "\",\n";
        j << "    \"symbols_count\": " << snapshot_info.symbols.size() << ",\n";
        j << "    \"symbols\": [\n";
        for (size_t s = 0; s < snapshot_info.symbols.size(); ++s) {
            j << "      \"" << jsonEscape(snapshot_info.symbols[s]) << "\"";
            if (s + 1 < snapshot_info.symbols.size()) j << ",";
            j << "\n";
        }
        j << "    ]\n";
        j << "  },\n";
    }

    j << "  \"functions\": [\n";

    for (size_t i = 0; i < results.size(); ++i) {
        const auto &e = results[i];
        char absAddr[32], offset[32], xrefAddr[32];
        snprintf(absAddr,  sizeof(absAddr),  "0x%zx", e.abs_addr);
        snprintf(offset,   sizeof(offset),   "0x%zx", e.offset_from_base);
        snprintf(xrefAddr, sizeof(xrefAddr), "0x%zx", e.xref_addr);

        j << "    {\n";
        j << "      \"name\": \""      << jsonEscape(e.name) << "\",\n";
        j << "      \"abs_addr\": \""  << absAddr             << "\",\n";
        j << "      \"offset\": \""    << offset              << "\",\n";
        j << "      \"xref_addr\": \"" << xrefAddr            << "\"\n";
        j << "    }";
        if (i + 1 < results.size()) j << ",";
        j << "\n";
    }

    j << "  ]\n}\n";
    return j.str();
}

} // namespace

void write_results(const std::string              &process_name,
                   const MappedModule             &lib_mod,
                   const std::vector<FunctionEntry> &results,
                   const DartSnapshotInfo         &snapshot_info) {

    fi_log_info("=== FlutterInspect results for %s (%zu functions) ===",
                process_name.c_str(), results.size());

    if (snapshot_info.valid) {
        fi_log_info("  [Dart Snapshot] Hash: %s | Clusters: %llu | Objects: %llu",
                    snapshot_info.hash.c_str(),
                    static_cast<unsigned long long>(snapshot_info.num_clusters),
                    static_cast<unsigned long long>(snapshot_info.num_objects));
    }

    // Log up to 50 results individually to avoid overflowing the logcat ring buffer
    size_t logLimit = results.size() > 50 ? 50 : results.size();
    for (size_t i = 0; i < logLimit; ++i) {
        const auto &e = results[i];
        fi_log_info("  FUNC  %-40s  offset=0x%zx  abs=0x%zx  xref=0x%zx  lib=%s",
                    e.name.c_str(),
                    e.offset_from_base,
                    e.abs_addr,
                    e.xref_addr,
                    e.lib_name.c_str());
    }
    if (results.size() > logLimit) {
        fi_log_info("  ... and %zu more functions (all %zu written to JSON file)",
                    results.size() - logLimit, results.size());
    }
    fi_log_info("=== end FlutterInspect results ===");

    // Also write JSON to /data/local/tmp for adb pull
    // Sanitise the package name for use as a filename (replace ':' with '_')
    std::string safePkg = process_name;
    for (char &c : safePkg) if (c == ':') c = '_';

    char path[256];
    snprintf(path, sizeof(path), "/data/data/%s/cache/flutterlens.json", process_name.c_str());

    std::string json = buildJson(process_name, lib_mod, results, snapshot_info);

    FILE *f = fopen(path, "w");
    if (!f) {
        snprintf(path, sizeof(path), "/data/user/0/%s/cache/flutterlens.json", process_name.c_str());
        f = fopen(path, "w");
    }
    if (!f) {
        snprintf(path, sizeof(path), "/data/data/%s/files/flutterlens.json", process_name.c_str());
        f = fopen(path, "w");
    }
    if (!f) {
        snprintf(path, sizeof(path), "/data/local/tmp/flutterlens_%s.json", safePkg.c_str());
        f = fopen(path, "w");
    }
    if (!f) {
        fi_log_warn("result_writer: could not open file for writing — results are in logcat only");
        return;
    }
    fwrite(json.data(), 1, json.size(), f);
    fclose(f);
    fi_log_info("result_writer: wrote %zu bytes to %s", json.size(), path);
    fi_log_info("result_writer: pull with:  adb shell su -c 'cat %s'", path);
}
