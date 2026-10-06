// FlutterInspect native module -- by Akshay Khilari
//
// .rodata segment string scanner.
//
// Dart AOT-compiled binaries embed function/class names as null-terminated
// ASCII strings in .rodata for stack traces, toString(), reflection etc.
// Scanning those literals gives us the "anchor" addresses that dart_resolver
// then traces back to actual function entry points via Capstone disassembly.
#include "rodata_scan.h"

#include <cctype>
#include <cstring>

#include "log.h"
#include "mem_scan.h"

namespace {

// Effective rodata range: if a distinct .rodata segment was found, use it;
// otherwise fall back to the whole .text segment (some aggressive linkers
// pack .rodata into the same PT_LOAD as .text).
static uintptr_t rodataBase(const MappedModule &mod, const ElfSegments &segs) {
    if (segs.rodata_memsz > 0) return mod.base + segs.rodata_vaddr;
    return mod.base + segs.text_vaddr; // fallback
}
static size_t rodataSize(const ElfSegments &segs) {
    return segs.rodata_memsz > 0 ? segs.rodata_memsz : segs.text_memsz;
}

// Returns true if `c` is a byte that can appear in a Dart identifier string.
// We allow printable ASCII plus the internal characters Dart uses:
//   ., :, @, _, -, <, >, [, ], (, ), #, /, &, ?, !
inline bool isDartStringChar(char c) {
    if (c >= 'a' && c <= 'z') return true;
    if (c >= 'A' && c <= 'Z') return true;
    if (c >= '0' && c <= '9') return true;
    switch (c) {
        case '.': case ':': case '@': case '_':
        case '-': case '<': case '>': case '[':
        case ']': case '(': case ')': case '#':
        case '/': case '&': case '?': case '!':
        case ' ': case ',': case '*': case '+':
        case '=': case '^': case '~':
            return true;
        default:
            return false;
    }
}

// Heuristic: does this string look like a Dart identifier or Dart-internal name?
// We want to catch:
//   ClassName.methodName           (dot-separated)
//   _PrivateClass.method           (leading underscore)
//   get:propertyName               (getter)
//   set:propertyName               (setter)
//   dyn:ClassName.method           (dynamic dispatch stubs)
//   ClassName@hashcode             (tear-off or closure)
//   Closure of ClassName.method    (closure descriptors)
// We want to REJECT:
//   URLs (http://, https://)
//   File paths (/system/lib, /data/app)
//   Raw hex strings
//   BoringSSL / OpenSSL error messages (long lowercase sentences)
//   Single-word all-lowercase tokens (too much noise)
bool looksLikeDartIdentifier(const char *str, size_t len) {
    if (len < 4 || len > 256) return false;

    // Quick reject: URLs and file paths
    if (len > 7 && (strncmp(str, "http://", 7) == 0 || strncmp(str, "https://", 8) == 0))
        return false;
    if (str[0] == '/' || str[0] == '\\') return false;

    // Quick reject: pure hex strings (0x...)
    if (len > 2 && str[0] == '0' && (str[1] == 'x' || str[1] == 'X')) return false;

    bool hasDot        = false;
    bool hasColon      = false;
    bool hasUpperCase  = false;
    bool hasLowerCase  = false;
    int  digitCount    = 0;

    for (size_t i = 0; i < len; ++i) {
        char c = str[i];
        if (!isDartStringChar(c)) return false;
        if (c == '.') hasDot = true;
        if (c == ':') hasColon = true;
        if (c >= 'A' && c <= 'Z') hasUpperCase = true;
        if (c >= 'a' && c <= 'z') hasLowerCase = true;
        if (c >= '0' && c <= '9') ++digitCount;
    }

    // Reject if all digits
    if (static_cast<size_t>(digitCount) == len) return false;

    // Dart getter/setter/dyn prefix
    if (hasColon && (strncmp(str, "get:", 4) == 0 ||
                     strncmp(str, "set:", 4) == 0 ||
                     strncmp(str, "dyn:", 4) == 0))
        return true;

    // ClassName.method (most common Dart pattern)
    if (hasDot && hasUpperCase) return true;

    // Private class: _PrivateClass.method
    if (str[0] == '_' && hasDot) return true;

    // Closure descriptor: "Closure of ClassName.method"
    if (len > 10 && strncmp(str, "Closure", 7) == 0 && hasDot) return true;

    // Dart internal: "<...>" patterns (type parameters, closures)
    if (str[0] == '<' && str[len - 1] == '>') return true;

    return false;
}

// Read a null-terminated string from memory using safe_read, one byte at a time.
// Returns false if any byte read fails or if the string is longer than maxLen.
bool readString(uintptr_t addr, char *buf, size_t maxLen, size_t &outLen) {
    outLen = 0;
    for (size_t i = 0; i < maxLen; ++i) {
        char c = 0;
        if (!safe_read(&c, reinterpret_cast<const void *>(addr + i), 1)) return false;
        if (c == '\0') { outLen = i; return true; }
        buf[i] = c;
    }
    return false; // exceeded maxLen without finding null terminator
}

} // namespace

