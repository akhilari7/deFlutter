// FlutterInspect native Dart AOT function tracer using Dobby
// Author: Akshay Khilari
#include "dart_tracer.h"

#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/mman.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

#include "dobby.h"
#include "log.h"
#include "mem_scan.h"

#define TRACER_TAG "FlutterInspect-Tracer"
#define LOG_LINE(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TRACER_TAG, fmt, ##__VA_ARGS__)

namespace {

uintptr_t g_lib_base = 0;
size_t    g_lib_size = 0;
uintptr_t g_heap_base = 0;
std::string g_process_name;
FILE     *g_trace_log_file = nullptr;
std::mutex g_tracer_mutex;

struct InstalledHook {
    HookTarget target;
    uintptr_t  runtime_addr = 0;
    bool       active = false;
};

std::vector<InstalledHook> g_installed_hooks;

void trace_output(const std::string &line) {
    LOG_LINE("%s", line.c_str());
    if (g_trace_log_file) {
        fprintf(g_trace_log_file, "%s\n", line.c_str());
        fflush(g_trace_log_file);
    }
}

// ---------------------------------------------------------------------------
// Dart Value & String Decoding Engine
// ---------------------------------------------------------------------------

uintptr_t decompress_pointer(uintptr_t val) {
    if ((val & 1) != 1) return val;
    if (val > 0x100000000ULL) {
        if (g_heap_base == 0) {
            g_heap_base = (val & 0xFFFFFFFF00000000ULL);
        }
        return val;
    }
    if (g_heap_base != 0) {
        return g_heap_base + val;
    }
    return val;
}

std::string try_decode_dart_string(uintptr_t val, int depth = 0) {
    if (depth > 2 || (val & 1) != 1) return "";
    uintptr_t decompress = decompress_pointer(val);
    uintptr_t untagged = decompress - 1;

    struct Candidate {
        uint32_t len;
        uint32_t data_off;
        bool is_utf16;
    };
    std::vector<Candidate> candidates;

    // Format A: length at offset +8
    uint32_t len_at_8 = 0;
    if (safe_read(&len_at_8, reinterpret_cast<const void *>(untagged + 8), 4)) {
        if ((len_at_8 & 1) == 0 && (len_at_8 >> 1) > 0 && (len_at_8 >> 1) <= 2048) {
            candidates.push_back({len_at_8 >> 1, 16, false});
            candidates.push_back({len_at_8 >> 1, 16, true});
        }
        if (len_at_8 > 0 && len_at_8 <= 2048) {
            candidates.push_back({len_at_8, 16, false});
        }
    }

    // Format B: length at offset +4
    uint32_t len_at_4 = 0;
    if (safe_read(&len_at_4, reinterpret_cast<const void *>(untagged + 4), 4)) {
        if ((len_at_4 & 1) == 0 && (len_at_4 >> 1) > 0 && (len_at_4 >> 1) <= 2048) {
            candidates.push_back({len_at_4 >> 1, 12, false});
            candidates.push_back({len_at_4 >> 1, 8, false});
        }
        if (len_at_4 > 0 && len_at_4 <= 2048) {
            candidates.push_back({len_at_4, 12, false});
            candidates.push_back({len_at_4, 8, false});
        }
    }

    for (const auto &c : candidates) {
        if (!c.is_utf16) {
            uint32_t read_len = std::min(c.len, 256U);
            std::vector<uint8_t> buf(read_len);
            if (safe_read(buf.data(), reinterpret_cast<const void *>(untagged + c.data_off), read_len)) {
                std::string s;
                bool ok = true;
                for (uint32_t i = 0; i < read_len; ++i) {
                    uint8_t b = buf[i];
                    if (b >= 32 && b <= 126) {
                        s.push_back(static_cast<char>(b));
                    } else if (b == '\n') {
                        s += "\\n";
                    } else if (b == '\r') {
                        s += "\\r";
                    } else if (b == '\t') {
                        s += "\\t";
                    } else {
                        ok = false;
                        break;
                    }
                }
                if (ok && !s.empty()) {
                    if (c.len > read_len) {
                        return "\"" + s + "\"... [len=" + std::to_string(c.len) + "]";
                    }
                    return "\"" + s + "\"";
                }
            }
        } else {
            uint32_t read_len = std::min(c.len, 128U);
            std::vector<uint16_t> buf(read_len);
            if (safe_read(buf.data(), reinterpret_cast<const void *>(untagged + c.data_off), read_len * 2)) {
                std::string s;
                bool ok = true;
                for (uint32_t i = 0; i < read_len; ++i) {
                    uint16_t code = buf[i];
                    if (code >= 32 && code <= 126) {
                        s.push_back(static_cast<char>(code));
                    } else if (code == '\n') {
                        s += "\\n";
                    } else if (code == '\r') {
                        s += "\\r";
                    } else if (code == '\t') {
                        s += "\\t";
                    } else {
                        ok = false;
                        break;
                    }
                }
                if (ok && !s.empty()) {
                    if (c.len > read_len) {
                        return "\"" + s + "\"... (utf16) [len=" + std::to_string(c.len) + "]";
                    }
                    return "\"" + s + "\" (utf16)";
                }
            }
        }
    }

    return "";
}

std::string try_decode_dart_buffer(uintptr_t val) {
    if (val == 0) return "";
    uintptr_t decompress = decompress_pointer(val);
    uintptr_t untagged = ((decompress & 1) == 1) ? (decompress - 1) : decompress;

    uint32_t cid = 0;
    uint32_t header = 0;
    if (safe_read(&header, reinterpret_cast<const void *>(untagged), 4)) {
        cid = (header >> 12) & 0xfffff;
    }
    char cid_prefix[32] = "";
    if (cid > 0) {
        snprintf(cid_prefix, sizeof(cid_prefix), "cid=0x%x, ", cid);
    }

    // 1. Length-prefixed TypedData / Uint8List
    for (uint32_t lOff : {20u, 16u, 12u, 8u, 4u}) {
        uint32_t rawLen = 0;
        if (safe_read(&rawLen, reinterpret_cast<const void *>(untagged + lOff), 4)) {
            uint32_t len = 0;
            if ((rawLen & 1) == 0 && (rawLen >> 1) >= 1 && (rawLen >> 1) <= 16384) {
                len = rawLen >> 1;
            } else if (rawLen >= 1 && rawLen <= 16384) {
                len = rawLen;
            }
            if (len > 0) {
                for (uint32_t dOff : {lOff + 4, 24u, 16u, 12u, 8u}) {
                    if (dOff <= lOff && dOff != 8u && dOff != 12u) continue;
                    uint8_t buf[32] = {0};
                    uint32_t readLen = (len < 32) ? len : 32;
                    if (safe_read(buf, reinterpret_cast<const void *>(untagged + dOff), readLen)) {
                        uint32_t nonZero = 0;
                        uint32_t printableCount = 0;
                        std::string asciiStr;
                        for (uint32_t j = 0; j < readLen; ++j) {
                            if (buf[j] != 0) nonZero++;
                            if (buf[j] >= 32 && buf[j] <= 126) {
                                printableCount++;
                                asciiStr += static_cast<char>(buf[j]);
                            } else if (buf[j] == '\n') {
                                asciiStr += "\\n";
                            } else if (buf[j] == '\r') {
                                asciiStr += "\\r";
                            } else if (buf[j] == '\t') {
                                asciiStr += "\\t";
                            }
                        }
                        if (nonZero >= 2 || (len == 1 && nonZero == 1)) {
                            // Check if mostly printable text
                            if (printableCount >= 4 && printableCount >= nonZero * 0.85) {
                                if (len > readLen) {
                                    return "Uint8List(" + std::string(cid_prefix) + "len=" + std::to_string(len) + ") -> \"" + asciiStr + "\"...";
                                }
                                return "Uint8List(" + std::string(cid_prefix) + "len=" + std::to_string(len) + ") -> \"" + asciiStr + "\"";
                            }

                            // Hex byte preview (up to 16 bytes)
                            char hexBuf[64] = {0};
                            size_t hexShow = (readLen < 16) ? readLen : 16;
                            int pos = 0;
                            for (size_t k = 0; k < hexShow; ++k) {
                                pos += snprintf(hexBuf + pos, sizeof(hexBuf) - pos, (k == 0 ? "%02x" : " %02x"), buf[k]);
                            }
                            std::string hexStr(hexBuf);
                            if (len > hexShow) {
                                hexStr += " ...";
                            }

                            std::string res = "Uint8List(" + std::string(cid_prefix) + "len=" + std::to_string(len) + ") -> [" + hexStr + "]";
                            if (printableCount >= 4) {
                                res += " (\"" + asciiStr + "\")";
                            }
                            return res;
                        }
                    }
                }
            }
        }
    }

    // 2. Fallback: 16-byte fixed buffer / IV block
    for (uint32_t dOff : {8u, 12u, 16u, 24u}) {
        uint8_t buf[16] = {0};
        if (safe_read(buf, reinterpret_cast<const void *>(untagged + dOff), 16)) {
            uint32_t nonZero = 0;
            for (uint32_t j = 0; j < 16; ++j) {
                if (buf[j] != 0) nonZero++;
            }
            if (nonZero >= 12) {
                char hexBuf[64] = {0};
                int pos = 0;
                for (size_t k = 0; k < 16; ++k) {
                    pos += snprintf(hexBuf + pos, sizeof(hexBuf) - pos, (k == 0 ? "%02x" : " %02x"), buf[k]);
                }
                return "Bytes(" + std::string(cid_prefix) + "len=16) -> [" + std::string(hexBuf) + "]";
            }
        }
    }
    return "";
}

struct DecodedValue {
    enum Type { NULL_VAL, SMI, STRING, INSTANCE, NOISE } type = NOISE;
    std::string summary;
    int64_t     smi_val = 0;
};

// Forward declaration
DecodedValue describe_dart_value(uintptr_t val, int depth, std::unordered_set<uintptr_t> &visited);

std::string try_decode_dart_list(uintptr_t val, uint32_t cid, int depth, std::unordered_set<uintptr_t> &visited) {
    if (depth > 2 || val == 0) return "";
    uintptr_t decompress = decompress_pointer(val);
    uintptr_t untagged = ((decompress & 1) == 1) ? (decompress - 1) : decompress;

    bool is_growable = (cid == 92);
    bool is_array = (cid == 90 || cid == 91);

    uint32_t len = 0;
    uintptr_t data_base = 0;

    if (is_growable) {
        uint32_t raw_len = 0;
        if (safe_read(&raw_len, reinterpret_cast<const void *>(untagged + 12), 4)) {
            len = (raw_len & 1) == 0 ? (raw_len >> 1) : raw_len;
        } else if (safe_read(&raw_len, reinterpret_cast<const void *>(untagged + 8), 4)) {
            len = (raw_len & 1) == 0 ? (raw_len >> 1) : raw_len;
        }

        if (len <= 500) {
            uint32_t backing_raw = 0;
            if (safe_read(&backing_raw, reinterpret_cast<const void *>(untagged + 16), 4) && backing_raw > 0x1000) {
                uintptr_t backing_ptr = (g_heap_base != 0) ? (g_heap_base + backing_raw) : backing_raw;
                uintptr_t backing_untagged = ((backing_ptr & 1) == 1) ? (backing_ptr - 1) : backing_ptr;
                data_base = backing_untagged + 16;
            }
        }
    } else if (is_array) {
        uint32_t raw_len = 0;
        if (safe_read(&raw_len, reinterpret_cast<const void *>(untagged + 12), 4)) {
            len = (raw_len & 1) == 0 ? (raw_len >> 1) : raw_len;
        } else if (safe_read(&raw_len, reinterpret_cast<const void *>(untagged + 8), 4)) {
            len = (raw_len & 1) == 0 ? (raw_len >> 1) : raw_len;
        }
        data_base = untagged + 16;
    }

    if (data_base == 0 || len > 500) return "";
    if (len == 0) return "List(len=0) []";

    std::string out = "List(len=" + std::to_string(len) + ") [";
    size_t show_count = std::min<size_t>(len, 4);
    for (size_t i = 0; i < show_count; ++i) {
        uint32_t elem_raw = 0;
        if (!safe_read(&elem_raw, reinterpret_cast<const void *>(data_base + i * 4), 4)) break;

        if (i > 0) out += ", ";

        if (elem_raw == 0) {
            out += "null";
        } else if ((elem_raw & 1) == 0) {
            int64_t num = static_cast<int64_t>(elem_raw) >> 1;
            out += std::to_string(num);
        } else {
            uintptr_t elem_ptr = (g_heap_base != 0) ? (g_heap_base + elem_raw) : elem_raw;
            if (visited.count(elem_ptr)) {
                out += "<cycle>";
            } else {
                auto dec = describe_dart_value(elem_ptr, depth + 1, visited);
                out += (dec.type != DecodedValue::NOISE ? dec.summary : "?");
            }
        }
    }

    if (len > show_count) {
        out += ", ...";
    }
    out += "]";
    return out;
}

std::string try_decode_dart_map(uintptr_t val, uint32_t cid, int depth, std::unordered_set<uintptr_t> &visited) {
    if (depth > 2 || val == 0) return "";
    uintptr_t decompress = decompress_pointer(val);
    uintptr_t untagged = ((decompress & 1) == 1) ? (decompress - 1) : decompress;

    if (cid != 86 && cid != 87) return "";

    uint32_t used_raw = 0;
    if (!safe_read(&used_raw, reinterpret_cast<const void *>(untagged + 20), 4)) return "";
    uint32_t used = (used_raw & 1) == 0 ? (used_raw >> 1) : used_raw;
    if (used == 0 || used > 400) {
        return "Map(len=0) {}";
    }

    uint32_t backing_raw = 0;
    if (!safe_read(&backing_raw, reinterpret_cast<const void *>(untagged + 16), 4) || backing_raw < 0x1000) {
        return "";
    }
    uintptr_t backing_ptr = (g_heap_base != 0) ? (g_heap_base + backing_raw) : backing_raw;
    uintptr_t backing_untagged = ((backing_ptr & 1) == 1) ? (backing_ptr - 1) : backing_ptr;

    std::string out = "Map(len=" + std::to_string(used / 2) + ") {";
    size_t pair_count = 0;
    size_t max_pairs = 3;

    for (uint32_t i = 0; i < used && pair_count < max_pairs; i += 2) {
        uint32_t k_raw = 0;
        uint32_t v_raw = 0;
        if (!safe_read(&k_raw, reinterpret_cast<const void *>(backing_untagged + 16 + i * 4), 4)) break;
        if (!safe_read(&v_raw, reinterpret_cast<const void *>(backing_untagged + 16 + (i + 1) * 4), 4)) break;

        if (k_raw == 0) continue;
        uintptr_t k_ptr = (g_heap_base != 0) ? (g_heap_base + k_raw) : k_raw;
        uintptr_t v_ptr = (g_heap_base != 0) ? (g_heap_base + v_raw) : v_raw;

        std::string k_str;
        if ((k_raw & 1) == 0) {
            k_str = std::to_string(static_cast<int64_t>(k_raw) >> 1);
        } else {
            k_str = try_decode_dart_string(k_ptr, depth + 1);
            if (k_str.empty()) continue;
        }

        std::string v_str;
        if ((v_raw & 1) == 0) {
            v_str = std::to_string(static_cast<int64_t>(v_raw) >> 1);
        } else {
            auto v_dec = describe_dart_value(v_ptr, depth + 1, visited);
            v_str = (v_dec.type != DecodedValue::NOISE ? v_dec.summary : "?");
        }

        if (pair_count > 0) out += ", ";
        out += k_str + ": " + v_str;
        pair_count++;
    }

    if ((used / 2) > pair_count) {
        out += ", ...";
    }
    out += "}";
    return out;
}

DecodedValue describe_dart_value(uintptr_t val, int depth, std::unordered_set<uintptr_t> &visited) {
    DecodedValue res;
    if (val == 0) {
        res.type = DecodedValue::NULL_VAL;
        res.summary = "null";
        return res;
    }

    // 1. Smi (bit 0 == 0)
    if ((val & 1) == 0) {
        int64_t num = static_cast<int64_t>(val) >> 1;
        if (num >= -10000000 && num <= 10000000) {
            res.type = DecodedValue::SMI;
            res.smi_val = num;
            res.summary = std::to_string(num) + " (int)";
            return res;
        }
        res.type = DecodedValue::NOISE;
        return res;
    }

    // Cycle detection for heap objects
    if (visited.count(val)) {
        res.type = DecodedValue::INSTANCE;
        res.summary = "<cycle>";
        return res;
    }
    visited.insert(val);

    // 2. Direct Dart String
    std::string direct_str = try_decode_dart_string(val, depth);
    if (!direct_str.empty()) {
        res.type = DecodedValue::STRING;
        res.summary = direct_str;
        visited.erase(val);
        return res;
    }

    // 3. Direct Dart Buffer / Uint8List
    std::string direct_buf = try_decode_dart_buffer(val);
    if (!direct_buf.empty()) {
        res.type = DecodedValue::STRING;
        res.summary = direct_buf;
        visited.erase(val);
        return res;
    }

    // 4. Dart HeapObject (bit 0 == 1)
    uintptr_t decompress = decompress_pointer(val);
    uintptr_t untagged = decompress - 1;
    uint32_t header = 0;
    if (!safe_read(&header, reinterpret_cast<const void *>(untagged), 4)) {
        res.type = DecodedValue::NOISE;
        visited.erase(val);
        return res;
    }

    uint32_t cid = (header >> 12) & 0xfffff;
    // Null singleton (cid == 1)
    if (cid == 1) {
        res.type = DecodedValue::NULL_VAL;
        res.summary = "null";
        visited.erase(val);
        return res;
    }

    // Bool (cid == 63)
    if (cid == 63) {
        uint8_t bool_val = 0;
        if (safe_read(&bool_val, reinterpret_cast<const void *>(untagged + 8), 1) ||
            safe_read(&bool_val, reinterpret_cast<const void *>(untagged + 4), 1)) {
            res.type = DecodedValue::STRING;
            res.summary = (bool_val != 0) ? "true" : "false";
            visited.erase(val);
            return res;
        }
    }

    // Mint / 64-bit Int (cid == 61)
    if (cid == 61) {
        int64_t mint_val = 0;
        if (safe_read(&mint_val, reinterpret_cast<const void *>(untagged + 8), 8)) {
            res.type = DecodedValue::SMI;
            res.smi_val = mint_val;
            res.summary = std::to_string(mint_val) + " (int64)";
            visited.erase(val);
            return res;
        }
    }

    // Double (cid == 62)
    if (cid == 62) {
        double d_val = 0.0;
        if (safe_read(&d_val, reinterpret_cast<const void *>(untagged + 8), 8)) {
            char d_buf[32];
            snprintf(d_buf, sizeof(d_buf), "%.4f (double)", d_val);
            res.type = DecodedValue::STRING;
            res.summary = d_buf;
            visited.erase(val);
            return res;
        }
    }

    // List / Array (cid == 90, 91, 92)
    if (cid == 90 || cid == 91 || cid == 92) {
        std::string list_str = try_decode_dart_list(val, cid, depth, visited);
        if (!list_str.empty()) {
            res.type = DecodedValue::STRING;
            res.summary = list_str;
            visited.erase(val);
            return res;
        }
    }

    // Map (cid == 86, 87)
    if (cid == 86 || cid == 87) {
        std::string map_str = try_decode_dart_map(val, cid, depth, visited);
        if (!map_str.empty()) {
            res.type = DecodedValue::STRING;
            res.summary = map_str;
            visited.erase(val);
            return res;
        }
    }

    // 5. Custom Instance (Scan member fields up to 48 bytes)
    if (depth < 2) {
        std::vector<std::string> found_items;

        // Compressed pointer fields scan (step 4, offset 4 to 48)
        for (uint32_t off = 4; off <= 48; off += 4) {
            uint32_t fld32 = 0;
            if (!safe_read(&fld32, reinterpret_cast<const void *>(untagged + off), 4)) break;
            if (fld32 == 0) continue;

            // Check if Smi
            if ((fld32 & 1) == 0) {
                int64_t num = static_cast<int64_t>(fld32) >> 1;
                if (num > 0 && num <= 1000000) {
                    char off_str[32];
                    snprintf(off_str, sizeof(off_str), "+0x%x: ", off);
                    found_items.push_back(std::string(off_str) + std::to_string(num));
                    if (found_items.size() >= 4) break;
                }
                continue;
            }

            // Pointer field
            if (fld32 > 0x1000 && (fld32 & 1) == 1) {
                uintptr_t fld_ptr = (g_heap_base != 0) ? (g_heap_base + fld32) : fld32;
                if (visited.count(fld_ptr)) continue;

                char off_str[32];
                snprintf(off_str, sizeof(off_str), "+0x%x: ", off);

                // String
                std::string fld_str = try_decode_dart_string(fld_ptr, depth + 1);
                if (!fld_str.empty()) {
                    found_items.push_back(std::string(off_str) + fld_str);
                    if (found_items.size() >= 4) break;
                    continue;
                }

                // Buffer
                std::string fld_buf = try_decode_dart_buffer(fld_ptr);
                if (!fld_buf.empty()) {
                    found_items.push_back(std::string(off_str) + fld_buf);
                    if (found_items.size() >= 4) break;
                    continue;
                }

                // Check sub-object CID (List or Map or Instance)
                uintptr_t sub_untagged = fld_ptr - 1;
                uint32_t sub_hdr = 0;
                if (safe_read(&sub_hdr, reinterpret_cast<const void *>(sub_untagged), 4)) {
                    uint32_t sub_cid = (sub_hdr >> 12) & 0xfffff;
                    if (sub_cid == 1) continue; // null
                    if (sub_cid == 90 || sub_cid == 91 || sub_cid == 92) {
                        std::string sl = try_decode_dart_list(fld_ptr, sub_cid, depth + 1, visited);
                        if (!sl.empty()) {
                            found_items.push_back(std::string(off_str) + sl);
                            if (found_items.size() >= 4) break;
                            continue;
                        }
                    } else if (sub_cid == 86 || sub_cid == 87) {
                        std::string sm = try_decode_dart_map(fld_ptr, sub_cid, depth + 1, visited);
                        if (!sm.empty()) {
                            found_items.push_back(std::string(off_str) + sm);
                            if (found_items.size() >= 4) break;
                            continue;
                        }
                    }
                }
            }
        }

        if (!found_items.empty()) {
            res.type = DecodedValue::INSTANCE;
            char cid_buf[32];
            snprintf(cid_buf, sizeof(cid_buf), "Instance(cid=0x%x) -> [", cid);
            std::string s = cid_buf;
            size_t max_show = std::min<size_t>(found_items.size(), 4);
            for (size_t i = 0; i < max_show; ++i) {
                s += found_items[i];
                if (i + 1 < max_show) s += ", ";
            }
            if (found_items.size() > max_show) {
                s += ", ...";
            }
            s += "]";
            res.summary = s;
            visited.erase(val);
            return res;
        }
    }

    char cid_buf[32];
    snprintf(cid_buf, sizeof(cid_buf), "Instance(cid=0x%x)", cid);
    res.type = DecodedValue::INSTANCE;
    res.summary = cid_buf;
    visited.erase(val);
    return res;
}

DecodedValue describe_dart_value(uintptr_t val, int depth = 0) {
    std::unordered_set<uintptr_t> visited;
    return describe_dart_value(val, depth, visited);
}

// ---------------------------------------------------------------------------
// Return Detour Machinery (ARM64)
// ---------------------------------------------------------------------------

#if defined(__arm64__) || defined(__aarch64__)

struct ReturnFrame {
    uintptr_t original_lr = 0;
    std::string name;
    std::chrono::steady_clock::time_point start_time;
};

pthread_key_t g_ret_stack_key;
pthread_once_t g_ret_stack_once = PTHREAD_ONCE_INIT;

void free_ret_stack(void *ptr) {
    delete static_cast<std::vector<ReturnFrame> *>(ptr);
}

void init_ret_stack_key() {
    pthread_key_create(&g_ret_stack_key, free_ret_stack);
}

std::vector<ReturnFrame> *get_ret_stack() {
    pthread_once(&g_ret_stack_once, init_ret_stack_key);
    auto *st = static_cast<std::vector<ReturnFrame> *>(pthread_getspecific(g_ret_stack_key));
    if (!st) {
        st = new std::vector<ReturnFrame>();
        pthread_setspecific(g_ret_stack_key, st);
    }
    return st;
}

extern "C" void dobby_return_trampoline();

extern "C" uintptr_t dobby_return_c_handler(uintptr_t x0_retval) {
    auto *st = get_ret_stack();
    if (!st || st->empty()) {
        return 0;
    }

    ReturnFrame frame = st->back();
    st->pop_back();

    auto elapsed_us = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - frame.start_time).count();
    double ms = static_cast<double>(elapsed_us) / 1000.0;

