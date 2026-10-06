// FlutterInspect native module -- by Akshay Khilari
//
// Scans the .rodata segment of libflutter.so / libapp.so for string literals.
// Two modes:
//   keyword_search  — find occurrences of specific user-supplied substrings
//   full_enumerate  — collect all null-terminated strings that look like
//                     Dart identifiers (ClassName.method, get:name, etc.)
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "elf_utils.h"

struct StringMatch {
    std::string value;    // the string literal found in .rodata
    uintptr_t   address;  // runtime address of the first byte in memory
};

// Scan .rodata for exact occurrences of `keyword` (case-sensitive, substring).
// Returns one match per occurrence of the string in memory.
std::vector<StringMatch> scan_rodata_for_keyword(const MappedModule &mod,
                                                  const ElfSegments  &segs,
                                                  const std::string  &keyword);

// Scan entire .rodata and return all null-terminated strings that look like
// Dart identifiers. Heuristics:
//   - Length in [4, 256]
//   - All printable ASCII
//   - Contains a '.' (ClassName.method) OR starts with "get:" / "set:" /
//     "dyn:" / is a Dart internal pattern
//   - Does NOT look like a URL, file path, or hex string
std::vector<StringMatch> scan_rodata_all_dart_strings(const MappedModule &mod,
                                                       const ElfSegments  &segs);
