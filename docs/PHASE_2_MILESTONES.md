# FlutterInspect — Phase 2 Milestones & Architecture Memory

**Date**: October 3, 2026  
**Author**: Akshay Khilari (Payatu)  
**Version**: `v2.0.0-phase2`  
**Target Architecture**: Android arm64-v8a / x86_64 | Dart AOT Snapshot | Zygisk Lifecycle

---

## 1. Executive Summary & Milestones Achieved

Phase 2 transformed FlutterInspect from a heuristic rodata memory scanner into an enterprise-grade, dual-engine Dart AOT runtime disassembler and dynamic tracer. Ground truth symbol integration from Blutter has replaced speculative heuristics, and in-process native DBI hooking via Dobby provides lightning-fast interception without triggering anti-Frida detections.

### Key Milestones Delivered:
1. **Blutter Symbol Integration (Clean Architecture)**:
   - Eliminated reliance on error-prone rodata string scanning.
   - Parses Blutter `symbols.json` directly into mapped Class IDs (CIDs), field offsets, and function offset dictionaries (e.g. 8,900 functions & 3,010 classes for `com.eightksec.droidpass`).
   - Automatically loads symbols from `/sdcard/Download/FlutterInspect/<pkg>/symbols.json` or `/data/local/tmp/` on startup.
   - Removed all references to "Ground Truth" across the UI.

2. **Native Inline Hooking with Dobby DBI**:
   - Integrated Dobby (`io.github.vvb2060.ndk:dobby:1.2`) into the Zygisk native module (`libflutterlens.so`).
   - Resolved the page-boundary trampoline crash (`SIGSEGV / SEGV_ACCERR`) by implementing `make_code_writable` with cross-page `mprotect` protection.
   - Dynamic real-time hook synchronization via globally accessible `/data/local/tmp/flutterlens_config.json` without requiring target app restart.

3. **Dart AOT Runtime Dissection & Argument Inspector**:
   - Traverses ARM64 Dart calling conventions: receiver/context register `x0` (`Arg0`), registers `x1`–`x4`, caller frame pointer slots `[FP+0x18]`, `[FP+0x20]`, etc.
   - Decodes compressed pointers (32-bit heap refs) and 64-bit tagged pointers (Smi vs HeapObject).
   - Resolves Class IDs (`cid=0xd2c: QueryResultSet`, `cid=0x39: Closure`), Uint8List byte arrays, and UTF-8/ASCII strings.
   - Live verified decrypt interception on `EncryptionService.decrypt` capturing real-time plaintext secrets.

4. **Multi-Function Batch Interceptor (Up to 20 Functions)**:
   - Re-engineered selection workflow: single-function mode by default (tap card to inspect immediately).
   - Dedicated `BATCH` mode allowing selection of up to 20 target functions with batch action toolbar (`Top 20`, `Clear`, `Exit`).
   - Simultaneous batch interception implemented for both **Dobby Native Inline** and **Frida Dynamic JS**.

5. **Streamlined Trace Lab UI**:
   - Compact single-line target header (`0x27b5a4 [EncryptionService.decrypt]`) maximizing console viewport.
   - Dual-engine switcher: **Frida Dynamic Engine** (JS Interceptor) vs **Dobby Native Engine** (In-process inline).
   - Script selector simplified: single mode displays **Dart AOT Argument Inspector** and **Basic Call Tracer**; batch mode automatically displays **Batch Interceptor**.
   - Toggles: **Verbose Output** (clean semantic output vs raw hex addresses) and **Basic Call Tracing** (calls & returns only).
   - Kept **Dart VM Snapshot (kFullAOT)** card with SDK hash and package explorer (`645 Snapshot Packages Discovered`).

---

## 2. Current Architecture & Component Matrix