    auto ret_dec = describe_dart_value(x0_retval);
    std::string ret_summary = (ret_dec.type != DecodedValue::NOISE) ? ret_dec.summary : "null";

    char ret_line[256];
    if ((x0_retval & 1) == 1 && x0_retval > 0x1000) {
        uintptr_t decomp = decompress_pointer(x0_retval);
        snprintf(ret_line, sizeof(ret_line), "[✓] ↵ RETURN: %s (0x%zx) -> %s [%.2fms]",
                 frame.name.c_str(), decomp, ret_summary.c_str(), ms);
    } else {
        snprintf(ret_line, sizeof(ret_line), "[✓] ↵ RETURN: %s -> %s [%.2fms]",
                 frame.name.c_str(), ret_summary.c_str(), ms);
    }
    trace_output(ret_line);

    return frame.original_lr;
}

__asm__(
    ".global dobby_return_trampoline\n"
    "dobby_return_trampoline:\n"
    "    // 1. Allocate 768 bytes on stack (16-byte aligned)\n"
    "    sub sp, sp, #768\n"
    "    // 2. Save all GP registers x0-x30\n"
    "    stp x0, x1, [sp, #0]\n"
    "    stp x2, x3, [sp, #16]\n"
    "    stp x4, x5, [sp, #32]\n"
    "    stp x6, x7, [sp, #48]\n"
    "    stp x8, x9, [sp, #64]\n"
    "    stp x10, x11, [sp, #80]\n"
    "    stp x12, x13, [sp, #96]\n"
    "    stp x14, x15, [sp, #112]\n"
    "    stp x16, x17, [sp, #128]\n"
    "    stp x18, x19, [sp, #144]\n"
    "    stp x20, x21, [sp, #160]\n"
    "    stp x22, x23, [sp, #176]\n"
    "    stp x24, x25, [sp, #192]\n"
    "    stp x26, x27, [sp, #208]\n"
    "    stp x28, x29, [sp, #224]\n"
    "    str x30, [sp, #240]\n"
    "    // 3. Save all NEON/FP registers q0-q31 (offset 256..768)\n"
    "    stp q0, q1, [sp, #256]\n"
    "    stp q2, q3, [sp, #288]\n"
    "    stp q4, q5, [sp, #320]\n"
    "    stp q6, q7, [sp, #352]\n"
    "    stp q8, q9, [sp, #384]\n"
    "    stp q10, q11, [sp, #416]\n"
    "    stp q12, q13, [sp, #448]\n"
    "    stp q14, q15, [sp, #480]\n"
    "    stp q16, q17, [sp, #512]\n"
    "    stp q18, q19, [sp, #544]\n"
    "    stp q20, q21, [sp, #576]\n"
    "    stp q22, q23, [sp, #608]\n"
    "    stp q24, q25, [sp, #640]\n"
    "    stp q26, q27, [sp, #672]\n"
    "    stp q28, q29, [sp, #704]\n"
    "    stp q30, q31, [sp, #736]\n"
    "    // 4. Call C handler: x0 is the return value of the hooked function\n"
    "    bl dobby_return_c_handler\n"
    "    // Save original_lr returned in x0 into the saved x30 slot\n"
    "    str x0, [sp, #240]\n"
    "    // 5. Restore all NEON/FP registers q0-q31\n"
    "    ldp q0, q1, [sp, #256]\n"
    "    ldp q2, q3, [sp, #288]\n"
    "    ldp q4, q5, [sp, #320]\n"
    "    ldp q6, q7, [sp, #352]\n"
    "    ldp q8, q9, [sp, #384]\n"
    "    ldp q10, q11, [sp, #416]\n"
    "    ldp q12, q13, [sp, #448]\n"
    "    ldp q14, q15, [sp, #480]\n"
    "    ldp q16, q17, [sp, #512]\n"
    "    ldp q18, q19, [sp, #544]\n"
    "    ldp q20, q21, [sp, #576]\n"
    "    ldp q22, q23, [sp, #608]\n"
    "    ldp q24, q25, [sp, #640]\n"
    "    ldp q26, q27, [sp, #672]\n"
    "    ldp q28, q29, [sp, #704]\n"
    "    ldp q30, q31, [sp, #736]\n"
    "    // 6. Restore all GP registers x0-x29 (x0 return value preserved!)\n"
    "    ldp x0, x1, [sp, #0]\n"
    "    ldp x2, x3, [sp, #16]\n"
    "    ldp x4, x5, [sp, #32]\n"
    "    ldp x6, x7, [sp, #48]\n"
    "    ldp x8, x9, [sp, #64]\n"
    "    ldp x10, x11, [sp, #80]\n"
    "    ldp x12, x13, [sp, #96]\n"
    "    ldp x14, x15, [sp, #112]\n"
    "    ldp x16, x17, [sp, #128]\n"
    "    ldp x18, x19, [sp, #144]\n"
    "    ldp x20, x21, [sp, #160]\n"
    "    ldp x22, x23, [sp, #176]\n"
    "    ldp x24, x25, [sp, #192]\n"
    "    ldp x26, x27, [sp, #208]\n"
    "    ldp x28, x29, [sp, #224]\n"
    "    // 7. Load original_lr into x30\n"
    "    ldr x30, [sp, #240]\n"
    "    // 8. Deallocate stack and return cleanly to caller\n"
    "    add sp, sp, #768\n"
    "    ret\n"
);