std::vector<StringMatch> scan_rodata_for_keyword(const MappedModule &mod,
                                                  const ElfSegments  &segs,
                                                  const std::string  &keyword) {
    std::vector<StringMatch> results;
    if (keyword.empty()) return results;

    uintptr_t base = rodataBase(mod, segs);
    size_t    size = rodataSize(segs);

    fi_log_info("rodata_scan: keyword '%s' in [0x%zx, 0x%zx)", keyword.c_str(), base, base + size);

    const size_t klen = keyword.size();
    if (klen > 256) return results;

    char lowerKw[257] = {};
    for (size_t i = 0; i < klen; ++i) {
        lowerKw[i] = static_cast<char>(tolower(static_cast<unsigned char>(keyword[i])));
    }
    char firstLower = lowerKw[0];
    char firstUpper = static_cast<char>(toupper(static_cast<unsigned char>(keyword[0])));

    for (size_t off = 0; off + klen <= size; ++off) {
        // Fast pre-check: first byte must match case-insensitively
        uint8_t first = 0;
        if (!safe_read(&first, reinterpret_cast<const void *>(base + off), 1)) {
            off += 63; // skip a chunk on fault
            continue;
        }
        char fc = static_cast<char>(first);
        if (fc != firstLower && fc != firstUpper) continue;

        // Read klen bytes and compare case-insensitively
        char buf[257] = {};
        if (!safe_read(buf, reinterpret_cast<const void *>(base + off), klen)) continue;
        if (strncasecmp(buf, lowerKw, klen) != 0) continue;

        // Confirmed substring match — now read the full containing string
        // by walking back to the previous null byte to find the string start,
        // then reading forward to the next null byte.
        uintptr_t strStart = base + off;
        // Walk back up to 256 bytes to find start of the string
        for (size_t back = 1; back <= 256 && strStart > base; ++back) {
            char prev = 0;
            if (!safe_read(&prev, reinterpret_cast<const void *>(strStart - 1), 1)) break;
            if (prev == '\0') break;
            --strStart;
        }

        // Read the full string from strStart
        char strBuf[257] = {};
        size_t strLen = 0;
        if (!readString(strStart, strBuf, 256, strLen) || strLen == 0) {
            // Fallback: just record the keyword itself at the match offset
            results.push_back({keyword, base + off});
            continue;
        }

        results.push_back({std::string(strBuf, strLen), strStart});
        fi_log_info("rodata_scan: hit '%s' @ 0x%zx", strBuf, strStart);

        // Skip past this string to avoid duplicate sub-matches
        off = (strStart - base) + strLen;
    }

    fi_log_info("rodata_scan: keyword '%s' found %zu match(es)", keyword.c_str(), results.size());
    return results;
}

std::vector<StringMatch> scan_rodata_all_dart_strings(const MappedModule &mod,
                                                       const ElfSegments  &segs) {
    std::vector<StringMatch> results;

    uintptr_t base = rodataBase(mod, segs);
    size_t    size = rodataSize(segs);

    fi_log_info("rodata_scan: full Dart string enumeration in [0x%zx, 0x%zx) (%zu bytes)",
                base, base + size, size);

    size_t off = 0;
    char buf[257];

    while (off < size) {
        uintptr_t addr = base + off;
        size_t strLen = 0;
        memset(buf, 0, sizeof(buf));

        if (!readString(addr, buf, 256, strLen)) {
            ++off; // read fault — step over one byte
            continue;
        }

        if (strLen > 0 && looksLikeDartIdentifier(buf, strLen)) {
            results.push_back({std::string(buf, strLen), addr});
        }

        // Advance past this string (including the null terminator)
        off += strLen + 1;
    }

    fi_log_info("rodata_scan: full enumerate found %zu candidate Dart strings", results.size());
    return results;
}
