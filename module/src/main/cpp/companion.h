// FlutterInspect native module
// Forked from FlutterTap by Eduardo Lopes. FlutterInspect by Akshay Khilari.
//
// Root companion process: reads config.json and sends it to the module
// running inside each target app process.
#pragma once

#include <string>

// Called from the Zygisk companion handler (root process).
// Reads /data/local/tmp/flutterlens_config.json and writes it to the fd.
void companion_handler(int fd);

// Called from the module (app process side).
// Reads the config JSON from the fd that the companion wrote to.
std::string companion_read_config(int fd);
