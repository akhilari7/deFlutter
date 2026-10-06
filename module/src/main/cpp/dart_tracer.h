// FlutterInspect native Dart AOT function tracer using Dobby
// Author: Akshay Khilari (Payatu)
#pragma once

#include <cstdint>
#include <string>
#include <vector>

struct HookTarget {
    uintptr_t offset = 0;       // Offset from libapp.so base
    std::string name;           // Human-readable function name
    bool trace_return = true;   // Whether to intercept and log return value
    std::vector<std::string> param_names; // Semantic parameter names
};

// Initializes the Dart tracer with library base address and size.
void dart_tracer_init(uintptr_t lib_base, size_t lib_size, const std::string &process_name);

// Hook a specific Dart function
bool dart_tracer_hook(const HookTarget &target);

// Unhook a specific Dart function
bool dart_tracer_unhook(uintptr_t offset);

// Synchronize active hooks with a new target list (adds new, removes obsolete)
void dart_tracer_sync_hooks(const std::vector<HookTarget> &targets);

// Get number of currently installed hooks
size_t dart_tracer_active_count();
