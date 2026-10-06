# FlutterInspect: Project Context, Architecture & Script Reference

This document consolidates all architectural details, C++ module implementations, device configurations, Dart AOT runtime internals, and Frida instrumentation scripts created during Phase 1. Use this file as a complete context handoff for future sessions.

---

## 1. Project Overview & Architecture

**FlutterInspect** (formerly *FlutterLens*) is a specialized Zygisk module for Android designed to dynamically locate and resolve unexported Dart/Flutter AOT function addresses and base-relative offsets at runtime.

### Key Capabilities
1. **Dynamic Resolution without Symbols**: Flutter strips symbols in release builds. FlutterInspect leverages `.rodata` string cross-references (`adrp` + `add` / `ldr` instructions in ARM64 `.text`) and disassembles backward to locate enclosing function prologues using Capstone.
2. **True ASLR-Corrected Offsets**: Functions are exported with runtime addresses (`abs_addr`) and base-relative offsets (`offset = abs_addr - base_address`), ready for instant attachment via Frida (`Process.getModuleByName("libapp.so").base.add(offset)`).
3. **Dual Operating Modes**:
   - `keyword_search`: Fast targeted search for specific strings/methods (e.g. `"command"`, `"login"`, `"http"`).
   - `full_enumerate`: Exhaustive scanning across all Dart function entrypoints.

---

## 2. Directory Structure & Key Files

```
FlutterInspect/
├── module/
│   ├── build.gradle.kts           # Configured for NDK 27.2, arm64-v8a, Capstone linkage
│   ├── src/main/cpp/
│   │   ├── main.cpp               # Zygisk entrypoints (onLoad, pre/postAppSpecialize)
│   │   ├── dart_resolver.h/.cpp   # Capstone xref resolver & prologue back-tracer (256-byte window)
│   │   ├── rodata_scan.h/.cpp     # String & Dart identifier scanner
│   │   ├── mem_scan.h/.cpp        # Signal-guarded memory reader (safe_read)
│   │   ├── result_writer.h/.cpp   # JSON exporter (writes to app cache & /data/local/tmp)
│   │   ├── companion.h/.cpp       # Magisk companion root socket for SELinux-safe config loading
│   │   └── module_config.h/.cpp   # Config parser (targets, keywords, modes)
│   └── template/                  # Magisk packaging scripts (module.prop, customize.sh)
├── scripts/
│   ├── package_zip.py             # Packaging script creating flashable Magisk zip
│   ├── sample_hook_functions.js   # Generic Frida function hooker
│   ├── sample_inspect_args.js     # Generic Dart AOT argument decoder
│   ├── hook_insecure_commands_args.js # Argument inspector for insecure_commands.dart
│   └── run_frida_hook.py          # Python Frida runner with auto-attach and unbuffered logs
├── config_keyword_example.json    # Example configuration
└── PROJECT_CONTEXT_HANDOFF.md     # This reference file
```

---

## 3. The 256-Byte Scanning Window Enhancement

In `module/src/main/cpp/dart_resolver.cpp`:
* **Problem**: Dart AOT function metadata often references string literals within an enclosing descriptor chunk. The original 80-byte scanning window missed earlier entry points when Dart packed multiple function references or closures together.
* **Solution**: The scanning window was expanded to 256 bytes:
  ```cpp
  uintptr_t chunkStart = fn_ref > 64 ? fn_ref - 64 : 0;
  size_t chunkLen = 256;
  ```
* **Result**: For keyword `"command"`, matches expanded from 3 functions to **16 distinct functions**, successfully capturing all 7 entry points for `insecure_commands.dart`, 1 for `secure_commands.dart`, and 8 for `performPrivateCommand`.

---

## 4. Target Test Environment

- **Device**: OnePlus 8 (`IN2011`), Android 13 (ARM64-v8a).
- **Root & Modules**: Magisk 30.7, ReZygisk, FlutterInspect installed at `/data/adb/modules/flutterlens/`.
- **Connectivity**: Wi-Fi ADB at `10.55.55.109:5555`.
- **Frida Server**: `frida-server-17.17.0-android-arm64` running standalone in daemon mode (`-D`) on the device.
- **Target Application**: `co.ostorlab.insecure_app` (process name: `Insecure_App`), base APK contains `libapp.so`.
- **Generated JSON**: `/data/data/co.ostorlab.insecure_app/cache/flutterlens.json`.

