// FlutterInspect native module -- by Akshay Khilari
//
// Generalised Dart function resolver using Capstone disassembly.
//
// Algorithm (arm64):
//   1. Build a set of target pages from all string match addresses.
//   2. Do a single forward scan of .text checking each word for the adrp
//      opcode bit pattern (register-agnostic).
//   3. For each candidate adrp, decode with Capstone to get the exact target
//      page. If it matches a string's page, check the next instruction for an
//      `add` with the correct low-12-bit offset.
//   4. Confirmed xref → backtrace up to 512 instructions looking for a
//      function prologue: `stp x29, x30, [sp, #-N]!` (canonical) or
//      `sub sp, sp, #N` followed by `str/stp` (leaf-less variant).
//   5. Record FunctionEntry with abs_addr = prologue address.
//
// Algorithm (x86_64):
//   1. Scan .text byte-by-byte for `lea reg, [rip+disp]` where the resolved
//      address equals any string match address.
//   2. Backtrace for `push rbp` or `push r15` (function prologue pattern).
//   3. Record FunctionEntry.
//
// Deduplication: if multiple strings resolve to the same prologue address,
// we keep all entries (the caller sees "this function references N strings").
#include "dart_resolver.h"

#include <capstone/capstone.h>

#include <algorithm>
#include <cctype>
#include <cstring>
#include <set>
#include <unordered_map>

#include "log.h"
#include "mem_scan.h"

