<p align="center">
  <img src="docs/deflutter_logo.svg" width="160" alt="deFlutter Logo">
</p>

<h1 align="center">deFlutter</h1>

<p align="center">
  <strong>The Dual-Engine Dart AOT Runtime Dissector & Dynamic Tracer for Android</strong>
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

Building on the groundwork laid by [Blutter](https://github.com/worawit/blutter), which made Dart AOT decompilation and symbol reconstruction possible, **deFlutter** takes Blutter's static analysis output into the live runtime—enabling Dart function analysis, deep tracing, and experimental runtime tampering.

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
  * **Frida Engine (100% Standalone)**: Dynamic userland tracing powered by on-device `frida-inject` with 1-tap automated binary installation. Works out-of-the-box on any rooted device—**no Magisk module or reboot required!**
  * **Native Zygisk + Dobby Engine**: In-process zygote-stage trampoline hooks via the **deFlutter Zygisk module** (Magisk, KernelSU, or APatch) with Capstone disassembly, LR detours, live ASLR discovery, and live `.rodata` memory scanning (optional).
* 🐳 **Automated Blutter Pipeline & Symbol Integration**: Pull target libraries (`libapp.so` & `libflutter.so`) via ADB, decompile with Blutter inside an isolated Docker container, extract semantic symbols (`symbols.json`), and automatically rebase and synchronize them with the deFlutter app and web companion.
* 📦 **Dart VM Snapshot Introspection**: Parses the runtime Dart AOT snapshot directly from memory without symbols—recovering SDK hashes, VM compiler flags, heap clusters, and thousands of discovered package/library URIs.
* 🎯 **One-Click Function Interception**: Intercept any function offset or Blutter symbol directly from the mobile UI or Web Companion.
* 🧬 **Semantic Argument & CID Decoding**: Automatically decodes `String`, `int`, `Uint8List`, and Dart heap objects across registers (`X0`–`X7`) and stack slots.
* 🧪 **Web Companion Terminal & Trace/Tamper Lab (Experimental)**: Sleek browser console (`http://localhost:8088`) with real-time WebSocket log streaming, live symbol search, one-click hooking, and interactive in-place memory tampering with instant hot-reload.

---

## 📋 Prerequisites

* **Android Device**: Rooted Android 10–15 (`arm64-v8a` or `x86_64`).
* **Root Access**: 
  * *For Frida Mode*: Standard root access (`su`).
  * *For Dobby Mode*: Magisk (with Zygisk), KernelSU (with Zygisk Next), or APatch.
* **Workstation**:
  * Python 3.8+ (for host bridge scripts).
  * Docker Desktop (optional, for automated Blutter decompilation).

---

## 📦 Installation & Setup

Follow this recommended workflow for analyzing and instrumenting any target Flutter app:

### Step 1: Install the deFlutter Android Manager App
Install the manager APK on your rooted device:
```sh
adb install -r deFlutter-v2.0.0-manager.apk
```
Launch **deFlutter** on the device and grant root permissions when prompted.

---

### Step 2: Choose & Configure Your Tracing Engine

#### Option A: Frida Engine (Recommended for Instant Setup — No Reboot Required)
1. In the **deFlutter** app, navigate to the **Config** tab.
2. Under **Frida Engine**, tap **Install Frida** to automatically download and configure `frida-inject` on-device.
3. You are ready to trace and tamper immediately!

#### Option B: deFlutter Zygisk Module (For Dobby Inline Hooks & Live Discovery)
1. Flash the prebuilt module zip in Magisk, KernelSU, or APatch:
   ```sh
   adb push deFlutter-v2.0.0-zygisk.zip /data/local/tmp/
   adb shell su -c "magisk --install-module /data/local/tmp/deFlutter-v2.0.0-zygisk.zip"
   ```
2. **Reboot the device**.

---

### Step 3: Run the Host Companion Bridge (Automated Symbol Extraction & Sync)
Connect your device via USB/ADB and run the host bridge on your PC to automatically pull the target app's APK, decompile it with Blutter in Docker, extract symbols, forward ports, and sync directly into deFlutter:

```sh
python scripts/host_bridge.py --pkg <target_package_name>
```

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

## 📁 Repository Structure

```
deFlutter/
├── app/                  # Android Manager App (Jetpack Compose, Ktor Web Server)
│   ├── src/main/assets/web/  # Web Terminal frontend (HTML/CSS/WebSocket)
│   └── src/main/java/        # UI screens, SymbolManager, FridaInstaller, RootShell IPC
├── module/               # deFlutter Zygisk module (C++20)
│   ├── src/main/cpp/
│   │   ├── main.cpp          # Zygisk lifecycle & dlopen interception
│   │   ├── scanner.cpp       # .rodata parser & Capstone disassembler
│   │   ├── snapshot.cpp      # Dart VM AOT snapshot deserializer
│   │   └── dobby_hook.cpp    # Dobby inline hooking & Dart ABI tracer
│   └── template/             # Magisk module packaging files
├── scripts/              # Workstation automation toolkit
│   ├── host_bridge.py        # Automated ADB pull, Docker runner, & symbol sync
│   └── blutter2symbols.py    # Standalone Blutter ASM to symbols converter
├── docs/                 # Documentation, specifications, and SVG vector logos
├── dist/                 # Release packages & module zips
├── gradle/               # Gradle wrapper and build tooling
├── LICENSE               # Apache License 2.0
└── README.md             # Project documentation
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
