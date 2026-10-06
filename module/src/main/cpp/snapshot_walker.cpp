// FlutterInspect native module
//
// Dart VM Snapshot Cluster Walker -- extracts metadata, SDK hash, VM features,
// object counts, cluster headers, and serialized symbols from Dart AOT snapshots.
#include "snapshot_walker.h"

#include <cstring>
#include <sstream>
#include "log.h"
#include "mem_scan.h"

namespace {

constexpr uint32_t kDartSnapshotMagic = 0xdcdcf5f5;

uint64_t read_uleb128(const uint8_t *&ptr, const uint8_t *end) {
    uint64_t result = 0;
    int shift = 0;
    while (ptr < end) {
        uint8_t byte = 0;
        if (!safe_read(&byte, ptr++, 1)) break;
        result |= static_cast<uint64_t>(byte & 0x7F) << shift;
        if ((byte & 0x80) == 0) break;
        shift += 7;
    }
    return result;
}

} // namespace

DartSnapshotInfo walk_dart_snapshot(const MappedModule &mod, const ElfSegments &segs) {
    DartSnapshotInfo info;

    fi_log_info("snapshot_walker: scanning module %s (base=0x%zx)", mod.path.c_str(), mod.base);

    // 1. Locate snapshot symbols in libapp.so
    info.isolate_data_addr = find_symbol_address(mod, "_kDartIsolateSnapshotData");
    if (!info.isolate_data_addr) info.isolate_data_addr = find_symbol_address(mod, "kDartIsolateSnapshotData");

    info.vm_data_addr = find_symbol_address(mod, "_kDartVmSnapshotData");
    if (!info.vm_data_addr) info.vm_data_addr = find_symbol_address(mod, "kDartVmSnapshotData");

    info.isolate_instructions_addr = find_symbol_address(mod, "_kDartIsolateSnapshotInstructions");
    if (!info.isolate_instructions_addr) info.isolate_instructions_addr = find_symbol_address(mod, "kDartIsolateSnapshotInstructions");

    info.vm_instructions_addr = find_symbol_address(mod, "_kDartVmSnapshotInstructions");
    if (!info.vm_instructions_addr) info.vm_instructions_addr = find_symbol_address(mod, "kDartVmSnapshotInstructions");

    if (info.isolate_data_addr) {
        fi_log_info("snapshot_walker: located _kDartIsolateSnapshotData at 0x%zx (offset 0x%zx)",
                    info.isolate_data_addr, info.isolate_data_addr - mod.base);
    } else {
        // Fallback: If symbols are not exported, scan from mod.base
        fi_log_info("snapshot_walker: symbols not in dynsym, scanning memory from base 0x%zx...", mod.base);
        uintptr_t scan_base = mod.base;
        size_t scan_size = 0x800000;

        for (size_t off = 0; off + 32 < scan_size; off += 4) {
            uint32_t val = 0;
            if (safe_read(&val, reinterpret_cast<const void *>(scan_base + off), 4)) {
                if (val == kDartSnapshotMagic) {
                    info.isolate_data_addr = scan_base + off;
                    fi_log_info("snapshot_walker: located snapshot magic at 0x%zx (offset 0x%zx)",
                                info.isolate_data_addr, off);
                    break;
                }
            }
        }
    }

    if (!info.isolate_data_addr) {
        fi_log_warn("snapshot_walker: could not locate Dart snapshot data");
        return info;
    }

    if (info.isolate_instructions_addr) {
        info.instructions_offset = info.isolate_instructions_addr - mod.base;
        info.instructions_size = 4 * 1024 * 1024; // Estimated/standard size
    }

    // 2. Read Snapshot Header from isolate_data_addr
    const uint8_t *p = reinterpret_cast<const uint8_t *>(info.isolate_data_addr);

    if (!safe_read(&info.magic, p, 4) || info.magic != kDartSnapshotMagic) {
        fi_log_warn("snapshot_walker: invalid snapshot magic (0x%08x)", info.magic);
        return info;
    }
    p += 4;

    if (!safe_read(&info.length, p, 8)) return info;
    p += 8;

    if (!safe_read(&info.kind, p, 8)) return info;
    p += 8;

    info.kind_name = (info.kind == 3) ? "kFullAOT" : "SnapshotKind(" + std::to_string(info.kind) + ")";

    // 3. Read features string (null terminated)
    char featureBuf[512] = {0};
    size_t featLen = 0;
    while (featLen < sizeof(featureBuf) - 1) {
        char c = 0;
        if (!safe_read(&c, p++, 1)) break;
        if (c == '\0') break;
        featureBuf[featLen++] = c;
    }
    featureBuf[featLen] = '\0';

    std::string fullFeatures(featureBuf);
    if (fullFeatures.size() >= 32) {
        info.hash = fullFeatures.substr(0, 32);
        info.features = (fullFeatures.size() > 32) ? fullFeatures.substr(32) : "";
        while (!info.features.empty() && info.features.front() == ' ') info.features.erase(info.features.begin());
    } else {
        info.hash = fullFeatures;
        info.features = "";
    }

    // 4. Read Cluster Metadata
    const uint8_t *end = reinterpret_cast<const uint8_t *>(info.isolate_data_addr + info.length);
    info.num_base_objects = read_uleb128(p, end);
    info.num_objects = read_uleb128(p, end);
    info.num_clusters = read_uleb128(p, end);

    info.valid = true;

    fi_log_info("=== Dart VM Snapshot Cluster Walker ===");
    fi_log_info("  Magic:        0x%08x (%s)", info.magic, info.kind_name.c_str());
    fi_log_info("  Length:       %zu bytes (0x%zx)", static_cast<size_t>(info.length), static_cast<size_t>(info.length));
    fi_log_info("  SDK Hash:     %s", info.hash.c_str());
    fi_log_info("  VM Features:  %s", info.features.c_str());
    fi_log_info("  Base Objects: %llu", static_cast<unsigned long long>(info.num_base_objects));
    fi_log_info("  Total Objects:%llu", static_cast<unsigned long long>(info.num_objects));
    fi_log_info("  Clusters:     %llu", static_cast<unsigned long long>(info.num_clusters));
    fi_log_info("  Code Offset:  0x%zx", info.instructions_offset);
    fi_log_info("========================================");

    // 5. Extract serialized Dart symbols directly from the snapshot payload
    // Scans through the snapshot buffer for Dart package identifiers and method strings
    size_t scanLimit = info.length > 2 * 1024 * 1024 ? 2 * 1024 * 1024 : static_cast<size_t>(info.length);
    const uint8_t *scanStart = reinterpret_cast<const uint8_t *>(info.isolate_data_addr);

    for (size_t i = 0; i + 8 < scanLimit; ++i) {
        char c0 = 0;
        if (!safe_read(&c0, scanStart + i, 1)) continue;

        // Check for "package:" or common Dart class prefixes
        if (c0 == 'p' && i + 8 < scanLimit) {
            char prefix[9] = {0};
            if (safe_read(prefix, scanStart + i, 8) && strcmp(prefix, "package:") == 0) {
                char strBuf[256] = {0};
                size_t sLen = 0;
                while (sLen < sizeof(strBuf) - 1) {
                    char sc = 0;
                    if (!safe_read(&sc, scanStart + i + sLen, 1)) break;
                    if (sc < 32 || sc > 126 || sc == '"' || sc == '\'') break;
                    strBuf[sLen++] = sc;
                }
                strBuf[sLen] = '\0';
                if (sLen >= 10) {
                    info.symbols.push_back(std::string(strBuf));
                    i += sLen;
                }
            }
        }
    }

    fi_log_info("snapshot_walker: extracted %zu package/library symbols from snapshot payload", info.symbols.size());
    return info;
}