#endif

// ---------------------------------------------------------------------------
// Dobby Entry Callback
// ---------------------------------------------------------------------------

void dart_entry_dbi_callback(RegisterContext *ctx, const HookEntryInfo *info) {
    if (!ctx || !info) return;
    uintptr_t target_addr = reinterpret_cast<uintptr_t>(info->target_address);
    std::string name = "unknown";
    uintptr_t target_offset = 0;
    bool trace_ret = true;
    std::vector<std::string> param_names;

    {
        std::lock_guard<std::mutex> lock(g_tracer_mutex);
        for (const auto &ih : g_installed_hooks) {
            if (ih.runtime_addr == target_addr) {
                name = ih.target.name;
                target_offset = ih.target.offset;
                trace_ret = ih.target.trace_return;
                param_names = ih.target.param_names;
                break;
            }
        }
    }

    char off_buf[32];
    snprintf(off_buf, sizeof(off_buf), "0x%zx", target_offset);
    std::string hit_msg = "[★] ⚡ HIT: " + name + " (" + off_buf + ")";
    trace_output(hit_msg);

#if defined(__arm64__) || defined(__aarch64__)
    // Extract argument values
    auto arg0 = describe_dart_value(ctx->general.regs.x0);
    auto arg1 = describe_dart_value(ctx->general.regs.x1);
    auto arg2 = describe_dart_value(ctx->general.regs.x2);
    auto arg3 = describe_dart_value(ctx->general.regs.x3);
    auto arg4 = describe_dart_value(ctx->general.regs.x4);

    auto format_label_with_ptr = [](const std::string &base_lbl, uintptr_t val) -> std::string {
        if ((val & 1) == 1 && val > 0x1000) {
            uintptr_t decomp = decompress_pointer(val);
            char buf[64];
            snprintf(buf, sizeof(buf), "%s (0x%zx)", base_lbl.c_str(), decomp);
            return buf;
        }
        return base_lbl;
    };

    std::vector<std::pair<std::string, DecodedValue>> valid_args;

    std::string lbl0 = "Arg0 (this/recv)";
    if (!param_names.empty() && !param_names[0].empty()) {
        lbl0 = "Arg0 (" + param_names[0] + ")";
    }
    if (arg0.type != DecodedValue::NOISE && arg0.type != DecodedValue::NULL_VAL) {
        valid_args.push_back({format_label_with_ptr(lbl0, ctx->general.regs.x0), arg0});
    }

    std::string lbl1 = "Arg1";
    if (param_names.size() > 1 && !param_names[1].empty()) {
        lbl1 = "Arg1 (" + param_names[1] + ")";
    }
    if (arg1.type != DecodedValue::NOISE && arg1.type != DecodedValue::NULL_VAL) {
        valid_args.push_back({format_label_with_ptr(lbl1, ctx->general.regs.x1), arg1});
    }

    std::string lbl2 = "Arg2";
    if (param_names.size() > 2 && !param_names[2].empty()) {
        lbl2 = "Arg2 (" + param_names[2] + ")";
    }
    if (arg2.type != DecodedValue::NOISE && arg2.type != DecodedValue::NULL_VAL) {
        valid_args.push_back({format_label_with_ptr(lbl2, ctx->general.regs.x2), arg2});
    }

    std::string lbl3 = "Arg3";
    if (param_names.size() > 3 && !param_names[3].empty()) {
        lbl3 = "Arg3 (" + param_names[3] + ")";
    }
    if (arg3.type != DecodedValue::NOISE && arg3.type != DecodedValue::NULL_VAL) {
        valid_args.push_back({format_label_with_ptr(lbl3, ctx->general.regs.x3), arg3});
    }

    std::string lbl4 = "Arg4";
    if (param_names.size() > 4 && !param_names[4].empty()) {
        lbl4 = "Arg4 (" + param_names[4] + ")";
    }
    if (arg4.type != DecodedValue::NOISE && arg4.type != DecodedValue::NULL_VAL) {
        valid_args.push_back({format_label_with_ptr(lbl4, ctx->general.regs.x4), arg4});
    }

    // 1. Inspect Dart VM Stack Register: x15 (SPREG)
    uintptr_t dart_sp = ctx->general.regs.x15;
    if (dart_sp > 0x1000) {
        for (int off = 0; off <= 0x40; off += 8) {
            uintptr_t slot_val = 0;
            if (safe_read(&slot_val, reinterpret_cast<const void *>(dart_sp + off), sizeof(uintptr_t))) {
                auto decoded = describe_dart_value(slot_val);
                if (decoded.type != DecodedValue::NOISE && decoded.type != DecodedValue::NULL_VAL) {
                    char label[32];
                    snprintf(label, sizeof(label), "[Stack+0x%x]", off);
                    valid_args.push_back({format_label_with_ptr(label, slot_val), decoded});
                }
            }
        }
    }

    // 2. Inspect Dart Context Register: x27 (CTXREG - Lexical scopes / async variables)
    uintptr_t dart_ctx = ctx->general.regs.x27;
    if (dart_ctx > 0x1000 && (dart_ctx & 1) == 1) {
        auto decoded = describe_dart_value(dart_ctx);
        if (decoded.type != DecodedValue::NOISE && decoded.type != DecodedValue::NULL_VAL) {
            valid_args.push_back({format_label_with_ptr("x27 (Context)", dart_ctx), decoded});
        }
    }

    // 3. Inspect caller stack frame slots off FP (x29)
    uintptr_t fp = static_cast<uintptr_t>(ctx->fp);
    if (fp != 0) {
        for (int off : {0x10, 0x18, 0x20, 0x28, 0x30, 0x38, 0x40}) {
            uintptr_t slot_val = 0;
            if (safe_read(&slot_val, reinterpret_cast<const void *>(fp + off), sizeof(uintptr_t))) {
                auto decoded = describe_dart_value(slot_val);
                if (decoded.type != DecodedValue::NOISE && decoded.type != DecodedValue::NULL_VAL) {
                    char label[32];
                    snprintf(label, sizeof(label), "[FP+0x%x]", off);
                    valid_args.push_back({format_label_with_ptr(label, slot_val), decoded});
                }
            }
        }
    }

    for (size_t i = 0; i < valid_args.size(); ++i) {
        bool is_last = (i + 1 == valid_args.size());
        std::string branch = is_last ? "    └── " : "    ├── ";
        trace_output(branch + valid_args[i].first + ": " + valid_args[i].second.summary);
    }

    if (trace_ret && ctx->lr != 0) {
        auto *st = get_ret_stack();
        if (st && st->size() < 32) {
            ReturnFrame rf;
            rf.original_lr = ctx->lr;
            rf.name = name;
            rf.start_time = std::chrono::steady_clock::now();
            st->push_back(rf);

            ctx->lr = reinterpret_cast<uint64_t>(dobby_return_trampoline);
        }
    }
#elif defined(_M_X64) || defined(__x86_64__)
    auto arg0 = describe_dart_value(ctx->general.regs.rdi);
    auto arg1 = describe_dart_value(ctx->general.regs.rsi);
    auto arg2 = describe_dart_value(ctx->general.regs.rdx);
    if (arg0.type != DecodedValue::NOISE && arg0.type != DecodedValue::NULL_VAL) {
        trace_output("    ├── Arg0: " + arg0.summary);
    }
    if (arg1.type != DecodedValue::NOISE && arg1.type != DecodedValue::NULL_VAL) {
        trace_output("    └── Arg1: " + arg1.summary);
    }
#endif
}

} // namespace

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

