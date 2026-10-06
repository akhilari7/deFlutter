# Phase 4 Milestones: Web Terminal, Automated Blutter Bridge, VM Snapshot & Deflutter Rebranding

**Document Version**: 1.0  
**Status**: Completed  
**Project**: Deflutter (formerly FlutterInspect)  
**Target Environment**: Android 10–15 (Rooted via Magisk / KernelSU / APatch), Workstation (Windows / Linux / macOS)

---

## 1. Executive Summary

Phase 4 transformed the project from an on-device mobile inspection tool into a comprehensive, hybrid workstation-mobile reverse engineering suite. Key highlights include:
1. **Embedded Web Terminal**: Ktor-powered HTTP & WebSocket server hosted directly inside the Android companion app (`http://localhost:8088`), offering desktop-grade console viewing, real-time log streaming, symbol searching, and one-click Dobby hook dispatch.
2. **Automated Blutter Host Companion Bridge**: Python automation (`host_bridge.py` & `blutter2symbols.py`) with a dedicated Docker container environment that extracts `libapp.so` via ADB, executes Blutter decompilation, converts outputs into structured `symbols.json`, and streams it back to the device.
3. **High-Capacity Symbol Sync**: Optimized the symbols ingestion pipeline to support massive enterprise/super applications (e.g. Tata Neu with 145,000+ functions and 60 MB+ symbols files) via streaming `JsonReader` and chunked upload handling.
4. **Dart VM Snapshot Deep Introspection**: Extracted and visualized the Dart AOT snapshot heap profile, exposing Magic, Kind, SDK Hash, VM flags, Clusters, Objects, Base Objects, Isolate memory addresses, and 3,300+ discovered internal packages across both mobile and web interfaces.
5. **Local Workspace Migration & Rebranding**: Moved the entire project out of cloud sync (OneDrive) to a clean local directory (`C:\FlutterInspect`) and officially rebranded the toolkit to **Deflutter**.

---

## 2. Detailed Milestone Deliverables

### Milestone 4.1: Embedded Web Terminal Server (`:8088`)
* **Architecture**: Embedded Ktor HTTP/WebSocket engine running inside `com.payatu.flutterinspect.server.WebTerminalServer`.
* **Endpoints**:
  * `GET /`: Serves the responsive, cyberpunk-themed Web Terminal single-page application (`index.html`).
  * `GET /ws/trace`: Full-duplex WebSocket streaming real-time call hits, parameter inspections, and returns from the target process.
  * `GET /api/status`: Returns current target package, hook status, symbol count, and class count.
  * `GET /api/apps`: Lists all installed packages on the device, highlighting detected Flutter applications.
  * `GET /api/functions?q=...&limit=...`: Fast query engine searching through loaded Blutter symbols.
  * `GET /api/snapshot?pkg=...`: Returns complete Dart VM Snapshot metadata and discovered library URIs.
  * `POST /api/hooks`: Dispatches one-click Dobby hooks or clears all active hooks.
  * `GET /api/download-libapp`: Streams `libapp.so` directly from the rooted device to the workstation browser.
  * `POST /api/upload-symbols`: Receives `symbols.json` from the browser or host bridge and indexes it into `SymbolManager`.
* **Live Web UI (`app/src/main/assets/web/index.html`)**:
  * Four dedicated navigation panels: **Live Terminal**, **Symbols & Functions**, **Dart VM Snapshot**, and **Toolkit & Workflow**.
  * Integrated Clean vs. Verbose log filtering (hiding redundant hex dumps and raw pointer addresses while retaining high-value strings, parameter names, and Class IDs).

---

### Milestone 4.2: Automated Blutter Host Companion Bridge (`host_bridge.py`)
* **End-to-End Automation Pipeline**:
  1. `check_adb()` verifies device connectivity and target app status.
  2. Pulls `libapp.so` and `libflutter.so` directly from `/data/app/...` using root commands without requiring manual APK extraction.
  3. Spawns Dockerized Blutter (`deflutter/bridge`) or local Blutter to decompile the Dart AOT snapshot into ASM and `blutter_frida.js`.
  4. Runs `blutter2symbols.py` (zero external dependencies, 100% Python standard library) to extract classes, CIDs, field offsets, and function signatures.
  5. Automatically pushes `symbols.json` to the Web Terminal REST API at `http://localhost:8088/api/upload-symbols` or directly via on-device storage.
* **Large Symbol Upload Optimization**:
  * Enhanced `WebTerminalServer.kt` to handle 60 MB+ payloads via streaming `receiveStream()` into a 64KB buffered disk writer.
  * Mitigated HTTP socket timeouts on super apps (145,000+ functions) by offloading indexing to background coroutines and providing fast-path file sync (`/load-symbols`).

---