| Component | Technology | Path / Artifact | Responsibilities |
| :--- | :--- | :--- | :--- |
| **Android Manager App** | Kotlin, Jetpack Compose, Coroutines | `app/build/outputs/apk/debug/app-debug.apk` | Target selection, symbol browser, batch selection, script generator, logcat streaming console. |
| **Zygisk Module** | C++20, Zygisk API v4, Dobby DBI | `module/build/outputs/magisk/FlutterInspect.zip` | Injected into target process during `postAppSpecialize`. Synchronizes hooks and executes native DBI callbacks. |
| **Native Shared Library** | ARM64 ELF | `/data/adb/modules/flutterlens/zygisk/arm64-v8a.so` | Houses `dart_tracer.cpp`, `dart_snapshot.cpp`, `dobby.a`. Handles page permissions and registers DBI hooks. |
| **Symbol Engine** | Kotlin Singleton | `SymbolManager.kt` | Fast in-memory resolution of function offsets, class names by CID, and field layout offsets. |
| **Live Communication** | Linux pipes, logcat, tmp JSON | `/data/local/tmp/flutterlens_config.json` | IPC bridge between manager app and target process for zero-restart dynamic hook synchronization. |

---

## 3. Verified Real-World Capture (DroidPass Proof of Concept)

In live verification against `com.eightksec.droidpass`, Dobby successfully intercepted `EncryptionService.decrypt` and extracted plaintext data in real time:

```text
[★] ⚡ HIT: EncryptionService.decrypt
├── Arg0 (this/recv): "ree"
├── Arg1: Bytes(cid=0x991, len=16)
├── Arg2: "0/sQubyxPRE7ad4u4t/cT5P4cuIo8zJWbsdnnDGMEwY="
├── Arg3: Instance(cid=0x0)
├── Arg4: Uint8List(len=5)
├── [FP+0x18]: Uint8List(cid=0xab: Null, len=2011)
├── [FP+0x20]: Instance(cid=0xd2c: QueryResultSet) -> [...]
...
[✓] ⤶ RETURN: EncryptionService.decrypt -> "0/sQubyxPRE7ad4u4t/cT5P4cuIo8zJWbsdnnDGMEwY=" [0.00ms]
```

---

## 4. Phase 3 Roadmap & TODO Backlog

### Feature 1: Semantic Variable & Field Name Decoration
* **Problem**: Currently, object fields are printed with raw offsets like `+0x10: "ree"` or generic `Arg1`, `Arg2`.
* **Solution**:
  1. **Field Layout Lookup**: Connect `SymbolManager.resolveField(cid, offset)` in the log parsing and native formatting pipelines to translate `+0x10` into `[key (+0x10)]: "ree"`.
  2. **Method Parameter Signature Mapping**: Parse Dart method parameter lists from Blutter metadata to label `Arg1 (secretKey)`, `Arg2 (cipherText)`, etc.

### Feature 2: Embedded Web Server & Host Browser Console Streaming
* **Problem**: Mobile screen space limits log exploration, deep regex analysis, and copy-pasting of long cryptographic keys or tokens during security assessments.
* **Solution**:
  1. **Embedded Ktor/CIO Web Server**: Lightweight HTTP + WebSocket server running in FlutterInspect (port `8088`).
  2. **Zero-Setup Desktop Pairing**: Accessible via USB with `adb forward tcp:8088 tcp:8088` (`http://localhost:8088`) or local Wi-Fi.
  3. **Desktop Terminal Dashboard**:
     - Web-based dark terminal featuring real-time log streaming.
     - Live regex and keyword filtering across thousands of lines.
     - One-click copy for plaintexts, keys, and tokens.
     - Session recording and export to JSON / HAR.

### Feature 3: Dynamic Argument Modification (Tampering Engine)
* **Goal**: Enable analysts to override return values (e.g. force authentication checks to return `true` or bypass SSL pinning checks) directly from the UI or web terminal.

---

## 5. Release Backup Index

* **Release Tag**: `v2.0.0-phase2`
* **Directory**: `dist/release-v2.0.0-phase2/`
  * `FlutterInspect-v2.0.0-debug.apk` (Manager App)
  * `FlutterInspect-v2.0.0-zygisk.zip` (Flashable Magisk/KernelSU Module)
  * `arm64-v8a.so` (Pre-compiled 64-bit Zygisk binary)
  * `x86_64.so` (Pre-compiled emulator Zygisk binary)
