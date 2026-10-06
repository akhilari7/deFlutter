// FlutterInspect native module
// Forked from FlutterTap by Eduardo Lopes. FlutterInspect by Akshay Khilari.
//
// SIGSEGV/SIGBUS-guarded memory operations. Port of flutter+burp.js scanMemory().
#pragma once

#include <cstdint>
#include <string>
#include <vector>

// Installs a SIGSEGV/SIGBUS guard so a bad computed address logs and aborts
// the scan instead of crashing the host app. Not in the original one-shot
// Frida script (Frida's JS runtime isolates crashes) but necessary here since
// we run in-process for the whole app lifetime.
//
// Call once per process, and ONLY from a process this library stays loaded in
// for good — i.e. a confirmed target, never from onLoad(). The handler address
// points into this library, so installing it in a process that later DLCLOSEs
// us leaves the kernel jumping into unmapped memory on the next signal.
void mem_scan_install_crash_guard();

// Guarded memcpy: returns false (instead of crashing) if `src` is unreadable.
// Shared by mem_scan.cpp, dart_resolver.cpp and elf_utils.cpp.
bool safe_read(void *dst, const void *src, size_t size);

// Byte-pattern descriptor for scan_all_matches().
// `mask[i] == 0xFF` means `value[i]` must match exactly.
// `mask[i] == 0x00` means wildcard (any byte).
struct BytePattern {
    std::vector<uint8_t> value;
    std::vector<uint8_t> mask;
};

// Compiles a pattern from a hex string with '??' wildcards.
// E.g. "90 ?? ?? 91" -> BytePattern that matches ADRP-like encodings.
BytePattern compile_pattern(const std::string &hex_pattern);

// Scans [base, base+size) for all occurrences of `pattern`.
// Returns a vector of match start addresses.
std::vector<uintptr_t> scan_all_matches(uintptr_t base, size_t size,
                                        const BytePattern &pattern);