void dart_tracer_init(uintptr_t lib_base, size_t lib_size, const std::string &process_name) {
    std::lock_guard<std::mutex> lock(g_tracer_mutex);
    g_lib_base = lib_base;
    g_lib_size = lib_size;
    g_process_name = process_name;

    std::string log_path = "/data/data/" + process_name + "/cache/flutterlens_trace.log";
    g_trace_log_file = fopen(log_path.c_str(), "a");
    if (g_trace_log_file) {
        fi_log_info("dart_tracer: opened log file at %s", log_path.c_str());
    } else {
        fi_log_warn("dart_tracer: failed to open log file at %s", log_path.c_str());
    }

    fi_log_info("dart_tracer: initialized for %s (lib_base=0x%zx, size=0x%zx)",
                process_name.c_str(), lib_base, lib_size);
}

static bool make_code_writable(uintptr_t addr, size_t len = 64) {
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;
    uintptr_t page_start = addr & ~(static_cast<uintptr_t>(page_size) - 1);
    uintptr_t page_end = (addr + len + page_size - 1) & ~(static_cast<uintptr_t>(page_size) - 1);
    size_t total_size = page_end - page_start;
    return (mprotect(reinterpret_cast<void *>(page_start), total_size, PROT_READ | PROT_WRITE | PROT_EXEC) == 0);
}

