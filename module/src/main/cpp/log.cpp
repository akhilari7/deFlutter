// FlutterInspect native module
// Forked from FlutterTap by Eduardo Lopes. FlutterInspect by Akshay Khilari.
#include "log.h"

#include <android/log.h>
#include <cstdarg>

#define LOG_TAG "FlutterInspect"

void fi_log_info(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, LOG_TAG, fmt, args);
    va_end(args);
}

void fi_log_warn(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_WARN, LOG_TAG, fmt, args);
    va_end(args);
}

void fi_log_error(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_ERROR, LOG_TAG, fmt, args);
    va_end(args);
}