namespace {

// ---------------------------------------------------------------------------
// Capstone handle management
// ---------------------------------------------------------------------------
struct CapstoneHandles {
    csh arm64 = 0;
    csh x86   = 0;
    bool arm64_ok = false;
    bool x86_ok   = false;
};

CapstoneHandles openHandles() {
    CapstoneHandles h;
    if (cs_open(CS_ARCH_ARM64, CS_MODE_ARM, &h.arm64) == CS_ERR_OK) {
        cs_option(h.arm64, CS_OPT_DETAIL, CS_OPT_ON);
        h.arm64_ok = true;
    }
    if (cs_open(CS_ARCH_X86, CS_MODE_64, &h.x86) == CS_ERR_OK) {
        cs_option(h.x86, CS_OPT_DETAIL, CS_OPT_ON);
        h.x86_ok = true;
    }
    return h;
}

void closeHandles(CapstoneHandles &h) {
    if (h.arm64_ok) cs_close(&h.arm64);
    if (h.x86_ok)   cs_close(&h.x86);
}

// Disassembles exactly one instruction at `addr` via guarded safe_read.
// `fn` receives the decoded cs_insn and its return value is this function's.
template <typename Fn>
bool disasmOne(csh handle, uintptr_t addr, Fn &&fn) {
    uint8_t buf[16] = {0};
    if (!safe_read(buf, reinterpret_cast<const void *>(addr), sizeof(buf))) return false;
    cs_insn *insn = nullptr;
    size_t count = cs_disasm(handle, buf, sizeof(buf), addr, 1, &insn);
    if (count == 0) return false;
    bool ok = fn(insn[0]);
    cs_free(insn, count);
    return ok;
}

// ---------------------------------------------------------------------------
// arm64 implementation
// ---------------------------------------------------------------------------
#if defined(__aarch64__)

// Backtrace from `xrefAddr` backwards through .text looking for the enclosing
// function's prologue or the instruction immediately following the previous function's ret.
uintptr_t findPrologueArm64(csh handle, uintptr_t xrefAddr, uintptr_t textBase) {
    constexpr int kMaxSteps = 512;
    for (int step = 1; step < kMaxSteps; ++step) {
        uintptr_t addr = xrefAddr - static_cast<uintptr_t>(step) * 4;
        if (addr < textBase) break;

        bool foundPrologue = false;
        bool isPreviousRet = false;

        disasmOne(handle, addr, [&](cs_insn &insn) -> bool {
            if (!insn.detail) return false;

            // 1. Previous function return: the current function begins at addr + 4
            if (strcmp(insn.mnemonic, "ret") == 0) {
                isPreviousRet = true;
                return true;
            }

            // 2. Canonical arm64 frame setup: stp x29, x30, [sp, #-N]!
            if (strcmp(insn.mnemonic, "stp") == 0) {
                auto &ops = insn.detail->arm64;
                if (ops.op_count >= 2 &&
                    ops.operands[0].type == ARM64_OP_REG &&
                    (ops.operands[0].reg == ARM64_REG_X29 || ops.operands[0].reg == ARM64_REG_FP) &&
                    ops.operands[1].type == ARM64_OP_REG &&
                    (ops.operands[1].reg == ARM64_REG_X30 || ops.operands[1].reg == ARM64_REG_LR)) {
                    foundPrologue = true;
                    return true;
                }
            }

            // 3. Stack adjustment: sub sp, sp, #N
            if (strcmp(insn.mnemonic, "sub") == 0) {
                auto &ops = insn.detail->arm64;
                if (ops.op_count >= 2 &&
                    ops.operands[0].type == ARM64_OP_REG && ops.operands[0].reg == ARM64_REG_SP &&
                    ops.operands[1].type == ARM64_OP_REG && ops.operands[1].reg == ARM64_REG_SP) {
                    foundPrologue = true;
                    return true;
                }
            }

            // 4. Single LR save: str x30, [sp, #-N]!
            if (strcmp(insn.mnemonic, "str") == 0) {
                auto &ops = insn.detail->arm64;
                if (ops.op_count >= 2 &&
                    ops.operands[0].type == ARM64_OP_REG &&
                    (ops.operands[0].reg == ARM64_REG_X30 || ops.operands[0].reg == ARM64_REG_LR)) {
                    foundPrologue = true;
                    return true;
                }
            }

            // 5. Pointer authentication: paciasp / pacibsp
            if (strncmp(insn.mnemonic, "paci", 4) == 0) {
                foundPrologue = true;
                return true;
            }

            return false;
        });

        if (isPreviousRet) return addr + 4;
        if (foundPrologue) return addr;
    }
    // Fallback: return the xref address itself if no prologue detected
    return xrefAddr;
}

// Scan .text for instructions referencing any page in `targetPages`.
// Returns map: string_address → list of xref addresses in .text.
std::unordered_map<uintptr_t, std::vector<uintptr_t>>
findXrefsArm64(csh handle, uintptr_t textBase, size_t textSize,
               const std::set<uintptr_t> &targetPages,
               const std::unordered_map<uintptr_t, std::vector<uintptr_t>> &pageToStrings) {
    std::unordered_map<uintptr_t, std::vector<uintptr_t>> xrefs;

    for (uintptr_t off = 0; off + 4 <= textSize; off += 4) {
        uintptr_t addr = textBase + off;
        uint32_t word = 0;
        if (!safe_read(&word, reinterpret_cast<const void *>(addr), sizeof(word))) continue;

        // Cheap pre-filter: ADRP opcode bits (register-agnostic)
        if ((word & 0x9F000000) != 0x90000000) continue;

        // Decode adrp with Capstone to get the exact target page
        int64_t adrpPage = 0;
        int adrpReg = -1;
        bool gotAdrp = disasmOne(handle, addr, [&](cs_insn &insn) -> bool {
            if (strcmp(insn.mnemonic, "adrp") != 0 || !insn.detail) return false;
            auto &ops = insn.detail->arm64;
            for (int i = 0; i < ops.op_count; ++i) {
                if (ops.operands[i].type == ARM64_OP_REG && adrpReg == -1)
                    adrpReg = ops.operands[i].reg;
                if (ops.operands[i].type == ARM64_OP_IMM)
                    adrpPage = ops.operands[i].imm;
            }
            return adrpReg != -1 && adrpPage != 0;
        });
        if (!gotAdrp) continue;

        uintptr_t page = static_cast<uintptr_t>(adrpPage);
        if (targetPages.find(page) == targetPages.end()) continue;

        auto it = pageToStrings.find(page);
        if (it == pageToStrings.end()) continue;

        // Check instructions following adrp (+4 and +8) for an add or ldr offset
        int64_t immOffset = -1;
        auto tryOffset = [&](uintptr_t nextAddr) -> bool {
            return disasmOne(handle, nextAddr, [&](cs_insn &insn) -> bool {
                if (!insn.detail) return false;
                auto &ops = insn.detail->arm64;
                if (strcmp(insn.mnemonic, "add") == 0) {
                    for (int i = 0; i < ops.op_count; ++i) {
                        if (ops.operands[i].type == ARM64_OP_IMM) {
                            immOffset = ops.operands[i].imm;
                            return true;
                        }
                    }
                } else if (strcmp(insn.mnemonic, "ldr") == 0) {
                    for (int i = 0; i < ops.op_count; ++i) {
                        if (ops.operands[i].type == ARM64_OP_MEM) {
                            immOffset = ops.operands[i].mem.disp;
                            return true;
                        }
                    }
                }
                return false;
            });
        };

        bool gotOffset = tryOffset(addr + 4) || tryOffset(addr + 8);
        if (!gotOffset) continue;

        uintptr_t resolved = page + static_cast<uintptr_t>(immOffset & 0xFFF);

        // Check against all strings located on this 4KB page
        for (uintptr_t strAddr : it->second) {
            if (strAddr == resolved || (strAddr & 0xFFF) == static_cast<uintptr_t>(immOffset & 0xFFF)) {
                xrefs[strAddr].push_back(addr);
            }
        }
    }
    return xrefs;
}

std::vector<FunctionEntry> resolveArm64(csh handle, const MappedModule &mod,
                                         const ElfSegments &segs,
                                         const std::vector<StringMatch> &matches) {
    std::vector<FunctionEntry> results;

    uintptr_t textBase = mod.base + segs.text_vaddr;
    size_t    textSize = segs.text_memsz;

    // Build page→[string_addresses] multimap for fast lookup during the scan
    std::set<uintptr_t> targetPages;
    std::unordered_map<uintptr_t, std::vector<uintptr_t>> pageToStrings;
    for (const auto &m : matches) {
        uintptr_t page = m.address & ~static_cast<uintptr_t>(0xFFF);
        targetPages.insert(page);
        pageToStrings[page].push_back(m.address);
    }

    // Build a reverse map: string_address → StringMatch
    std::unordered_map<uintptr_t, const StringMatch *> addrToMatch;
    for (const auto &m : matches) addrToMatch[m.address] = &m;

    fi_log_info("dart_resolver[arm64]: scanning .text [0x%zx, 0x%zx) for %zu string(s) across %zu pages",
                textBase, textBase + textSize, matches.size(), targetPages.size());

    auto xrefs = findXrefsArm64(handle, textBase, textSize, targetPages, pageToStrings);

    for (const auto &[strAddr, xrefList] : xrefs) {
        const StringMatch *m = addrToMatch.count(strAddr) ? addrToMatch[strAddr] : nullptr;
        std::string name = m ? m->value : "(unknown)";

        // Deduplicate: find unique prologue addresses
        std::set<uintptr_t> seenPrologues;
        for (uintptr_t xref : xrefList) {
            uintptr_t prologue = findPrologueArm64(handle, xref, textBase);
            if (!seenPrologues.insert(prologue).second) continue;

            FunctionEntry e;
            e.name             = name;
            e.abs_addr         = prologue;
            e.offset_from_base = prologue - mod.base;
            e.xref_addr        = xref;
            e.lib_name         = mod.path.substr(mod.path.rfind('/') + 1);
            results.push_back(e);

            fi_log_info("dart_resolver: '%s' → 0x%zx (offset 0x%zx) xref@0x%zx",
                        name.c_str(), prologue, e.offset_from_base, xref);
        }
    }
    return results;
}

// Enumerate all Dart AOT function entry points in .text.
// Across all Flutter versions on ARM64, the Dart VM compiler generates frames using:
//   stp x29, x30, [x15, #-16]!  (0xa9bf79fd)
//   mov x29, x15                (0xaa0f03fd)
// where x15 is the dedicated Dart stack pointer (SPREG / R15).
// Leaf/stub functions may also use:
//   stp x29, x30, [sp, #-16]!   (0xa9bf7bfd)
//   mov x29, sp                 (0x910003fd)
std::vector<FunctionEntry> enumerateDartArm64(const MappedModule &mod, const ElfSegments &segs) {
    std::vector<FunctionEntry> results;
    uintptr_t textBase = mod.base + segs.text_vaddr;
    size_t    textSize = segs.text_memsz;
    std::string libName = mod.path.substr(mod.path.rfind('/') + 1);

    fi_log_info("dart_resolver[arm64]: enumerating Dart functions in .text [0x%zx, 0x%zx) (%zu bytes)",
                textBase, textBase + textSize, textSize);

    size_t count = 0;
    for (uintptr_t off = 0; off + 8 <= textSize; off += 4) {
        uintptr_t addr = textBase + off;
        uint32_t w0 = 0, w1 = 0;
        if (!safe_read(&w0, reinterpret_cast<const void *>(addr), sizeof(w0))) continue;
        if (!safe_read(&w1, reinterpret_cast<const void *>(addr + 4), sizeof(w1))) continue;

        bool isDartPrologue = false;
        // 1. Canonical Dart ARM64 prologue:
        //    stp x29, x30, [x15, #-16]! (0xa9bf79fd)
        //    mov x29, x15               (0xaa0f03fd)
        if (w0 == 0xa9bf79fd && w1 == 0xaa0f03fd) {
            isDartPrologue = true;
        }
        // 2. Standard ARM64 frame prologue (C stubs / leaf helpers):
        //    stp x29, x30, [sp, #-16]!  (0xa9bf7bfd)
        //    mov x29, sp                (0x910003fd)
        else if (w0 == 0xa9bf7bfd && w1 == 0x910003fd) {
            isDartPrologue = true;
        }
        // 3. General stp x29, x30, [x15, #-imm]! followed by mov x29, x15
        else if ((w0 & 0xffe07fff) == 0xa98079fd && w1 == 0xaa0f03fd) {
            isDartPrologue = true;
        }

        if (isDartPrologue) {
            uintptr_t absAddr = addr;
            uintptr_t relOffset = absAddr - mod.base;

            char nameBuf[64];
            snprintf(nameBuf, sizeof(nameBuf), "dart_func_%05zu_0x%zx", ++count, relOffset);

            FunctionEntry e;
            e.name = nameBuf;
            e.abs_addr = absAddr;
            e.offset_from_base = relOffset;
            e.xref_addr = absAddr;
            e.lib_name = libName;
            results.push_back(e);
        }
    }

    fi_log_info("dart_resolver[arm64]: discovered %zu Dart functions in %s",
                results.size(), libName.c_str());
    return results;
}

// ---------------------------------------------------------------------------
// x86_64 implementation
// ---------------------------------------------------------------------------
#elif defined(__x86_64__)

// On x86_64 we scan byte-by-byte (variable-length instructions) looking for
//   lea  reg, [rip + disp]
// where resolved_addr = (insn_addr + insn_size) + disp == string_address.
uintptr_t findPrologueX64(csh handle, uintptr_t xrefAddr, uintptr_t textBase) {
    constexpr int kMaxBytes = 512 * 15; // ~512 instructions, avg 15 bytes each
    for (int back = 1; back < kMaxBytes; ++back) {
        if (xrefAddr < static_cast<uintptr_t>(back)) break;
        uintptr_t addr = xrefAddr - static_cast<uintptr_t>(back);
        if (addr < textBase) break;

        bool found = disasmOne(handle, addr, [](cs_insn &insn) -> bool {
            // push rbp — the most common x86_64 prologue
            if (strcmp(insn.mnemonic, "push") == 0 && insn.detail) {
                auto &ops = insn.detail->x86;
                if (ops.op_count >= 1 &&
                    ops.operands[0].type == X86_OP_REG &&
                    ops.operands[0].reg == X86_REG_RBP)
                    return true;
            }
            // push r15 — seen in Flutter engine builds
            if (strcmp(insn.mnemonic, "push") == 0 && insn.detail) {
                auto &ops = insn.detail->x86;
                if (ops.op_count >= 1 &&
                    ops.operands[0].type == X86_OP_REG &&
                    ops.operands[0].reg == X86_REG_R15)
                    return true;
            }
            return false;
        });
        if (found) return addr;
    }
    return xrefAddr; // fallback
}

std::vector<FunctionEntry> resolveX64(csh handle, const MappedModule &mod,
                                       const ElfSegments &segs,
                                       const std::vector<StringMatch> &matches) {
    std::vector<FunctionEntry> results;

    uintptr_t textBase = mod.base + segs.text_vaddr;
    size_t    textSize = segs.text_memsz;

    // Build string address set for fast lookup
    std::set<uintptr_t> stringAddrs;
    std::unordered_map<uintptr_t, const StringMatch *> addrToMatch;
    for (const auto &m : matches) {
        stringAddrs.insert(m.address);
        addrToMatch[m.address] = &m;
    }

    fi_log_info("dart_resolver[x64]: scanning .text [0x%zx, 0x%zx) for %zu string(s)",
                textBase, textBase + textSize, matches.size());

    std::set<uintptr_t> seenPrologues;

    // Step one byte at a time (x86_64 is variable-width)
    for (uintptr_t off = 0; off < textSize; ++off) {
        uintptr_t addr = textBase + off;

        uintptr_t resolvedAddr = 0;
        bool isLea = disasmOne(handle, addr, [&](cs_insn &insn) -> bool {
            if (strcmp(insn.mnemonic, "lea") != 0 || !insn.detail) return false;
            auto &ops = insn.detail->x86;
            for (int i = 0; i < ops.op_count; ++i) {
                if (ops.operands[i].type == X86_OP_MEM &&
                    ops.operands[i].mem.base == X86_REG_RIP) {
                    // resolved = next_insn_addr + displacement
                    resolvedAddr = (insn.address + insn.size) +
                                   static_cast<uintptr_t>(ops.operands[i].mem.disp);
                    return true;
                }
            }
            return false;
        });

        if (!isLea || stringAddrs.find(resolvedAddr) == stringAddrs.end()) continue;

        const StringMatch *m = addrToMatch[resolvedAddr];
        std::string name = m ? m->value : "(unknown)";

        uintptr_t prologue = findPrologueX64(handle, addr, textBase);
        if (!seenPrologues.insert(prologue).second) continue;

        FunctionEntry e;
        e.name             = name;
        e.abs_addr         = prologue;
        e.offset_from_base = prologue - mod.base;
        e.xref_addr        = addr;
        e.lib_name         = mod.path.substr(mod.path.rfind('/') + 1);
        results.push_back(e);

        fi_log_info("dart_resolver: '%s' → 0x%zx (offset 0x%zx) xref@0x%zx",
                    name.c_str(), prologue, e.offset_from_base, addr);
    }
    return results;
}

std::vector<FunctionEntry> enumerateDartX64(const MappedModule &mod, const ElfSegments &segs) {
    std::vector<FunctionEntry> results;
    uintptr_t textBase = mod.base + segs.text_vaddr;
    size_t    textSize = segs.text_memsz;
    std::string libName = mod.path.substr(mod.path.rfind('/') + 1);

    fi_log_info("dart_resolver[x64]: enumerating Dart functions in .text [0x%zx, 0x%zx) (%zu bytes)",
                textBase, textBase + textSize, textSize);

    size_t count = 0;
    for (uintptr_t off = 0; off + 4 <= textSize; ++off) {
        uintptr_t addr = textBase + off;
        uint8_t buf[4] = {0};
        if (!safe_read(buf, reinterpret_cast<const void *>(addr), sizeof(buf))) continue;

        // push rbp; mov rbp, rsp (55 48 89 e5)
        if (buf[0] == 0x55 && buf[1] == 0x48 && buf[2] == 0x89 && buf[3] == 0xe5) {
            uintptr_t absAddr = addr;
            uintptr_t relOffset = absAddr - mod.base;

            char nameBuf[64];
            snprintf(nameBuf, sizeof(nameBuf), "dart_func_%05zu_0x%zx", ++count, relOffset);

            FunctionEntry e;
            e.name = nameBuf;
            e.abs_addr = absAddr;
            e.offset_from_base = relOffset;
            e.xref_addr = absAddr;
            e.lib_name = libName;
            results.push_back(e);
        }
    }

    fi_log_info("dart_resolver[x64]: discovered %zu Dart functions in %s",
                results.size(), libName.c_str());
    return results;
}
#endif // arch

} // namespace

