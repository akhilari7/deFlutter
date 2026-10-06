// FlutterInspect native module
// Forked from FlutterTap by Eduardo Lopes. FlutterInspect by Akshay Khilari.
//
// Port of flutter+burp.js parseElf() / module lookup (Android/ELF branch only).
#include "elf_utils.h"

#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <unistd.h>

#include <cstring>

#include "log.h"
#include "mem_scan.h"

namespace {

// Goes through safe_read rather than a bare memcpy: `addr` is derived from
// ELF header fields we did not write, so a corrupt ELF can point it outside
// the mapping. The SIGSEGV guard only diverts faults while safe_read has
// armed it, so a plain memcpy would take the host app down instead.
template <typename T>
bool readMem(uintptr_t addr, T &out) {
    return safe_read(&out, reinterpret_cast<void *>(addr), sizeof(T));
}

// Known program header types whitelist — an unrecognised p_type stops the
// walk, matching the original flutter+burp.js parseElf() behaviour.
bool isKnownPType(uint32_t p_type) {
    switch (p_type) {
        case PT_NULL:
        case PT_LOAD:
        case PT_DYNAMIC:
        case PT_INTERP:
        case PT_NOTE:
        case PT_SHLIB:
        case PT_PHDR:
        case PT_TLS:
        case PT_GNU_EH_FRAME:
        case PT_GNU_STACK:
        case PT_GNU_RELRO:
        case PT_GNU_PROPERTY:
            return true;
        default:
            return false;
    }
}

struct FindModuleCtx {
    const char *suffix;
    size_t suffixLen;
    bool found = false;
    uintptr_t base = 0;
    std::string path;
};

int findModuleCallback(struct dl_phdr_info *info, size_t, void *data) {
    auto *ctx = static_cast<FindModuleCtx *>(data);
    if (!info->dlpi_name) return 0;
    size_t nameLen = strlen(info->dlpi_name);
    if (nameLen < ctx->suffixLen) return 0;
    if (strcmp(info->dlpi_name + (nameLen - ctx->suffixLen), ctx->suffix) != 0) return 0;
    ctx->found = true;
    ctx->base = static_cast<uintptr_t>(info->dlpi_addr);
    ctx->path = info->dlpi_name;
    return 1; // non-zero stops dl_iterate_phdr
}

} // namespace

bool find_module_by_suffix(const char *name_suffix, MappedModule &out) {
    // dl_iterate_phdr (not /proc/self/maps) so this also works when the APK
    // was built with extractNativeLibs=false and the library is mapped directly
    // out of the APK zip — bionic still reports dlpi_addr/dlpi_name correctly.
    FindModuleCtx ctx;
    ctx.suffix    = name_suffix;
    ctx.suffixLen = strlen(name_suffix);
    dl_iterate_phdr(findModuleCallback, &ctx);
    if (!ctx.found) return false;
    out.base = ctx.base;
    out.path = ctx.path;
    return true;
}

bool parse_elf_segments(const MappedModule &mod, ElfSegments &out) {
    Elf64_Ehdr ehdr{};
    if (!readMem(mod.base, ehdr)) {
        fi_log_error("parse_elf_segments: failed to read ELF header at 0x%zx", mod.base);
        return false;
    }
    if (ehdr.e_ident[EI_MAG0] != ELFMAG0 || ehdr.e_ident[EI_MAG1] != ELFMAG1 ||
        ehdr.e_ident[EI_MAG2] != ELFMAG2 || ehdr.e_ident[EI_MAG3] != ELFMAG3) {
        fi_log_error("parse_elf_segments: bad ELF magic at 0x%zx", mod.base);
        return false;
    }

    uintptr_t phdrAddr = mod.base + ehdr.e_phoff;
    bool foundText = false, foundRodata = false;

    for (uint16_t i = 0; i < ehdr.e_phnum; ++i) {
        Elf64_Phdr phdr{};
        uintptr_t addr = phdrAddr + static_cast<uintptr_t>(i) * sizeof(Elf64_Phdr);
        if (!readMem(addr, phdr)) break;
        if (!isKnownPType(phdr.p_type)) break;
        if (phdr.p_type != PT_LOAD) continue;

        bool exec  = (phdr.p_flags & PF_X) != 0;
        bool write = (phdr.p_flags & PF_W) != 0;
        bool read  = (phdr.p_flags & PF_R) != 0;

        if (exec && read && !write && !foundText) {
            // Executable, readable, non-writable → .text segment
            out.text_vaddr = phdr.p_vaddr;
            out.text_memsz = phdr.p_memsz;
            foundText = true;
        } else if (!exec && read && !write && !foundRodata) {
            // Non-executable, readable, non-writable → .rodata segment
            out.rodata_vaddr = phdr.p_vaddr;
            out.rodata_memsz = phdr.p_memsz;
            foundRodata = true;
        }

        if (foundText && foundRodata) break;
    }

    if (!foundText) {
        fi_log_error("parse_elf_segments: no executable segment found in %s", mod.path.c_str());
        return false;
    }
    if (!foundRodata) {
        // Non-fatal — .rodata might be packed into the text segment in some builds.
        // rodata_scan will fall back to scanning the whole text segment.
        fi_log_warn("parse_elf_segments: no distinct .rodata segment in %s — will scan .text", mod.path.c_str());
    }
    return true;
}

