// FlutterInspect native module
// Forked from FlutterTap by Eduardo Lopes. FlutterInspect by Akshay Khilari.
//
// Minimal read-only JSON parser. Only implements what is needed to read
// config.json (objects, arrays, strings, numbers, booleans, null).
// Avoids vendoring a large third-party JSON dependency for one small file.
#pragma once

#include <map>
#include <string>
#include <vector>

struct JsonValue {
    enum class Type { Null, Bool, Number, String, Array, Object };

    Type type = Type::Null;
    bool boolValue = false;
    double numValue = 0.0;
    std::string strValue;
    std::vector<JsonValue> arrValue;
    std::map<std::string, JsonValue> objValue;

    bool isNull()   const { return type == Type::Null;   }
    bool isBool()   const { return type == Type::Bool;   }
    bool isNumber() const { return type == Type::Number;  }
    bool isString() const { return type == Type::String;  }
    bool isArray()  const { return type == Type::Array;   }
    bool isObject() const { return type == Type::Object;  }

    // Convenience accessors (return default if wrong type)
    bool        asBool(bool def = false)         const { return isBool()   ? boolValue       : def; }
    double      asNumber(double def = 0.0)        const { return isNumber() ? numValue         : def; }
    std::string asString(std::string def = {})   const { return isString() ? strValue         : def; }

    const JsonValue *get(const std::string &key) const {
        if (!isObject()) return nullptr;
        auto it = objValue.find(key);
        return it == objValue.end() ? nullptr : &it->second;
    }
};

// Parses JSON text into `out`. Returns false on syntax error.
bool json_parse(const std::string &text, JsonValue &out);