std::vector<FunctionEntry> enumerate_dart_functions(
    const MappedModule &mod,
    const ElfSegments  &segs) {
#if defined(__aarch64__)
    return enumerateDartArm64(mod, segs);
#elif defined(__x86_64__)
    return enumerateDartX64(mod, segs);
#else
    fi_log_error("dart_resolver: unsupported architecture");
    return {};
#endif
}

namespace {

std::string extractDartIdentifier(uintptr_t isoDataAddr, size_t isoDataSize, size_t fnRef, const std::string &keyword) {
    if (fnRef >= isoDataSize) return "(unknown)";

    size_t start = fnRef > 64 ? fnRef - 64 : 0;
    size_t end = std::min(isoDataSize, fnRef + 192);
    size_t len = end - start;
    std::vector<uint8_t> buf(len);
    if (!safe_read(buf.data(), reinterpret_cast<const void *>(isoDataAddr + start), len)) {
        return "(unreadable)";
    }

    std::string chunk(reinterpret_cast<char *>(buf.data()), len);

    auto toLower = [](const std::string &s) {
        std::string res = s;
        for (char &c : res) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));
        return res;
    };

    auto stripUnderscore = [](const std::string &s) {
        std::string res;
        for (char c : s) if (c != '_') res.push_back(c);
        return res;
    };

    std::string chunkLower = toLower(chunk);
    std::string kwLower = toLower(keyword);

    // 1. Direct match with keyword
    size_t kwPos = chunkLower.find(kwLower);
    size_t matchLen = keyword.length();

    // 2. If not found, try match without underscores (e.g. password_generator -> PasswordGenerator)
    if (kwPos == std::string::npos && !keyword.empty()) {
        std::string kwClean = stripUnderscore(kwLower);
        if (!kwClean.empty()) {
            for (size_t i = 0; i < chunkLower.size(); ++i) {
                size_t c_idx = 0;
                size_t j = i;
                while (j < chunkLower.size() && c_idx < kwClean.size()) {
                    if (chunkLower[j] == '_') {
                        j++;
                        continue;
                    }
                    if (chunkLower[j] == kwClean[c_idx]) {
                        j++;
                        c_idx++;
                    } else {
                        break;
                    }
                }
                if (c_idx == kwClean.size()) {
                    kwPos = i;
                    matchLen = j - i;
                    break;
                }
            }
        }
    }

    if (kwPos != std::string::npos) {
        size_t idStart = kwPos;
        while (idStart > 0) {
            char c = chunk[idStart - 1];
            if (isalnum(static_cast<unsigned char>(c)) || c == '_' || c == '@' || c == '.' || c == ':') {
                idStart--;
            } else {
                break;
            }
        }
        size_t idEnd = kwPos + matchLen;
        while (idEnd < chunk.size()) {
            char c = chunk[idEnd];
            if (isalnum(static_cast<unsigned char>(c)) || c == '_' || c == '@' || c == '.' || c == ':') {
                idEnd++;
            } else {
                break;
            }
        }
        if (idEnd > idStart) {
            return chunk.substr(idStart, idEnd - idStart);
        }
    }

    // 3. Fallback: find any identifier around fnRef
    for (size_t i = 0; i < chunk.size(); ++i) {
        char c = chunk[i];
        if (isalpha(static_cast<unsigned char>(c)) || c == '_') {
            size_t j = i + 1;
            while (j < chunk.size()) {
                char d = chunk[j];
                if (isalnum(static_cast<unsigned char>(d)) || d == '_' || d == '@' || d == '.' || d == ':') j++;
                else break;
            }
            if (j - i >= 4) {
                return chunk.substr(i, j - i);
            }
            i = j;
        }
    }

    return "(unknown)";
}

} // namespace

