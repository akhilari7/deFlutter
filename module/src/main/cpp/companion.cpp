// FlutterInspect native module
// Forked from FlutterTap by Eduardo Lopes. FlutterInspect by Akshay Khilari.
//
// Root companion: reads the config file and streams it to the app process
// over the Zygisk companion socket.
#include "companion.h"

#include <sys/types.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <string>

#include "log.h"

namespace {

constexpr const char *kPrimaryConfigPath  = "/data/adb/flutterlens/config.json";
constexpr const char *kFallbackConfigPath = "/data/local/tmp/flutterlens_config.json";
constexpr const char *kDebugLogPath       = "/data/adb/flutterlens/debug.log";

bool writeFull(int fd, const void *buf, size_t size) {
    const auto *p = static_cast<const uint8_t *>(buf);
    size_t written = 0;
    while (written < size) {
        ssize_t n = write(fd, p + written, size - written);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            return false;
        }
        written += static_cast<size_t>(n);
    }
    return true;
}

bool readFull(int fd, void *buf, size_t size) {
    auto *p = static_cast<uint8_t *>(buf);
    size_t got = 0;
    while (got < size) {
        ssize_t n = read(fd, p + got, size - got);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            return false;
        }
        got += static_cast<size_t>(n);
    }
    return true;
}

std::string readConfigFile() {
    fi_log_info("companion: readConfigFile started");

    FILE *f = fopen(kPrimaryConfigPath, "rb");
    if (f) {
        fi_log_info("companion: opened primary config at %s", kPrimaryConfigPath);
    } else {
        fi_log_warn("companion: failed to open %s (errno=%d)", kPrimaryConfigPath, errno);
        f = fopen(kFallbackConfigPath, "rb");
        if (f) {
            fi_log_info("companion: opened fallback config at %s", kFallbackConfigPath);
        } else {
            fi_log_error("companion: failed to open fallback %s (errno=%d)", kFallbackConfigPath, errno);
            return "";
        }
    }

    std::string content;
    char buf[4096];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
        content.append(buf, n);
    }
    fclose(f);

    fi_log_info("companion: read %zu bytes of config successfully", content.size());
    return content;
}

} // namespace

void companion_handler(int client_fd) {
    fi_log_info("companion_handler entered (client_fd=%d)", client_fd);
    std::string json = readConfigFile();
    auto len = static_cast<uint32_t>(json.size());
    if (!writeFull(client_fd, &len, sizeof(len))) {
        fi_log_warn("companion: failed to write config length to fd %d", client_fd);
        return;
    }
    if (len > 0 && !writeFull(client_fd, json.data(), len)) {
        fi_log_warn("companion: failed to write config body to fd %d", client_fd);
        return;
    }
    fi_log_info("companion: successfully sent %u bytes to client_fd %d", len, client_fd);
}

std::string companion_read_config(int companion_fd) {
    fi_log_info("companion_read_config entered (companion_fd=%d)", companion_fd);
    if (companion_fd < 0) return "";
    uint32_t len = 0;
    if (!readFull(companion_fd, &len, sizeof(len))) {
        fi_log_warn("companion_read_config: failed to read length from fd %d (errno=%d)", companion_fd, errno);
        return "";
    }
    fi_log_info("companion_read_config: incoming len = %u", len);
    if (len == 0 || len > 1024 * 1024) {
        fi_log_warn("companion_read_config: length %u invalid or empty", len);
        return "";
    }
    std::string result(len, '\0');
    if (!readFull(companion_fd, result.data(), len)) {
        fi_log_warn("companion_read_config: failed to read body (errno=%d)", errno);
        return "";
    }
    fi_log_info("companion_read_config: received %zu bytes", result.size());
    return result;
}