---

## 5. Dart AOT ARM64 Runtime & Calling Conventions

Understanding these conventions allows completely generic argument decoding without source code:

### A. Dedicated Dart Stack Pointer (`x15`)
Dart ARM64 AOT does not use `sp` for Dart frames. It reserves **`x15`** as the dedicated Dart Stack Pointer.
Function prologues follow:
```arm64
stp x29, x30, [x15, #-16]!   ; Push caller FP (x29) and LR (x30) onto Dart stack
mov x29, x15                 ; Set Frame Pointer (x29) to top of Dart stack
```

### B. Frame Pointer Relative Argument Layout
The caller pushes arguments onto the Dart stack (`x15`) prior to the `blr`/`br` call. Once inside the callee:
- `[FP + 0x10]` (`[x29 + 16]`): First argument (receiver `this` instance, or first parameter).
- `[FP + 0x18]` (`[x29 + 24]`): Second argument (e.g. `String command` / `input`).
- `[FP + 0x20]` (`[x29 + 32]`): Third argument / context closure.

### C. Register Fallbacks
In certain optimized leaf functions, stub calls, and closures:
- `x0`: Receiver / Arg 0.
- `x1`: Arg 1.
- `x2`: Arg 2.
- `x3`: Arg 3.

### D. Tagged Pointer Representation (64-Bit Dart)
* **`LSB == 0` (Bit 0 is 0) -> `Smi` (Small Integer)**:
  - Value = `rawPointer >> 1`.
  - In Frida: `ptr.and(1).isNull()`, integer value is `ptr.shr(1).toString()`.
* **`LSB == 1` (Bit 0 is 1) -> `HeapObject`**:
  - Untagged Pointer = `rawPointer - 1`.
  - In Frida: `const untagged = ptr.sub(1)`.

### E. `OneByteString` Layout
Any ASCII/UTF-8 Dart string has this universal in-memory structure:
* `untagged + 0x00`: 32-bit Header word (Class ID & GC tags).
* `untagged + 0x08`: String length (stored as a tagged `Smi`! Length = `readPointer(untagged + 8) >> 1`).
* `untagged + 0x10`: Byte array of characters.

---

## 6. Inventory of Frida Scripts

### 1. `scripts/sample_hook_functions.js`
A clean, modular sample showing how to attach to offsets extracted from `flutterlens.json`:
```javascript
const TARGET_MODULE = "libapp.so";
const HOOK_TARGETS = [
    { name: "insecure_commands_entry_0", offset: 0x2b8af0 },
    { name: "insecure_commands_entry_1", offset: 0x2b8db0 }
];

function setupHooks() {
    const mod = Process.findModuleByName(TARGET_MODULE);
    if (!mod) return false;
    HOOK_TARGETS.forEach(target => {
        const targetAddr = mod.base.add(target.offset);
        Interceptor.attach(targetAddr, {
            onEnter(args) {
                console.log(`[+] Hit: ${target.name} @ ${targetAddr}`);
            }
        });
    });
    return true;
}
```

### 2. `scripts/sample_inspect_args.js`
A generic Dart value decoder demonstrating how to inspect any register or memory address:
- Safely handles `NativePointer` bitwise checks.
- Decodes `Smi`, `OneByteString`, `bool`, and `HeapObject` headers.

### 3. `scripts/hook_insecure_commands_args.js`
The specialized script used to intercept `insecure_commands.dart` across all 8 discovered offsets:
- Intercepts `0x2b8af0`, `0x2b8db0`, `0x2b9274`, `0x2b98e8`, `0x2b9c48`, `0x2b9ef4`, `0x2ba39c`, `0x2ba4e0`.
- Dumps registers `x0..x3`, `[FP + 0x10]`, `[FP + 0x18]`, `[FP + 0x20]`.
- Emits events via both `send()` and `console.log()`.

