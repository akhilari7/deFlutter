// FlutterInspect native module -- by Akshay Khilari
//
// Writes enumeration results to:
//   1. logcat (tag "FlutterInspect") — visible via adb logcat -s FlutterInspect:V
//   2. /data/local/tmp/flutterlens_<pkg>.json — pull via adb pull
#pragma once

#include <string>
#include <vector>

#include "dart_resolver.h"
#include "elf_utils.h"
#include "snapshot_walker.h"

// Writes results to logcat and to /data/local/tmp/flutterlens_<pkg>.json.
// `lib_base` is the runtime base address of the library (for the JSON header).
void write_results(const std::string              &process_name,
                   const MappedModule             &lib_mod,
                   const std::vector<FunctionEntry> &results,
                   const DartSnapshotInfo         &snapshot_info = {});
