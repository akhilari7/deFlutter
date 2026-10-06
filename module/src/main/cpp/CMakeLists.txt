cmake_minimum_required(VERSION 3.22)
project(flutterlens)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# Capstone (disassembly engine) -- same engine Frida's Instruction.parse uses.
# Only ARM64 + X86(x86_64) decoding to keep the .so small.
# ---------------------------------------------------------------------------
set(CAPSTONE_ARCHITECTURE_DEFAULT OFF CACHE BOOL "" FORCE)
set(CAPSTONE_ARM64_SUPPORT ON CACHE BOOL "" FORCE)
set(CAPSTONE_X86_SUPPORT ON CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_CSTOOL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
add_subdirectory(third_party/capstone)

# ---------------------------------------------------------------------------
# FlutterInspect Zygisk module
# ---------------------------------------------------------------------------
add_library(flutterlens SHARED
    log.cpp
    json_min.cpp
    elf_utils.cpp
    mem_scan.cpp
    companion.cpp
    module_config.cpp
    rodata_scan.cpp
    dart_resolver.cpp
    snapshot_walker.cpp
    result_writer.cpp
    dart_tracer.cpp
    main.cpp
)

target_include_directories(flutterlens PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_package(dobby REQUIRED CONFIG)

target_link_libraries(flutterlens
    capstone
    dobby::dobby
    log
    android
    dl
)