### 4. `scripts/run_frida_hook.py`
The Python Frida runner:
- Connects to `10.55.55.109:5555`.
- Attaches to `co.ostorlab.insecure_app` / `Insecure_App`.
- Flushes stdout immediately (`flush=True`) for background task logging.
- Accepts any target script: `python scripts/run_frida_hook.py scripts/<script_name>.js`.

---

## 7. Live Device Verification Summary

When clicking **"Run All"** in the Ostorlab test app:
- **Hook Triggered**: Target `#0` (`0x2b8af0`), Target `#2` (`0x2b9274`), Target `#3` (`0x2b98e8`), Target `#5` (`0x2b9ef4`).
- **Decoded Arguments**:
  - `Target #0`: `x3: String: ""`
  - `Target #2`: `[FP + 0x10]: String: ""` and `[FP + 0x18]: String: ""`
  - `Target #5`: `[FP + 0x18]: String: ""`
- **Finding**: Ostorlab's automated test harness passes an empty string `""` by default to `InsecureCommands.run("")`, which calls `executeCommand("")`. Our argument inspector accurately captured the type (`String`), length (`0`), and empty buffer value without any source code modification.

---

## 8. Is This Approach Generic for Blind Analysis?

- **Universal**: The memory layout of `Smi`, `OneByteString`, Dart heap headers, and the `x15`/`x29` frame pointer argument slots (`[FP + 0x10]`, `[FP + 0x18]`) are VM-level constants across all Flutter ARM64 release builds.
- **Blind Discovery**: Even without knowing class/method names, any string or URL searched in `.rodata` will lead to its referencing functions and allow extracting whatever arguments are passed to them at runtime.

---

## 9. Phase 2: On-Device FlutterInspect Manager App (`:app`)

Phase 2 introduces a native Android Companion & Manager Application built with **Jetpack Compose Material 3** (`com.payatu.flutterinspect`), directly integrated into the root Gradle workspace.

### Key Capabilities & Architecture
1. **Root & Namespace Isolation (`su -mm`)**:
   - Executes root commands using interactive `ProcessBuilder` with stdin piping (`\nexit\n`).
   - Uses `su -mm` (Mount Master namespace) to cross per-app mount namespaces and directly read `/data/data/<pkg>/cache/flutterlens.json`, `/data/user/0/<pkg>/cache/flutterlens.json`, and write to `/data/adb/flutterlens/config.json`.
2. **Target App Discovery**:
   - Inspects installed applications via `PackageManager`.
   - Inspects APKs and native library paths for `libflutter.so`, `libapp.so`, and `assets/flutter_assets/` to automatically highlight target Flutter apps with a distinct cyan badge.
3. **Interactive Config Editor**:
   - Process selection with integrated modal picker.
   - Discovery mode toggle: `Keyword Search` vs `Full Enumerate`.
   - Chip manager for keywords with preset security anchors (`command`, `login`, `http`, `token`, `auth`, `crypto`, `secret`, `user`) + custom keyword input.
   - Target library selection (`libapp.so` user code vs `libflutter.so` engine).
   - Instant deployment to `/data/adb/flutterlens/config.json` and one-tap force-stop + restart of the target process.
4. **Discovered Functions Explorer**:
   - Real-time search and filter across function names, base-relative offsets, and ASLR addresses.
   - Visual cards showing function name, cyan offset chip with one-tap clipboard copying, ASLR runtime address, and XREF address.
5. **Frida Studio / Code Lab**:
   - Template selection:
     - **Basic Call Tracer**: Minimalistic interceptor logging invocations and return addresses.
     - **Dart AOT Argument Inspector**: Implements the universal ARM64 Dart runtime calling convention decoders discovered in Phase 1 (`x15` Dart stack pointer, `[FP + 0x10]` receiver, `[FP + 0x18]` arg 1, `[FP + 0x20]` arg 2, registers `x0..x3`, `Smi`, and `OneByteString` length and payload extraction).
     - **Batch Multi-Hook**: Hooks all filtered functions simultaneously.
   - Interactive dropdown to switch target functions or batch mode on the fly.
   - Styled dark monospace code viewer with 1-tap **Copy Script** to clipboard and **Share / Export** via Android Intent.

