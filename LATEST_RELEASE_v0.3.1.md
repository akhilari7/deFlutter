# FlutterInspect v0.3.1: Release & Progress Summary

**Generated:** 2026-09-29  
**Target Architecture:** Android ARM64-v8a  
**Verified Test Environment:** OnePlus 7 (`GM1901`), Android 15 (API 35), Magisk 30.6 + Zygisk Next 1.3.3  
**Target Application:** `co.ostorlab.insecure_app` (`libapp.so`)

---

## 1. Executive Summary

Version **v0.3.1** represents a milestone transition for FlutterInspect from a static/Zygisk-only Dart inspection tool into a **fully self-contained, on-device dynamic instrumentation platform**. 

Key highlights of this release:
1. **Zero-Laptop Dynamic Frida Hooking:** Analysts can select discovered Dart functions and run dynamic hooks directly inside the Android Manager App without needing a computer, USB tether, or `frida-server` daemon.
2. **Precision Dart AOT Argument & Return Value Tracing ("frida-trace for Dart"):** Decodes ARM64 registers (`x0..x3`), Dart Stack slots (`x15`), Caller Frame slots (`x29`), Smis, OneByteStrings, and return values (`x0` / `retval`) with microsecond invocation timing.
3. **Dart VM Snapshot Package Integration:** Merges serialized snapshot metadata (651 discovered package URIs) with the Code Object Table resolver and adds an interactive in-app package explorer.
4. **Keyword Search Engine Hardening:** Solved schema mismatches, cache reload fallback issues, and stale default keyword bleed-through.
5. **App Lifecycle & State Resiliency:** Hoisted interceptor state and configured `singleTask` launch mode so switching between FlutterInspect and target applications never drops hooks or clears logs.

---

## 2. Core Features & Architectural Implementation

### A. Dynamic In-App Frida Interceptor (Phase 2 Point 2)
- **Standalone Injector Binary:** Embedded `/data/local/tmp/frida-inject` (v17.6.2, ARM64 static executable, mode `755`). Runs completely offline without open TCP sockets or network permissions.
- **Root Process Streaming (`RootShell.kt`):**
  - Hook scripts are staged to `/data/local/tmp/flutterlens_hook.js` via piped stdin (`cat << 'EOF'`), eliminating shell escaping and argument length limits.
  - Spawns the target app via `su -c "/data/local/tmp/frida-inject -f <pkg> -s <script>"`, bypassing Android 15 kernel `ptrace pokedata: I/O error` restrictions on running processes.
  - Asynchronously streams `stdout` line-by-line into Compose state.
  - Clean detachment on `stop()` with coroutine cancellation and `pkill -9 frida-inject`.
- **Frida Studio UI (`FridaStudioScreen.kt`):**
  - **Dual Sub-Tabs:** `<> Script Editor` (syntax preview and template picker) and `>_ Live Console` (real-time terminal).
  - **Auto-scrolling Color Terminal:**
    - `EmeraldSuccess` (`[+]`) for module base attachments and hit notifications.
    - `RoseError` (`[-]`) for exceptions and errors.
    - `CyanPrimary` (`0x...`) for virtual addresses and offsets.
  - **Bottom Action Button:** Context-aware `▶ RUN HOOK (IN-APP)` (Cyan) / `■ STOP INTERCEPTOR` (Rose Red).

---

### B. Dart AOT Argument & Return Value Inspector
- **Variable Shadowing Fix:** Replaced `ptr` parameter naming with `ptrVal` in `FridaSnippetGenerator.kt` to prevent shadowing Frida's global `ptr()` constructor.
- **Universal Dart Type Decoder:**
  - **Smi (Small Integers):** `LSB == 0` -> unshifts `ptrVal.shr(1)` to display numeric values.
  - **OneByteString:** `LSB == 1` -> reads Smi length at `+0x08`, then safely extracts ASCII/UTF-8 character buffer at `+0x10`.
  - **HeapObject:** Extracts Class ID (`cid = header >> 12`) and shallow-scans fields (`+0x08` to `+0x20`) for nested string references.
- **ARM64 Register & Frame Mapping:**
  - `x0`: Arg 0 / Receiver (`this` instance).
  - `x1`: Arg 1 (first method parameter).
  - `x2`: Arg 2 (second method parameter).
  - `x3`: Arg 3 (context closure / third parameter).
  - `x15` (Dart Stack Pointer): Top 4 stack slots (`[x15 + 0x0]`, `[x15 + 0x8]`, `[x15 + 0x10]`, `[x15 + 0x18]`).
  - `x29` (Caller Frame Pointer): Caller slots (`[FP + 0x10]`, `[FP + 0x18]`, `[FP + 0x20]`).
