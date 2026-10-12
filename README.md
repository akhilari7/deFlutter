<p align="center">
  <img src="docs/deflutter_logo.svg" width="160" alt="deFlutter Logo">
</p>

<h1 align="center">deFlutter</h1>

<p align="center">
  <strong>The Dual-Engine Dart AOT Runtime Inspector & Dynamic Tracer for Android</strong>
</p>

<p align="center">
  <a href="https://github.com/akhilari7/deFlutter"><img src="https://img.shields.io/badge/Platform-Android%2010--15-brightgreen.svg" alt="Platform"></a>
  <a href="https://github.com/akhilari7/deFlutter"><img src="https://img.shields.io/badge/Arch-arm64--v8a%20%7C%20x86__64-blue.svg" alt="Arch"></a>
  <a href="https://github.com/akhilari7/deFlutter"><img src="https://img.shields.io/badge/Engine-Frida%20%2B%20Dobby-orange.svg" alt="Engines"></a>
  <a href="https://github.com/akhilari7/deFlutter/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-purple.svg" alt="License"></a>
</p>

---

## 🔍 What is deFlutter?

Flutter Ahead-of-Time (AOT) compiles Dart applications into raw, stripped ARM64 machine code (`libapp.so`). There are no JVM method tables, standard symbols are completely stripped, and traditional dynamic instrumentation tools struggle against runtime ASLR, custom object pools, and Dart's non-standard register-based calling convention.

**deFlutter** is a Flutter reverse-engineering tool that bridges static Dart AOT analysis with dynamic runtime analysis.