### Device Test Confirmation
- Built debug APK: `app/build/outputs/apk/debug/app-debug.apk` (AGP 8.9.2, Kotlin 2.0.21, CompileSdk 35).
- Installed and verified live on **OnePlus 8 (`IN2011`, Android 13, Magisk 30.7)** at `10.55.55.109:5555`.
- Successfully verified root acquisition (`ROOT: ACTIVE`), reading of 16 resolved functions for `co.ostorlab.insecure_app`, live offset copying, and interactive Frida script generation.

---

## 10. Phase 2 Point 1: Dart VM Snapshot Cluster Walker (`v0.3.0`)

Point 1 enhances the native C++ engine with direct parsing of Dart AOT serialized heap snapshots without relying strictly on heuristics.

### Key Technical Implementations
1. **Dart Snapshot Symbols (`_kDart*`)**:
   - `_kDartIsolateSnapshotData`: Serialized heap snapshot containing classes, libraries, and objects.
   - `_kDartIsolateSnapshotInstructions`: Target executable code segment for isolate functions.
   - `_kDartVmSnapshotData` & `_kDartVmSnapshotInstructions`: VM core objects and pre-compiled runtime stubs.
2. **Bionic Linker Relocation Handling (`elf_utils.cpp`)**:
   - In modern Android Bionic dynamic linkers, `d_ptr` values for `DT_SYMTAB` and `DT_STRTAB` in `.dynamic` are already relocated to absolute memory addresses.
   - Updated `find_symbol_address()` to check `if (vaddr >= mod.base)` so symbol offsets are not double-added to `mod.base`.
3. **Snapshot Cluster Header Parsing (`snapshot_walker.cpp`)**:
   - Validates snapshot magic `0xdcdcf5f5` and kind `kFullAOT` (kind 3).
   - Extracts VM SDK MD5 hash (e.g. `adb4292f3ec25074ca70abcd2d5c7251` -> Flutter 3.7.12 / Dart 2.19) and feature string (`arm64 android compressed-pointers null-safety...`).
   - Decodes cluster header counts using ULEB128 decoding: `num_base_objects`, `num_objects`, `num_clusters`.
   - Scans the serialized snapshot payload directly to discover `package:` strings and library namespaces.
   - Emits structured `"snapshot"` metadata block into `flutterlens.json`.
4. **Interactive Snapshot Engine UI (`ResultsScreen.kt`)**:
   - Displays a dedicated Snapshot Engine Card in the Manager app:
     - Snapshot Engine Badge & Magic (`0xdcdcf5f5`)
     - SDK Hash banner with one-tap copy button
     - Metric chips: Clusters, Objects, Base Objects, and Code Offset
     - VM Feature flag badges
     - Discovered snapshot package / library explorer dialog

---

## 11. Archived Release Deliverables

### Release v0.2.0 (`dist/release-v0.2.0/`)
- `FlutterInspect-v0.2.0-manager.apk` (62,450,542 bytes): Full Jetpack Compose Material 3 Manager App.
- `FlutterInspect-v0.2.0-zygisk.zip` (1,485,106 bytes): Magisk/Zygisk native inspection module.
- `FlutterInspect-v0.2.0-source.zip` (22,050,676 bytes): Complete project source tree.

### Release v0.3.0 (`dist/release-v0.3.0/`)
- `FlutterInspect-v0.3.0-manager.apk` (62,249,562 bytes): Updated Manager App with Snapshot Engine Card and package symbol viewer.
- `FlutterInspect-v0.3.0-zygisk.zip` (1,493,885 bytes): Native module with Dart VM Snapshot Cluster Walker.
- `FlutterInspect-v0.3.0-source.zip` (14,948,699 bytes): Complete source archive.
- `screen_snap_snapshot_engine.png`: Live screenshot of Manager App showing Snapshot Engine Card — 90 clusters, 14,984 objects, 651 packages from `co.ostorlab.insecure_app`.

---

## 12. Key Engineering Rules & Lessons Learned

