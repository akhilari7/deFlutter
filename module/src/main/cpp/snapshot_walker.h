// FlutterInspect native module
//
// Dart VM Snapshot Cluster Walker -- extracts metadata, SDK hash, VM features,
// object counts, cluster headers, and serialized symbols from Dart AOT snapshots.
#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include "elf_utils.h"

struct DartSnapshotInfo {
    bool valid = false;
    uint32_t magic = 0;
    uint64_t length = 0;
    uint64_t kind = 0;
    std::string kind_name;
    std::string hash;
    std::string features;
    uint64_t num_base_objects = 0;
    uint64_t num_objects = 0;
    uint64_t num_clusters = 0;

    uintptr_t vm_data_addr = 0;
    uintptr_t vm_instructions_addr = 0;
    uintptr_t isolate_data_addr = 0;
    uintptr_t isolate_instructions_addr = 0;

    uintptr_t instructions_offset = 0;
    size_t instructions_size = 0;

    // Extracted Dart symbols directly from the serialized snapshot
    std::vector<std::string> symbols;
};

// Inspects and walks Dart snapshot data in the target library (libapp.so)
DartSnapshotInfo walk_dart_snapshot(const MappedModule &mod, const ElfSegments &segs);