bool dart_tracer_hook(const HookTarget &target) {
    std::lock_guard<std::mutex> lock(g_tracer_mutex);
    if (g_lib_base == 0) {
        fi_log_error("dart_tracer: cannot hook before initialization");
        return false;
    }

    uintptr_t runtime_addr = g_lib_base + target.offset;

    // Check if already hooked
    for (auto &ih : g_installed_hooks) {
        if (ih.target.offset == target.offset && ih.active) {
            ih.target = target;
            fi_log_info("dart_tracer: function %s (0x%zx) is already hooked (metadata updated)",
                        target.name.c_str(), runtime_addr);
            return true;
        }
    }

    make_code_writable(runtime_addr, 64);
    int ret = DobbyInstrument(reinterpret_cast<void *>(runtime_addr), dart_entry_dbi_callback);
    if (ret == 0) {
        InstalledHook ih;
        ih.target = target;
        ih.runtime_addr = runtime_addr;
        ih.active = true;
        g_installed_hooks.push_back(ih);

        fi_log_info("dart_tracer: [✓] successfully hooked %s at 0x%zx (+0x%zx)",
                    target.name.c_str(), runtime_addr, target.offset);
        return true;
    } else {
        fi_log_error("dart_tracer: [✗] DobbyInstrument failed for %s at 0x%zx (code=%d)",
                     target.name.c_str(), runtime_addr, ret);
        return false;
    }
}