### Magisk memfd Stale-Cache Behaviour (CRITICAL)
- `magiskd` opens a `memfd` file descriptor for each Zygisk module **once at boot** and caches it in memory.
- `magisk --install-module` writes to `/data/adb/modules_update/<id>/` and stages; it does NOT overwrite the active module until the next reboot.
- Copying the `.so` directly to `/data/adb/modules/<id>/zygisk/arm64-v8a.so` updates the file on disk, but `magiskd` **continues serving the old cached memfd** to all newly forked app processes.
- `magisk --zygote-restart` causes Zygote to reconnect but does NOT force `magiskd` to reload its module memfd cache.
- **The ONLY way to pick up a new Zygisk module binary is a full device reboot** (`adb shell su -c reboot`).
- After reboot, re-enable ADB via: `adb connect 10.55.55.109:5555`.

### Gradle `AccessDeniedException` on Windows / OneDrive Workaround
- When Gradle builds fail with `AccessDeniedException` on `mergeReleaseNativeLibs` or `packageReleaseResources`, it is caused by OneDrive or Windows holding file locks on the build intermediates directory.
- **Fix**: Kill the Gradle daemon (`Get-Process java | Stop-Process -Force`), then delete the locked intermediates:
  ```
  Remove-Item -Recurse -Force module\build\intermediates\merged_native_libs\release
  Remove-Item -Recurse -Force module\build\intermediates\incremental\release\packageReleaseResources
  ```
