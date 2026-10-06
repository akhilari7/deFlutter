// FlutterInspect native module
// Forked from FlutterTap by Eduardo Lopes. FlutterInspect by Akshay Khilari.
#include "json_min.h"

#include <cctype>
#include <cstdlib>

namespace {

class Parser {
public:
    explicit Parser(const std::string &text) : s(text), pos(0), len(text.size()) {}

    bool parseValue(JsonValue &out) {
        // Bounded recursion: parseValue -> parseObject/parseArray -> parseValue.
        // A config.json with a few thousand '[' would overflow the stack inside
        // every app process. Real configs nest 2 deep.
        struct DepthGuard {
            int &d;
            explicit DepthGuard(int &counter) : d(counter) { ++d; }
            ~DepthGuard() { --d; }
        } guard(depth);
        if (depth > kMaxDepth) return false;

        skipWs();
        if (pos >= len) return false;
        char c = s[pos];
        if (c == '{') return parseObject(out);
        if (c == '[') return parseArray(out);
        if (c == '"') return parseString(out);
        if (c == 't' || c == 'f') return parseBool(out);
        if (c == 'n') return parseNull(out);
        if (c == '-' || isdigit(static_cast<unsigned char>(c))) return parseNumber(out);
        return false;
    }

private:
    static constexpr int kMaxDepth = 32;

    const std::string &s;
    size_t pos;
    size_t len;
    int depth = 0;

    void skipWs() {
        while (pos < len && (s[pos] == ' ' || s[pos] == '\t' ||
                             s[pos] == '\n' || s[pos] == '\r'))
            ++pos;
    }

    bool parseObject(JsonValue &out) {
        ++pos; // '{'
        out.type = JsonValue::Type::Object;
        skipWs();
        if (pos < len && s[pos] == '}') { ++pos; return true; }
        while (pos < len) {
            skipWs();
            JsonValue keyVal;
            if (!parseString(keyVal)) return false;
            skipWs();
            if (pos >= len || s[pos] != ':') return false;
            ++pos;
            JsonValue val;
            if (!parseValue(val)) return false;
            out.objValue[keyVal.strValue] = std::move(val);
            skipWs();
            if (pos >= len) return false;
            if (s[pos] == '}') { ++pos; return true; }
            if (s[pos] != ',') return false;
            ++pos;
        }
        return false;
    }

    bool parseArray(JsonValue &out) {
        ++pos; // '['
        out.type = JsonValue::Type::Array;
        skipWs();
        if (pos < len && s[pos] == ']') { ++pos; return true; }
        while (pos < len) {
            JsonValue elem;
            if (!parseValue(elem)) return false;
            out.arrValue.push_back(std::move(elem));
            skipWs();
            if (pos >= len) return false;
            if (s[pos] == ']') { ++pos; return true; }
            if (s[pos] != ',') return false;
            ++pos;
        }
        return false;
    }

    bool parseString(JsonValue &out) {
        if (pos >= len || s[pos] != '"') return false;
        ++pos;
        std::string result;
        while (pos < len) {
            char c = s[pos++];
            if (c == '"') {
                out.type = JsonValue::Type::String;
                out.strValue = std::move(result);
                return true;
            }
            if (c == '\\') {
                if (pos >= len) return false;
                char e = s[pos++];
                switch (e) {
                    case '"': result += '"'; break;
                    case '\\': result += '\\'; break;
                    case '/': result += '/'; break;
                    case 'n': result += '\n'; break;
                    case 'r': result += '\r'; break;
                    case 't': result += '\t'; break;
                    default: result += e; break;
                }
            } else {
                result += c;
            }
        }
        return false;
    }

    bool parseBool(JsonValue &out) {
        if (pos + 4 <= len && s.substr(pos, 4) == "true") {
            out.type = JsonValue::Type::Bool;
            out.boolValue = true;
            pos += 4;
            return true;
        }
        if (pos + 5 <= len && s.substr(pos, 5) == "false") {
            out.type = JsonValue::Type::Bool;
            out.boolValue = false;
            pos += 5;
            return true;
        }
        return false;
    }

    bool parseNull(JsonValue &out) {
        if (pos + 4 <= len && s.substr(pos, 4) == "null") {
            out.type = JsonValue::Type::Null;
            pos += 4;
            return true;
        }
        return false;
    }

    bool parseNumber(JsonValue &out) {
        size_t start = pos;
        if (pos < len && s[pos] == '-') ++pos;
        while (pos < len && isdigit(static_cast<unsigned char>(s[pos]))) ++pos;
        if (pos < len && s[pos] == '.') {
            ++pos;
            while (pos < len && isdigit(static_cast<unsigned char>(s[pos]))) ++pos;
        }
        if (pos < len && (s[pos] == 'e' || s[pos] == 'E')) {
            ++pos;
            if (pos < len && (s[pos] == '+' || s[pos] == '-')) ++pos;
            while (pos < len && isdigit(static_cast<unsigned char>(s[pos]))) ++pos;
        }
        if (pos == start) return false;
        out.type = JsonValue::Type::Number;
        out.numValue = std::strtod(s.c_str() + start, nullptr);
        return true;
    }
};

} // namespace

bool json_parse(const std::string &text, JsonValue &out) {
    Parser p(text);
    return p.parseValue(out);
}