std::vector<FunctionEntry> resolve_dart_snapshot_functions(
    const MappedModule             &mod,
    const ElfSegments              &segs,
    const std::vector<std::string> &keywords) {

    std::vector<FunctionEntry> results;

    uintptr_t isoDataAddr = find_symbol_address(mod, "_kDartIsolateSnapshotData");
    uintptr_t isoInsnAddr = find_symbol_address(mod, "_kDartIsolateSnapshotInstructions");

    if (isoDataAddr == 0) {
        isoDataAddr = mod.base + segs.rodata_vaddr;
        fi_log_warn("dart_resolver: _kDartIsolateSnapshotData symbol not found, using .rodata @ 0x%zx", isoDataAddr);
    }
    if (isoInsnAddr == 0) {
        isoInsnAddr = mod.base + segs.text_vaddr;
        fi_log_warn("dart_resolver: _kDartIsolateSnapshotInstructions symbol not found, using .text @ 0x%zx", isoInsnAddr);
    }

    size_t isoDataSize = segs.rodata_memsz;
    if (isoDataSize == 0 || isoDataSize < 0x200000) {
        isoDataSize = segs.text_memsz > 0 ? segs.text_memsz : 0x2000000;
    }

    fi_log_info("dart_resolver: analyzing Dart AOT snapshot (data @ 0x%zx, insns @ 0x%zx, dataSize=0x%zx)",
                isoDataAddr, isoInsnAddr, isoDataSize);

    // Search for the true Dart Code Object Table:
    // A contiguous sequence of (insn_offset, fn_ref) pairs where insn_offset is monotonically increasing.
    // We scan the entire snapshot data and choose the table candidate with the MAXIMUM number of consecutive entries.
    intptr_t bestTableOff = -1;
    size_t   maxConsecutive = 0;

    for (size_t off = 0; off + 800 <= isoDataSize; off += 8) {
        uint32_t ioff = 0, fref = 0;
        if (!safe_read(&ioff, reinterpret_cast<const void *>(isoDataAddr + off), sizeof(ioff))) continue;
        if (!safe_read(&fref, reinterpret_cast<const void *>(isoDataAddr + off + 4), sizeof(fref))) continue;

        if (ioff > 0 && ioff < 0x4000 && fref > 0 && fref < isoDataSize) {
            uint32_t prev = ioff;
            size_t count = 1;
            for (size_t k = 1; off + (k + 1) * 8 <= isoDataSize; ++k) {
                uint32_t c_ioff = 0, c_fref = 0;
                if (!safe_read(&c_ioff, reinterpret_cast<const void *>(isoDataAddr + off + k * 8), sizeof(c_ioff)) ||
                    !safe_read(&c_fref, reinterpret_cast<const void *>(isoDataAddr + off + k * 8 + 4), sizeof(c_fref))) {
                    break;
                }
                if (c_ioff < prev || c_ioff > 0x4000000 || c_fref >= isoDataSize || c_fref == 0) {
                    break;
                }
                prev = c_ioff;
                count++;
            }
            if (count > maxConsecutive) {
                maxConsecutive = count;
                bestTableOff = static_cast<intptr_t>(off);
            }
            if (count > 1) {
                off += (count - 1) * 8;
            }
        }
    }

    intptr_t tableOff = (maxConsecutive >= 32) ? bestTableOff : -1;

    if (tableOff < 0) {
        fi_log_warn("dart_resolver: Code Object Table not found in snapshot data — falling back to prologue scan");
        return enumerate_dart_functions(mod, segs);
    }

    fi_log_info("dart_resolver: found Dart Code Object Table at data+0x%zx (%zu entries)",
                static_cast<size_t>(tableOff), maxConsecutive);

    std::string libName = mod.path.substr(mod.path.rfind('/') + 1);
    std::set<uintptr_t> seenAddrs;

    auto toLower = [](const std::string &s) {
        std::string res = s;
        for (char &c : res) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));
        return res;
    };

    auto stripUnderscore = [](const std::string &s) {
        std::string res;
        for (char c : s) if (c != '_') res.push_back(c);
        return res;
    };

    struct PreparedKeyword {
        std::string orig;
        std::string lower;
        std::string clean; // lowercase without underscores
    };

    std::vector<PreparedKeyword> prepKws;
    for (const auto &kw : keywords) {
        if (kw.empty()) continue;
        PreparedKeyword pk;
        pk.orig = kw;
        pk.lower = toLower(kw);
        pk.clean = stripUnderscore(pk.lower);
        prepKws.push_back(pk);
    }

    size_t scannedEntries = 0;
    std::unordered_map<std::string, int> nameOccurrences;

    for (size_t off = static_cast<size_t>(tableOff); off + 8 <= isoDataSize; off += 8) {
        uint32_t insn_off = 0, fn_ref = 0;
        if (!safe_read(&insn_off, reinterpret_cast<const void *>(isoDataAddr + off), sizeof(insn_off)) ||
            !safe_read(&fn_ref, reinterpret_cast<const void *>(isoDataAddr + off + 4), sizeof(fn_ref))) {
            break;
        }

        if (insn_off > 0x4000000 || fn_ref >= isoDataSize || (scannedEntries > 0 && insn_off == 0)) {
            break;
        }
        scannedEntries++;

        size_t chunkStart = fn_ref > 64 ? fn_ref - 64 : 0;
        size_t chunkLen = std::min(isoDataSize - chunkStart, static_cast<size_t>(256));
        std::vector<uint8_t> chunkBuf(chunkLen);
        if (!safe_read(chunkBuf.data(), reinterpret_cast<const void *>(isoDataAddr + chunkStart), chunkLen)) {
            continue;
        }
        std::string chunk(reinterpret_cast<char *>(chunkBuf.data()), chunkLen);
        std::string chunkLower = toLower(chunk);
        std::string chunkClean = stripUnderscore(chunkLower);

        bool matched = false;
        std::string matchedKw;
        if (prepKws.empty()) {
            matched = true;
        } else {
            for (const auto &pkw : prepKws) {
                if (chunkLower.find(pkw.lower) != std::string::npos ||
                    (!pkw.clean.empty() && chunkClean.find(pkw.clean) != std::string::npos)) {
                    matched = true;
                    matchedKw = pkw.orig;
                    break;
                }
            }
        }

        if (matched) {
            uintptr_t absAddr = isoInsnAddr + insn_off;
            if (seenAddrs.insert(absAddr).second) {
                std::string fnName = extractDartIdentifier(isoDataAddr, isoDataSize, fn_ref, matchedKw);

                int count = ++nameOccurrences[fnName];
                std::string displayName = fnName;
                if (count > 1) {
                    displayName += " #" + std::to_string(count);
                }

                FunctionEntry e;
                e.name = displayName;
                e.abs_addr = absAddr;
                e.offset_from_base = absAddr - mod.base;
                e.xref_addr = absAddr;
                e.lib_name = libName;
                results.push_back(e);

                fi_log_info("dart_resolver: [%s] '%s' → abs=0x%zx (offset=0x%zx)",
                            matchedKw.empty() ? "match" : matchedKw.c_str(),
                            displayName.c_str(), e.abs_addr, e.offset_from_base);
            }
        }
    }

    fi_log_info("dart_resolver: scanned %zu Code Table entries, matched %zu functions",
                scannedEntries, results.size());

    if (results.empty()) {
        fi_log_warn("dart_resolver: 0 keyword matches in Code Table — falling back to prologue enumeration");
        return enumerate_dart_functions(mod, segs);
    }

    return results;
}

