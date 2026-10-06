// FlutterInspect native module -- by Akshay Khilari
//
// Generalised function resolver: given a string literal's address in .rodata,
// finds the function(s) in .text that reference it via adrp/add (arm64) or
// lea (x86_64), then backtracks to the function prologue entry point.
//
// This is a generalisation of FlutterTap's addr_resolver.cpp which performed
// the same operation for exactly two specific strings (ssl_client,
// Socket_CreateConnect). Here we do it for any arbitrary string.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "elf_utils.h"
#include "rodata_scan.h"

struct FunctionEntry {
    std::string name;            // the string literal that led us here
    uintptr_t   abs_addr;        // runtime address of function entry point
    uintptr_t   offset_from_base;// abs_addr - module.base (paste into Frida)
    uintptr_t   xref_addr;       // address of the adrp/lea instruction in .text
    std::string lib_name;        // "libapp.so" or "libflutter.so"
};

// For each StringMatch, find all functions in .text that reference the string.
// Returns a (possibly empty) list — one string can be referenced by N functions.
std::vector<FunctionEntry> resolve_functions_for_strings(
    const MappedModule             &mod,
    const ElfSegments              &segs,
    const std::vector<StringMatch> &matches);

// Enumerate all Dart function entry points in a Dart AOT snapshot (.text)
// via canonical Dart function prologues.
std::vector<FunctionEntry> enumerate_dart_functions(
    const MappedModule &mod,
    const ElfSegments  &segs);

// Resolves Dart functions in libapp.so by parsing the Dart VM Code Object Table
// and matching Dart function names/symbols against keywords.
std::vector<FunctionEntry> resolve_dart_snapshot_functions(
    const MappedModule             &mod,
    const ElfSegments              &segs,
    const std::vector<std::string> &keywords);