### Milestone 4.3: Dart VM Snapshot Introspection
* **Engine Introspection**:
  * Exposed runtime snapshot parameters dumped by the Zygisk module into `flutterlens.json`.
  * **Metadata Extracted**:
    * Snapshot Kind: `kFullAOT` / `kIsolate`
    * Magic: `0xdcdcf5f5`
    * Dart SDK Hash (e.g. `78da37fed6bf1489361a312568249f3f`) with one-click copy.
    * Compiler Flags: `product`, `no-code_comments`, `no-dwarf_stack_traces_mode`, `dedup_instructions`, `compressed-pointers`.
    * Metric Counters: Heap Clusters, Total Heap Objects, Base Objects, Code Offset.
    * Memory Addresses: Isolate Data Base & Isolate Instructions Base.
    * Embedded Packages: Interactive searchable list of 3,300+ package paths with one-click filtering.
* **Dual Presentation**:
  * Rendered as an interactive card in the Android Manager app (`ResultsScreen.kt`).
  * Rendered as a dedicated tab panel in the Web Terminal (`index.html`).

---

### Milestone 4.4: Local Migration & OneDrive Decoupling
* **Motivation**: Cloud synchronization software (OneDrive) caused aggressive file-locking, high CPU churn, and build failures when generating tens of thousands of intermediate Gradle and Docker files.
* **Migration**:
  * Safely transferred the entire repository (22,740 files, ~2.89 GB) to `C:\FlutterInspect` using multi-threaded `robocopy /MT:16`.
  * Purged stale build intermediates and verified clean Gradle build (`BUILD SUCCESSFUL in 2m 8s`).
  * Removed old workspace from OneDrive.

---

### Milestone 4.5: Rebranding to Deflutter
* **Identity Rationale**:
  * `Unflutter` was already an established tool (`zboralski/unflutter`).
  * **Deflutter** is completely unique, memorable, and establishes the natural dynamic runtime counterpart to `reFlutter`.
* **Changes Applied**:
  * Android Application Name: Updated to **Deflutter** in `strings.xml`.
  * Top App Bar: Updated to `DEFLUTTER · Dart AOT Runtime Dissector`.
  * Web Terminal UI: Brand updated to `DEFLUTTER · Dart AOT Web Terminal`.
  * Python Scripts: CLI banners, help menus, and error messages updated to `Deflutter Host Companion Bridge`.
  * Documentation: Complete overhaul of `README.md` reflecting Deflutter architecture and workflows.
  * Git Repository: Initialized on branch `main` with 1,726 clean source files committed.

---

## 3. Live Discovery vs. Blutter Mode Switching Logic

| Property | Live On-Device Discovery | Blutter Symbols Mode |
| :--- | :--- | :--- |
| **Data Source** | Native `.rodata` scan & Capstone disassembly (`flutterlens.json`) | Workstation decompiler output (`symbols.json`) |
| **Requirements** | Zero setup (purely on-device, instant) | Workstation PC with Python & Docker |
| **Symbol Scope** | Functions referencing matched strings/keywords (typically 50–500) | Full application symbols & classes (typically 10,000–150,000+) |
| **Semantic Fidelity** | Function offsets, names, and string xrefs | Full class names, methods, CIDs, field offsets, parameter types, register/slot mappings |
| **UI Presentation** | `Discovered (N)` chip in `ResultsScreen.kt` | `Blutter Symbols (M)` chip in `ResultsScreen.kt` |
| **Active Mode Default** | Preferred when no symbols are uploaded | Automatically becomes default (`effectiveBlutterMode = true`) once symbols are loaded |
| **Package Isolation** | Scoped strictly to target app package cache | Scoped strictly to `/sdcard/Download/FlutterInspect/<pkg>/symbols.json`; old symbols cleared on app switch |

---

## 4. Verification Matrix

| Target Application | Test Scenario | Expected Outcome | Status |
| :--- | :--- | :--- | :--- |
| `com.eightksec.droidpass` | Zero-config Live Discovery | 52 functions discovered, 645 snapshot packages detected | **PASSED** |
| `com.eightksec.droidpass` | Blutter Bridge Upload | 8,900+ functions indexed, class hierarchy decoded | **PASSED** |
| `com.tatadigital.tcp` | Super App Blutter Sync | 145,166 functions, 3,396 snapshot packages, 0 timeout errors | **PASSED** |
| Web Terminal (`:8088`) | WebSocket Trace Streaming | Real-time logs streamed with clean parameter labels | **PASSED** |
| Web Terminal (`:8088`) | One-Click Dobby Hooking | Dispatched hook from browser, intercepted live hit in terminal | **PASSED** |
| Mobile App UI | Package Switching | Switching target app automatically isolates snapshot & symbols | **PASSED** |