std::vector<FunctionEntry> resolve_functions_for_strings(
    const MappedModule             &mod,
    const ElfSegments              &segs,
    const std::vector<StringMatch> &matches) {

    bool isDartSnapshot = (mod.path.find("libapp.so") != std::string::npos);

    if (isDartSnapshot) {
        std::vector<std::string> keywords;
        for (const auto &m : matches) {
            if (!m.value.empty()) keywords.push_back(m.value);
        }
        return resolve_dart_snapshot_functions(mod, segs, keywords);
    }

    if (matches.empty()) return {};

    CapstoneHandles h = openHandles();
    std::vector<FunctionEntry> results;

#if defined(__aarch64__)
    if (!h.arm64_ok) {
        fi_log_error("dart_resolver: failed to open Capstone arm64 handle");
        closeHandles(h);
        return {};
    }
    results = resolveArm64(h.arm64, mod, segs, matches);
#elif defined(__x86_64__)
    if (!h.x86_ok) {
        fi_log_error("dart_resolver: failed to open Capstone x86_64 handle");
        closeHandles(h);
        return {};
    }
    results = resolveX64(h.x86, mod, segs, matches);
#else
    fi_log_error("dart_resolver: unsupported architecture");
#endif

    closeHandles(h);

    fi_log_info("dart_resolver: resolved %zu function entries total", results.size());
    return results;
}
