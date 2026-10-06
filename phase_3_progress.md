# Phase 3 Progress & Parity Milestone Summary

**Date**: 2026-10-04  
**Status**: Verified Live on Device (OnePlus 7 - `2fcddb7d`)  
**Target App**: `com.eightksec.droidpass`

---

## 1. Accomplishments & Live Verification

### Complete Parity Between Dobby & Frida Engines
- **Identified Cause of Missing Stack Context in Dobby**:
  - Dart AOT on ARM64 uses a dual-stack layout: native C stack (`sp`) vs. Dart managed evaluation stack (`x15` / `SPREG`).
  - Spilled arguments and temporary evaluation results (such as generated password plaintext and service names in `DatabaseHelper.insertPassword`) sit in evaluation stack slots off `x15`.
- **Dobby Native Inline Tracer (`dart_tracer.cpp`)**:
  - Implemented `x15` (`SPREG`) inspection: offsets `0x00` through `0x40` (step 8) labeled as `[Stack+0x...]`.
  - Implemented `x27` (`CTXREG`) inspection: tagged pointer checking and object decoding for lexical closure context (`x27 (Context)`).
  - Extended caller frame slots off `fp` (`x29`): `{0x10, 0x18, 0x20, 0x28, 0x30, 0x38, 0x40}` labeled as `[FP+0x...]`.
- **Frida Dynamic Interceptor (`FridaSnippetGenerator.kt`)**:
  - Synchronized inspection logic: expanded `ctx.x15` scan up to `0x40`, added `ctx.x27` (Context), and expanded `ctx.fp` up to `0x40`.
- **Live Device Verification**:
  - Rebuilt native library with NDK and stripped with `llvm-strip` (4.6 MB).
  - Deployed to `/data/adb/modules/flutterlens/zygisk/arm64-v8a.so` and updated `FlutterInspect` APK.
  - Confirmed live: `DatabaseHelper.insertPassword` hits and caller frame context (`[Stack+0x10]`, `[Stack+0x18]`) logged with full parity.

---

## 2. Upcoming Roadmap (Next Session)

1. **Symbolic Field / Variable Offset Resolution**:
   - Map field offsets (+0x4, +0x8, +0xc, etc.) to actual Dart class field names extracted from snapshot metadata or Blutter symbol tables.
2. **Remote Web Console / Streaming Server**:
   - Lightweight embedded HTTP/WebSocket server on device to stream live trace hits and interceptor logs to a browser terminal on the host machine.