- **`onLeave` Return Value Capture:**
  - Captures `this.context.x0` (the Dart return register) and formats it through the type decoder.
  - Displays invocation duration (`elapsed = Date.now() - startTime`).

---

### C. Dart VM Snapshot Package Discovery & Keyword Integration
- **Dual-Layer Keyword Expansion (`module/src/main/cpp/main.cpp`):**
  - When `walk_dart_snapshot()` extracts package URIs, incoming user keywords are matched against the 651 package identifiers.
  - If a keyword matches any package URI (e.g. `"command"` or `"insecure"` matches `package:ostorlab_insecure_flutter_app/bugs/insecure_commands.dart`), the base file name (`insecure_commands.dart`) is automatically appended to the active search keyword list.
  - The Code Object Table then resolves all functions matching either the raw keyword or the matched package name.
- **Interactive Package Explorer (`ResultsScreen.kt`):**
  - The snapshot card header button (`📦 651 Snapshot Packages Discovered`) opens an interactive dialog.
  - Each package item features a Cyan filter icon. Tapping any package immediately closes the dialog, sets `searchQuery`, and filters the functions list to that specific package.

---

### D. Keyword Search Engine & Bleed-Through Fixes
- **JSON Schema Alignment:** Synchronized `target_process` (string) and `target_packages` (array) across `InspectConfig.kt` and `module_config.cpp`.
- **Clean Keyword Defaults:** Set `keywords = emptyList()` in `InspectConfig.kt` constructor so unconfigured keywords never default to stale hardcoded values (`auth`, `http`, etc.).
- **3-Tier Config Fallback in C++:**
  1. `/data/data/<pkg>/cache/flutterlens_config.json` (direct app sandbox)
  2. `/data/adb/flutterlens/config.json` (Magisk root staging)
  3. `/data/local/tmp/flutterlens_config.json` (SELinux permissive global fallback)

---

### E. App State Hoisting & Task Switching Resiliency
- **SingleTask Launch Mode:** Added `android:launchMode="singleTask"` to `AndroidManifest.xml` so switching back from the target app never resets the Activity.
- **Parent State Hoisting:** Hoisted `fridaSession`, `isFridaHookRunning`, and `fridaConsoleLogs` to `MainScreen.kt`. Switching between `Config`, `Functions`, and `Frida Lab` tabs no longer stops the interceptor or clears logs.
- **Active Hook Navigation Badge:** The `Frida Lab` bottom navigation tab dynamically displays an emerald badge counter with real-time log counts while an interceptor is running.

---

## 3. Live Verification Results

Verified live on OnePlus 7 (`GM1901`, Android 15, API 35) on local Wi-Fi:

| Test Case | Configuration | Result | Notes |
| :--- | :--- | :--- | :--- |
| **Keyword Search** | Keyword: `"exec"` | **144 functions** discovered | Zero bleed-through of old keywords; verified in `insecure_app`. |
| **Batch Hook** | 16 functions (`insecure_commands.dart`) | **443 live hits** streamed | Base address resolved at `0x75a50d3000`; attached to all 16 offsets. |
| **Dart Arg Inspector** | Single function: `0x2b8af0` (`insecure_commands.dart`) | **1,194 live calls** captured | Successfully decoded `x0..x3`, `x15` slots, `x29` frame, and `onLeave` `x0` return values. |
| **Snapshot Packages** | 651 packages extracted | **100% matched** | Tapped `insecure_commands.dart` in explorer -> filtered to 16 functions instantly. |

---

## 4. Release Artifacts (v0.3.1)

All compiled artifacts and test captures are located in `dist/release-v0.3.1/`:

| Artifact | Size | Description |
| :--- | :--- | :--- |
| `FlutterInspect-v0.3.1-manager.apk` | ~45.8 MB | Manager App with in-app Frida injector, Dart Arg Inspector, and Package Explorer. |
| `FlutterInspect-v0.3.1-zygisk.zip` | ~1.5 MB | Flashable Magisk/Zygisk module with Dart VM cluster walker & snapshot package keyword matching. |
| `screen_arg_inspector_live.png` | 247 KB | Screenshot showing 1,194 live calls with decoded arguments and return values. |
| `screen_packages_dialog.png` | 200 KB | Screenshot showing the interactive Snapshot Discovered Packages explorer. |
| `screen_frida_live_intercepted_hits.png`| 250 KB | Screenshot showing 443 batch hits streamed in-app. |

---

## 5. Next Steps for Future Sessions

1. **Custom Filter Rules & Arg Modifiers:** Allow editing argument values on-the-fly (`args[0] = ...`) from the in-app UI.
2. **Log Export:** Add one-tap export of console sessions to `.txt` or `.json` for offline report generation.
3. **Expanded Class ID Resolver:** Map extracted Dart Snapshot Class IDs back to user-friendly Dart class names.
