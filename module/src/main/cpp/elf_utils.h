// FlutterInspect native module
// Forked from FlutterTap by Eduardo Lopes. FlutterInspect by Akshay Khilari.
//
// ELF module lookup and segment parsing.
// Port of flutter+burp.js parseElf() / module lookup logic (Android/ELF only).
#pragma once

#include <cstdint>
#include <string>

struct MappedModule {
    uintptr_t base = 0;
    // The linker's own name for the module. When the APK was built with
    // extractNativeLibs=false (the modern AGP default), the library is mapped
    // directly out of the APK zip and this is a synthetic path like
    // ".../base.apk!/lib/arm64-v8a/libflutter.so" rather than a real file.
    std::string path;
};

struct ElfSegments {
    // .text (PT_LOAD, executable): where all code lives
    uintptr_t text_vaddr  = 0;
    size_t    text_memsz  = 0;
    // .rodata (PT_LOAD, non-writable, non-executable): string literals
    uintptr_t rodata_vaddr = 0;
    size_t    rodata_memsz = 0;
};

// Finds the loaded module whose linker name ends with `name_suffix`
// (e.g. "libflutter.so") via dl_iterate_phdr. Works whether the library
// was extracted to its own file or is mapped directly from the APK.
// Returns false if not currently loaded.
bool find_module_by_suffix(const char *name_suffix, MappedModule &out);

// Parses the ELF program headers of `mod` to populate `out` with the
// virtual address and size of the .text and .rodata segments.
bool parse_elf_segments(const MappedModule &mod, ElfSegments &out);

// Look up an exported symbol in a mapped ELF module.
// Returns the absolute runtime address of the symbol, or 0 if not found.
uintptr_t find_symbol_address(const MappedModule &mod, const char *sym_name);
