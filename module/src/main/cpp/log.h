// FlutterInspect native module
// Forked from FlutterTap by Eduardo Lopes. FlutterInspect by Akshay Khilari.
//
// printf-style logging to logcat under the "FlutterInspect" tag.
// Filter with: adb logcat -s FlutterInspect:V
//
// The format attribute makes the compiler type-check every call site.
#pragma once

void fi_log_info(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void fi_log_warn(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void fi_log_error(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
