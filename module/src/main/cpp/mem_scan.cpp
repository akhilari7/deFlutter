// FlutterInspect native module
// Forked from FlutterTap by Eduardo Lopes. FlutterInspect by Akshay Khilari.
//
// SIGSEGV/SIGBUS-guarded memory scan. Port of flutter+burp.js scanMemory().
#include "mem_scan.h"

#include <pthread.h>

#include <csetjmp>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <sstream>

#include "log.h"

namespace {

// Per-thread state for the guarded-read machinery. Deliberately pthread
// thread-specific data rather than `thread_local`: a thread_local makes the
// compiler emit dynamic-TLS relocations (R_AARCH64_TLSDESC on arm64,
// R_X86_64_DTPMOD64 on x86_64), and Zygisk Next Linker preloads modules into
// zygote with its own minimal ELF loader that never registers a TLS block for
// us — the first access to a thread_local then segfaults in every hooked
// process. pthread_get/setspecific are ordinary libc calls (plain JUMP_SLOT
// relocations) and behave identically under both loaders.
struct GuardState {
    sigjmp_buf jmpBuf;
    volatile sig_atomic_t active;
};

pthread_key_t g_guardKey;
pthread_once_t g_guardKeyOnce = PTHREAD_ONCE_INIT;
// Also read from the signal handler, so kept signal-safe.
volatile sig_atomic_t g_guardKeyReady = 0;

void freeGuardState(void *state) { free(state); }

void createGuardKey() {
    if (pthread_key_create(&g_guardKey, freeGuardState) == 0) g_guardKeyReady = 1;
}

// Allocates on first use per thread. Never call from the signal handler:
// malloc isn't async-signal-safe. safe_read() calls it *before* arming the
// guard, so by the time the handler runs the state already exists.
GuardState *guardState() {
    pthread_once(&g_guardKeyOnce, createGuardKey);
    if (!g_guardKeyReady) return nullptr;
    auto *state = static_cast<GuardState *>(pthread_getspecific(g_guardKey));
    if (state == nullptr) {
        state = static_cast<GuardState *>(calloc(1, sizeof(GuardState)));
        if (state != nullptr && pthread_setspecific(g_guardKey, state) != 0) {
            free(state);
            state = nullptr;
        }
    }
    return state;
}

struct sigaction g_oldSegv {};
struct sigaction g_oldBus  {};

// Hands the signal back to whoever owned it before us. Every path must end
// either in a handler that resolves the fault or in the process dying.
// Returning from a SIGSEGV handler without fixing the fault re-executes the
// faulting instruction, spinning the app at 100% CPU.
void invokeOld(const struct sigaction &old, int sig, siginfo_t *info, void *ctx) {
    if ((old.sa_flags & SA_SIGINFO) && old.sa_sigaction != nullptr) {
        old.sa_sigaction(sig, info, ctx);
        return;
    }
    if (!(old.sa_flags & SA_SIGINFO) && old.sa_handler != SIG_DFL &&
        old.sa_handler != SIG_IGN) {
        old.sa_handler(sig);
        return;
    }
    // Default: re-raise so the kernel generates a core dump / crash report.
    struct sigaction dfl{};
    dfl.sa_handler = SIG_DFL;
    sigaction(sig, &dfl, nullptr);
    raise(sig);
}

void crashHandler(int sig, siginfo_t *info, void *ctx) {
    // g_guardKeyReady is volatile sig_atomic_t — safe to read from a handler.
    if (g_guardKeyReady) {
        auto *state = static_cast<GuardState *>(pthread_getspecific(g_guardKey));
        if (state && state->active) {
            state->active = 0;
            siglongjmp(state->jmpBuf, 1);
        }
    }
    // Not one of our guarded reads — chain to the previous handler.
    const struct sigaction &old = (sig == SIGSEGV) ? g_oldSegv : g_oldBus;
    invokeOld(old, sig, info, ctx);
}

} // namespace

void mem_scan_install_crash_guard() {
    struct sigaction sa{};
    sa.sa_sigaction = crashHandler;
    sa.sa_flags     = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, &g_oldSegv);
    sigaction(SIGBUS,  &sa, &g_oldBus);
    fi_log_info("mem_scan: crash guard installed");
}

bool safe_read(void *dst, const void *src, size_t size) {
    GuardState *state = guardState();
    if (!state) {
        // Guard not available — do a plain copy and hope for the best.
        memcpy(dst, src, size);
        return true;
    }
    state->active = 1;
    if (sigsetjmp(state->jmpBuf, 1) != 0) {
        // Arrived here via siglongjmp from crashHandler — the read faulted.
        return false;
    }
    memcpy(dst, src, size);
    state->active = 0;
    return true;
}

BytePattern compile_pattern(const std::string &hex_pattern) {
    BytePattern p;
    std::istringstream ss(hex_pattern);
    std::string token;
    while (ss >> token) {
        if (token == "??" || token == "?") {
            p.value.push_back(0x00);
            p.mask.push_back(0x00);
        } else {
            uint8_t byte = static_cast<uint8_t>(std::stoul(token, nullptr, 16));
            p.value.push_back(byte);
            p.mask.push_back(0xFF);
        }
    }
    return p;
}

std::vector<uintptr_t> scan_all_matches(uintptr_t base, size_t size,
                                        const BytePattern &pattern) {
    std::vector<uintptr_t> results;
    if (pattern.value.empty() || size < pattern.value.size()) return results;

    const size_t patLen = pattern.value.size();
    const size_t limit  = size - patLen;

    for (size_t off = 0; off <= limit; ++off) {
        bool match = true;
        for (size_t j = 0; j < patLen; ++j) {
            uint8_t byte = 0;
            if (!safe_read(&byte, reinterpret_cast<const void *>(base + off + j), 1)) {
                match = false;
                break;
            }
            if (pattern.mask[j] && (byte & pattern.mask[j]) != pattern.value[j]) {
                match = false;
                break;
            }
        }
        if (match) results.push_back(base + off);
    }
    return results;
}