uintptr_t find_symbol_address(const MappedModule &mod, const char *sym_name) {
    if (!sym_name || mod.base == 0) return 0;

    // 1. Try dlsym first
    void *h = dlopen(mod.path.c_str(), RTLD_NOLOAD | RTLD_NOW);
    if (!h) h = dlopen(nullptr, RTLD_NOW);
    if (h) {
        void *sym = dlsym(h, sym_name);
        if (h != RTLD_DEFAULT && h != nullptr) dlclose(h);
        if (sym) return reinterpret_cast<uintptr_t>(sym);
    }

    // 2. Parse ELF dynamic symbol table (.dynsym) directly from memory
    Elf64_Ehdr ehdr{};
    if (!readMem(mod.base, ehdr)) return 0;

    uintptr_t dynamicVaddr = 0;
    size_t    dynamicMemsz = 0;
    for (uint16_t i = 0; i < ehdr.e_phnum; ++i) {
        Elf64_Phdr phdr{};
        if (!readMem(mod.base + ehdr.e_phoff + i * sizeof(Elf64_Phdr), phdr)) continue;
        if (phdr.p_type == PT_DYNAMIC) {
            dynamicVaddr = phdr.p_vaddr;
            dynamicMemsz = phdr.p_memsz;
            break;
        }
    }
    if (dynamicVaddr == 0 || dynamicMemsz == 0) return 0;

    uintptr_t symtabVaddr = 0, strtabVaddr = 0, hashVaddr = 0;
    size_t numDyn = dynamicMemsz / sizeof(Elf64_Dyn);
    for (size_t i = 0; i < numDyn; ++i) {
        Elf64_Dyn dyn{};
        if (!readMem(mod.base + dynamicVaddr + i * sizeof(Elf64_Dyn), dyn)) continue;
        if (dyn.d_tag == DT_NULL) break;
        if (dyn.d_tag == DT_SYMTAB) symtabVaddr = dyn.d_un.d_ptr;
        else if (dyn.d_tag == DT_STRTAB) strtabVaddr = dyn.d_un.d_ptr;
        else if (dyn.d_tag == DT_HASH) hashVaddr = dyn.d_un.d_ptr;
    }

    if (symtabVaddr == 0 || strtabVaddr == 0) return 0;

    uintptr_t symtabAddr = (symtabVaddr >= mod.base) ? symtabVaddr : (mod.base + symtabVaddr);
    uintptr_t strtabAddr = (strtabVaddr >= mod.base) ? strtabVaddr : (mod.base + strtabVaddr);

    uint32_t nchain = 8192;
    if (hashVaddr != 0) {
        uintptr_t hashAddr = (hashVaddr >= mod.base) ? hashVaddr : (mod.base + hashVaddr);
        uint32_t hashHdr[2] = {0};
        if (readMem(hashAddr, hashHdr)) {
            nchain = hashHdr[1];
        }
    }

    for (uint32_t i = 0; i < nchain; ++i) {
        Elf64_Sym sym{};
        if (!readMem(symtabAddr + i * sizeof(Elf64_Sym), sym)) break;
        if (sym.st_name != 0) {
            char nameBuf[128] = {0};
            if (safe_read(nameBuf, reinterpret_cast<const void *>(strtabAddr + sym.st_name), sizeof(nameBuf) - 1)) {
                if (strcmp(nameBuf, sym_name) == 0) {
                    uintptr_t val = sym.st_value;
                    return (val >= mod.base) ? val : (mod.base + val);
                }
            }
        }
    }

    return 0;
}