Building on the groundwork laid by [Blutter](https://github.com/worawit/blutter), which made Dart AOT decompilation and symbol reconstruction possible, **deFlutter** takes Blutter's static analysis output into the live runtime-enabling Dart function analysis, deep tracing, and experimental runtime tampering.

The name **deFlutter** is a homage to [`reFlutter`](https://github.com/Impact-I/reFlutter), one of the early tools that helped shape Flutter reverse engineering and is still relevant.

---

## 🛠️ Architecture

```
                          ┌─────────────────────────────────────────┐
                          │    deFlutter Web Companion Terminal     │
                          │   (Live Trace & Tamper Lab @ :8088)     │
                          └────────────────────┬────────────────────┘
                                               │ WebSocket / REST
                                               ▼
┌─────────────────────────┐  ADB / REST  ┌─────────────────────────────────────────┐
│  Host Bridge (Python)   │ ───────────► │      deFlutter Android Manager App      │
│   - Docker / Blutter    │              │     (Jetpack Compose + Ktor Server)     │
│   - Symbol Extractor    │              └─────────┬───────────────────────────┬───┘
└─────────────────────────┘                        │                           │
                                        Root ptrace│                           │Root IPC / Config
                                                   ▼                           ▼
                                        ┌─────────────────────┐   ┌─────────────────────────┐
                                        │    Frida Engine     │   │ deFlutter Zygisk Module │
                                        │   (Standalone JS)   │   │     (Native Dobby)      │
                                        └──────────┬──────────┘   └────────────┬────────────┘
                                                   │                           │
                                                   ▼                           ▼
                                        ┌───────────────────────────────────────────────────┐
                                        │            Target Flutter App Process             │
                                        │          (arm64-v8a / x86_64 libapp.so)           │
                                        └───────────────────────────────────────────────────┘
```

---

## 🚀 Key Features

* ⚡ **Dual Hooking Engines**:
  * **Frida Engine (100% Standalone)**: Dynamic userland tracing powered by on-device `frida-inject` with 1-tap automated binary installation. Works out-of-the-box on any rooted device, **no Magisk module or reboot required!**
  * **Native Zygisk + Dobby Engine**: In-process zygote-stage trampoline hooks via the **deFlutter Zygisk module** (Magisk, KernelSU, or APatch) with Capstone disassembly, LR detours, live ASLR discovery, and live `.rodata` memory scanning (optional).
* 🐳 **Automated Blutter Pipeline & Symbol Integration**: Pull target libraries (`libapp.so` & `libflutter.so`) via ADB, decompile with Blutter inside an isolated Docker container, extract semantic symbols (`symbols.json`), and automatically rebase and synchronize them with the deFlutter app and web companion.
* 📦 **Dart VM Snapshot Introspection** *(Requires Zygisk Module)*: Parses the runtime Dart AOT snapshot directly from memory at startup without symbols—recovering SDK hashes, VM compiler flags, heap clusters, and thousands of discovered package/library URIs.
* 🎯 **One-Click Function Interception**: Intercept any function offset or Blutter symbol directly from the mobile UI or Web Companion.
* 🧬 **Semantic Argument & CID Decoding**: Automatically decodes `String`, `int`, `Uint8List`, and Dart heap objects across registers (`X0`–`X7`) and stack slots.
* 🧪 **Web Companion Terminal & Trace/Tamper Lab (Experimental)**: Sleek browser console (`http://localhost:8088`) with real-time WebSocket log streaming, live symbol search, one-click hooking, and interactive in-place memory tampering with instant hot-reload.

---

## 📋 Prerequisites

* **Android Device**: Rooted Android 10–15 (`arm64-v8a` or `x86_64`).
* **Root Access**: 
  * *For Frida Mode (Live Tracing & Tampering)*: Standard root access (`su`). No Magisk module or reboot required.
  * *For Zygisk Mode (Dobby Hooks & Dart VM Snapshot)*: Magisk (with Zygisk), KernelSU (with Zygisk Next), or APatch.
* **Workstation**:
  * Python 3.8+ (for host bridge scripts).
  * Docker Desktop (optional, for automated Blutter decompilation).

---

## 📦 Installation & Setup

Follow this recommended workflow for analyzing and instrumenting any target Flutter app:

### Step 1: Install the deFlutter Android Manager App
Install the manager APK on your rooted device:
```sh
adb install -r deFlutter-<version>-manager.apk
```
> *Note: Download the latest APK asset from [Releases](https://github.com/akhilari7/deFlutter/releases) and specify the exact filename corresponding to the version you downloaded (e.g. `deFlutter-v2.0.0-manager.apk`).*

Launch **deFlutter** on the device and grant root permissions when prompted.

---

### Step 2: Choose & Configure Your Engine

deFlutter supports two deployment workflows depending on your analysis requirements:

| Capability | Dual-Engine Mode *(Recommended)*<br>*(Zygisk Module + Frida)* | Frida Standalone Mode<br>*(Faster Setup)* |
| :--- | :---: | :---: |
| **Setup Overhead** | Flash ZIP & Reboot (Magisk / KSU / APatch) | 1-Tap in App (No Reboot Required) |
| **Dart VM Snapshot & Package Discovery** | ✅ Full in-memory heap walk & SDK hash | ❌ Not Available *(Requires Zygisk)* |
| **Dobby Native Inline Hooking** | ✅ Early zygote-level injection | ❌ Not Available *(Requires Zygisk)* |
| **Live Function Tracing & Arg Decoding** | ✅ Supported (Dual-Engine / Frida) | ✅ Supported (Frida Engine) |
| **Interactive Tamper Lab & Hot Reload** | ✅ Supported (Frida Engine) | ✅ Supported (Frida Engine) |

---

#### Primary Method: Dual-Engine Setup via Zygisk Module (Recommended)
Installing the companion Zygisk module unlocks full **Dual-Engine Synergy**, combining low-level C++ zygote hooks with Frida runtime scripting:
* **Dart VM Snapshot Introspection**: Walks the in-memory Dart VM isolate heap during early zygote initialization to retrieve `kFullAOT` snapshot headers, verify SDK commit hashes, and discover all registered `package:` URIs.
* **Dobby Native Inline Hooking**: Intercepts native functions at zygote startup before application runtime code begins executing.
* **Frida Tracing & Tamper Lab**: Once the Zygisk module is installed, you can also leverage Frida via the Manager App's Config tab for hot-reloading hooks and in-memory tampering.

1. Flash the prebuilt module zip in Magisk, KernelSU, or APatch:
   ```sh
   adb push deFlutter-<version>-zygisk.zip /data/local/tmp/
   adb shell su -c "magisk --install-module /data/local/tmp/deFlutter-<version>-zygisk.zip"
   ```
   > *Note: Download the latest module ZIP from [Releases](https://github.com/akhilari7/deFlutter/releases) and substitute `<version>` with your downloaded version (e.g. `deFlutter-v2.0.0-zygisk.zip`).*
2. **Reboot the device**.

---

#### Alternative Option: Frida Standalone Mode (Fast Setup — No Reboot)
If you want to start analyzing immediately without flashing modules or rebooting your device:
1. In the **deFlutter** app, navigate to the **Config** tab.
2. Under **Frida Engine**, tap **Install Frida** to automatically download and configure `frida-inject` on-device.
3. You are ready to trace and tamper immediately!

> [!WARNING]
> **Trade-off in Frida Standalone Mode**:
> In standalone mode, live function tracing, decoded argument logging, and the interactive Trace/Tamper Lab work completely out of the box. However, low-level **Dart VM Snapshot Introspection** is not available without early zygote injection. The **Dart VM Snapshot** tab in both the Manager App and Web UI will display an amber notice indicating that the Zygisk module is required for snapshot walking.

---

### Step 3: Run the Host Companion Bridge (Automated Symbol Extraction & Sync)
Connect your device via USB/ADB and run the host bridge on your PC to automatically pull the target app's APK, decompile it with Blutter in Docker, extract symbols, forward ports, and sync directly into deFlutter:

```sh
python scripts/host_bridge.py --pkg <target_package_name>
```

> [!NOTE]
> **Works in Both Modes**:
> The host bridge script extracts `libapp.so` and `libflutter.so` directly from the installed APK via ADB, completely independent of the Zygisk module. Even in Frida-only standalone mode, running the bridge will populate all symbols, class hierarchies, and function offsets for tracing.

> **Tip**: If you already have decompiled symbols, you can synchronize them directly:
> ```sh
> python scripts/host_bridge.py --symbols path/to/symbols.json --pkg <target_package_name>
> ```

> [!NOTE]
> **Manual Workflow (Without Host Bridge)**:
> If you prefer not to use the automated host bridge script:
> 1. Pull target binaries (`libapp.so`, `libflutter.so`) via ADB: `adb shell pm path <pkg>` and `adb pull <path>`.
> 2. Run Blutter decompilation manually to generate your symbol mapping (`symbols.json`).
> 3. Push symbols to the device: `adb push symbols.json /data/local/tmp/deflutter/symbols.json` (or use the **Upload Symbols** button in the Web Companion / Manager App).
> 4. Forward the companion port manually: `adb forward tcp:8088 tcp:8088`.

---

### Step 4: Open the Web Companion Terminal & Trace/Tamper Lab

The bridge script automatically configures port forwarding. Open your workstation browser at:
```
http://localhost:8088
```
*(Or forward manually anytime via `adb forward tcp:8088 tcp:8088`)*

From the Web Companion, you can:
* **Live Terminal**: View live function hit logs with clean decoded semantic arguments.
* **Symbols & Functions**: Search across thousands of symbols with server-side pagination and dispatch 1-click hooks.
* **Trace/Tamper Lab**: Customize Frida handlers, test in-place Dart string modifications, and hot-reload hooks live.
* **Dart VM Snapshot**: Inspect VM flags, isolate metadata, and discovered packages.

---

## 🎬 Usage & Demo Walkthrough

Watch live Dart AOT function tracing and dynamic in-memory tampering in action:

### 1. Live Function Tracing & Semantic Argument Decoding
<video src="https://github.com/user-attachments/assets/fa52bf23-6d60-4a4b-94e4-6901a79af753" controls="controls" width="100%"></video>

### 2. Live Memory Tampering & CID Guarding
<video src="https://github.com/user-attachments/assets/fa903a44-01db-4a78-bd62-0cdb2e27edf6" controls="controls" width="100%"></video>

> [!TIP]
> **GUI-Driven Workflow & Standalone Script Export**:
> * **100% GUI-Based Operation**: Once deployed, deFlutter is completely GUI-driven. All real-time function tracing, decoded argument viewing, symbol searching, and live tampering can be executed seamlessly from the Web Companion (`http://localhost:8088`) without requiring manual terminal commands.
> * **Trace & Tamper Lab Presets**: The Web Companion currently provides an experiment feature with basic presets to modify Dart String objects (direct arguments and nested instance fields), manipulate register Smi integers, bypass return values, and apply Class ID (`CID`) guards.
> * **Extensible Beyond deFlutter**: You are not restricted to the Web Companion! Every time you configure or inspect a function, deFlutter automatically generates complete, standalone Frida scripts in the handler editor. You can copy these tailored scripts directly into your external Frida setup, CLI workflows, or custom tooling to extend your analysis outside of deFlutter.

---

## 📁 Repository Structure

```
deFlutter/
├── app/                      # Android Manager App (Jetpack Compose, Ktor Embedded Server)
│   ├── src/main/assets/
│   │   ├── web/              # Web Companion frontend (HTML5/CSS3/WebSocket SPA)
│   │   └── scripts/          # Bundled workstation scripts (blutter2symbols.py)
│   └── src/main/java/com/akhilari/deflutter/
│       ├── data/             # Models, RootShell IPC, SymbolManager, FridaInstaller
│       ├── server/           # Ktor embedded server (REST API & Trace Log Broadcaster)
│       └── ui/               # Jetpack Compose UI (screens, components, themes)
├── module/                   # deFlutter Zygisk module (C++20 & Dobby Hook)
│   ├── src/main/cpp/
│   │   ├── main.cpp          # Zygisk lifecycle entry point & dlopen interception
│   │   ├── dart_tracer.cpp   # Dobby inline hooking, ABI argument decoding & call tracing
│   │   ├── snapshot_walker.cpp # Dart VM AOT isolate snapshot cluster walker & metadata parser
│   │   ├── rodata_scan.cpp   # .rodata string scanner & Capstone disassembly xrefs
│   │   ├── dart_resolver.cpp # Semantic Dart type and CID resolution
│   │   └── result_writer.cpp # In-memory enumeration & snapshot JSON serializer
│   └── template/             # Magisk module packaging template (module.prop, customize.sh)
├── bridge/                   # Dockerized Blutter decompilation engine
│   ├── Dockerfile            # Container image definition (Ubuntu + Clang + Blutter)
│   ├── entrypoint.py         # Container entrypoint runner
│   └── blutter2symbols.py    # Blutter ASM symbol extractor & JSON serializer
├── scripts/                  # Workstation companion automation toolkit
│   ├── host_bridge.py        # Automated ADB pull, Docker runner, & symbol sync
│   └── blutter2symbols.py    # Standalone Blutter ASM to symbols converter
├── docs/                     # Documentation, specifications, and SVG vector logos
├── dist/                     # Release packages & flashable module zips
├── gradle/                   # Gradle wrapper and build tooling
├── LICENSE                   # Apache License 2.0
└── README.md                 # Project documentation
```

---

## 🔨 Building from Source

### 1. Clone Repository
```sh
git clone --recursive https://github.com/akhilari7/deFlutter.git
cd deFlutter
```

### 2. Build Android App
```sh
./gradlew :app:assembleDebug
```

### 3. Build Native Zygisk Module
```sh
./gradlew :module:packageMagiskZip
```

---

## 📄 License

This project is licensed under the **Apache License 2.0**. See the [LICENSE](LICENSE) file for details.