bool dart_tracer_unhook(uintptr_t offset) {
    std::lock_guard<std::mutex> lock(g_tracer_mutex);
    for (auto it = g_installed_hooks.begin(); it != g_installed_hooks.end(); ++it) {
        if (it->target.offset == offset && it->active) {
            DobbyDestroy(reinterpret_cast<void *>(it->runtime_addr));
            it->active = false;
            fi_log_info("dart_tracer: unhooked %s (+0x%zx)", it->target.name.c_str(), offset);
            g_installed_hooks.erase(it);
            return true;
        }
    }
    return false;
}

void dart_tracer_sync_hooks(const std::vector<HookTarget> &targets) {
    std::lock_guard<std::mutex> lock(g_tracer_mutex);
    if (g_lib_base == 0) return;

    // Remove obsolete hooks
    for (auto it = g_installed_hooks.begin(); it != g_installed_hooks.end();) {
        bool still_present = false;
        for (const auto &t : targets) {
            if (t.offset == it->target.offset) {
                still_present = true;
                break;
            }
        }
        if (!still_present && it->active) {
            DobbyDestroy(reinterpret_cast<void *>(it->runtime_addr));
            fi_log_info("dart_tracer: removed obsolete hook %s (+0x%zx)",
                        it->target.name.c_str(), it->target.offset);
            it = g_installed_hooks.erase(it);
        } else {
            ++it;
        }
    }

    // Add new hooks
    for (const auto &t : targets) {
        bool already_installed = false;
        for (auto &ih : g_installed_hooks) {
            if (ih.target.offset == t.offset && ih.active) {
                ih.target = t;
                already_installed = true;
                break;
            }
        }
        if (!already_installed) {
            uintptr_t runtime_addr = g_lib_base + t.offset;
            make_code_writable(runtime_addr, 64);
            int ret = DobbyInstrument(reinterpret_cast<void *>(runtime_addr), dart_entry_dbi_callback);
            if (ret == 0) {
                InstalledHook ih;
                ih.target = t;
                ih.runtime_addr = runtime_addr;
                ih.active = true;
                g_installed_hooks.push_back(ih);
                fi_log_info("dart_tracer: [✓] synced hook %s at 0x%zx (+0x%zx)",
                            t.name.c_str(), runtime_addr, t.offset);
            } else {
                fi_log_error("dart_tracer: [✗] sync failed to hook %s at 0x%zx",
                             t.name.c_str(), runtime_addr);
            }
        }
    }
}

size_t dart_tracer_active_count() {
    std::lock_guard<std::mutex> lock(g_tracer_mutex);
    return g_installed_hooks.size();
}