- C++ compilation outputs (`cxx\RelWithDebInfo\...\obj\`) are NOT affected and can be reused.
- **Fast workaround**: Strip the compiled `.so` manually using NDK `llvm-strip` and zip directly from `module\template\` — bypasses the broken `packageReleaseResources` Gradle task entirely:
  ```powershell
  $strip = "C:\...\ndk\27.2.12479018\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"
  & $strip -s -o zygisk/arm64-v8a.so module\build\intermediates\cxx\RelWithDebInfo\...\obj\arm64-v8a\libflutterlens.so
  Compress-Archive -Path module\template\*,zygisk -DestinationPath dist\FlutterInspect-zygisk.zip
  ```

---

## 13. Phase 2 Point 1: Live Verification Results (2026-09-28)

Verified live on OnePlus 8 (`IN2011`, Android 13) post full-reboot with `v0.3.0` module:

| Metric | Value |
|--------|-------|
| Magic | `0xdcdcf5f5` (kFullAOT) |
| SDK Hash | `adb4292f3ec25074ca70abcd2d5c7251` (Flutter 3.7.12 / Dart 2.19) |
| Clusters | 90 |
| Objects | 14,984 |
| Base Objects | 64 |
| Code Offset | `0x22d980` |
| Packages extracted | **651** |
| JSON output size | **40,794 bytes** (vs 2,506 bytes pre-snapshot) |

Notable packages discovered (security-relevant):
- `package:ostorlab_insecure_flutter_app/bugs/hardcoded_creds_in_url.dart`
- `package:pointycastle/digests/sha512.dart`, `package:pointycastle/block/des_base.dart`
- `package:local_auth_platform_interface/types/auth_messages.dart`
- `package:webview_flutter_android/src/android_webview.g.dart`
- `package:flutter_inappwebview/src/in_app_webview/ios/in_app_webview_options.dart`

---

## 14. Phase 2 Point 2: Frida Lab / Dynamic In-App Interceptor [COMPLETED & LIVE VERIFIED]

**Goal Achieved**: Complete on-device dynamic function hooking and live streaming log console within the FlutterInspect Manager App — **Zero Laptop / External Frida Requirement**.

### Implementation Architecture:
1. **Frida Engine**:
   - Deployed standalone static `frida-inject-17.6.2-android-arm64` binary to `/data/local/tmp/frida-inject` (`chmod 755`).
   - Does not require a background `frida-server` daemon, external ports, or USB debugging.
2. **Streaming Execution (`RootShell.kt`)**:
   - `startFridaInterceptor(packageName, scriptContent, onLogLine)`:
     - Writes generated JavaScript hook to `/data/local/tmp/flutterlens_hook.js` via stdin (`cat << 'EOF'`).
     - Force-stops stale processes and spawns target app with `/data/local/tmp/frida-inject -f <pkg> -s ...`.
     - Reads injector `stdout` asynchronously in an IO coroutine and parses clean JSON messages.
   - `FridaSession.stop()`:
     - Cancels coroutine job, destroys root process, and runs `pkill -9 frida-inject`.
3. **Frida Studio UI (`FridaStudioScreen.kt`)**:
   - Sub-tabs: `<> Script Editor` (syntax code preview & template selector) and `>_ Live Console` (streaming terminal).
   - Real-time syntax coloring (`EmeraldSuccess` for hits, `RoseError` for exceptions, `CyanPrimary` for pointers/offsets).
   - Bottom Action Button: `▶ RUN HOOK (IN-APP)` (Cyan) / `■ STOP INTERCEPTOR` (Rose Red).
4. **Resilient State Hoisting & Task Switching**:
   - Hoisted `fridaSession`, `isFridaHookRunning`, and `fridaConsoleLogs` to `MainScreen` to preserve output across tab navigation (`Functions` ↔ `Frida Lab`).
   - Configured `android:launchMode="singleTask"` in `AndroidManifest.xml` to prevent activity recreation during target app switching.
   - Added dynamic notification badge on the `Frida Lab` bottom navigation bar displaying real-time hit counts.

### Live Verification on Device (2026-09-29):
- **Device**: OnePlus 7 (`GM1901`), Android 15 (API 35), Magisk 30.6 + Zygisk Next 1.3.3 (`192.168.1.9:5555`).
- **Target App**: `co.ostorlab.insecure_app`.
- **Test Execution**:
  1. **Batch Multi-Hook**: Hooked all 16 `insecure_commands.dart` entry points -> **443 live hits** streamed in real-time.
  2. **Dart AOT Arg & Return Inspector**:
     - Fixed `ptr` parameter variable shadowing crash in `FridaSnippetGenerator.kt`.
     - Added defensive memory reading for Dart SP (`x15`), Caller FP (`x29`), and registers `x0..x3`.
     - Implemented `onLeave` return value decoding (`this.context.x0` / `retval`) and execution duration timing.
     - Hooked `insecure_commands.dart` (`0x2b8af0`) -> **1,194 live invocations** captured with detailed arguments (Smis, Strings, HeapObject class IDs) and return values (`x0`: `HeapObject` / native `0x6f00008081`).
  3. **Snapshot Package Integration**:
     - C++ `main.cpp`: User keywords now match against the 651 snapshot packages extracted by `walk_dart_snapshot` to automatically enrich search queries.
     - Kotlin UI: `ResultsScreen.kt` Snapshot Packages dialog is now interactive with quick-filter buttons to isolate functions per package file.

---

## 15. Release v0.3.1 Artifacts

Located at `dist/release-v0.3.1/`:
- `FlutterInspect-v0.3.1-manager.apk`: Updated Manager App with single-function Dart Arg & Return inspector, hoisted console state, and interactive package explorer.
- `FlutterInspect-v0.3.1-zygisk.zip`: Zygisk module with Dart VM snapshot package keyword expansion and cluster walker.
- `screen_arg_inspector_live.png`: Device screenshot showing 1,194 live intercepted calls with decoded registers, stack slots, and return values.
- `screen_packages_dialog.png`: Device screenshot showing the interactive Snapshot Discovered Packages explorer.

---

## 16. Current Device State for Next Session

- **Device**: OnePlus 7 (`GM1901`), Android 15 (API 35) at `192.168.1.9:5555`
- **Installed Manager App**: `com.payatu.flutterinspect` (updated with Dart Arg Inspector & Package Explorer)
- **Active Zygisk Module**: `def905231683e45e7b47f44d8e933592` installed via Magisk (`/data/adb/modules/flutterlens/`)
- **Frida Injector**: `17.6.2` at `/data/local/tmp/frida-inject`
- **Target App**: `co.ostorlab.insecure_app` (with 16 functions discovered for `insecure_commands.dart`)


