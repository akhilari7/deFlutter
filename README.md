<p align="center">
  <img src="docs/deflutter_logo.svg" width="160" alt="deFlutter Logo">
</p>

<h1 align="center">deFlutter ⚡</h1>

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

As a homage to `reFlutter`, **deFlutter** pierces the opaque veil of compiled Flutter binaries with a state-of-the-art **dual-engine architecture**:

1. **Frida Engine (100% Standalone)**: Dynamic userland tracing powered by on-device `frida-inject` with 1-tap automated binary installation. Works out-of-the-box on any rooted device—**no Magisk module or reboot required!**
2. **Native Zygisk + Dobby Engine**: In-process zygote-stage trampoline hooks via Magisk, KernelSU, or APatch for sub-millisecond native execution and live `.rodata` memory scanning.
3. **Blutter Symbol Integration**: Direct import of exported Blutter analysis symbols (`dump.json`), automatically rebased against live memory at runtime.
4. **Dart VM Snapshot Introspection**: Parses the runtime Dart AOT snapshot directly from memory, recovering SDK hashes, VM compiler flags, heap clusters, and thousands of internal package/library URIs.
5. **Interactive Web Terminal & Host Bridge**: Sleek browser console (`http://localhost:8088`) with real-time WebSocket log streaming, live symbol search, one-click hooking, and 1-click `libapp.so` binary dumping.

---

## 🛠️ Architecture

```
                          ┌─────────────────────────────────────────┐
                          │          deFlutter Web Terminal         │
                          │          http://localhost:8088          │
                          └────────────────────┬────────────────────┘
                                               │ WebSocket / REST
                                               ▼
┌─────────────────────────┐  ADB / REST  ┌─────────────────────────────────────────┐
│  Host Bridge (Python)   │ ───────────► │       deFlutter Android Companion       │
│   - Docker / Blutter    │              │     (Jetpack Compose + Ktor Server)     │
│   - Symbol Extractor    │              └────────────┬──────────────────┬─────────┘
└─────────────────────────┘                           │                  │
                                           Root ptrace│                  │Root IPC / Config
                                                      ▼                  ▼
                                        ┌───────────────────┐  ┌───────────────────┐
                                        │  Frida Engine     │  │   Zygisk Module   │
                                        │  (Standalone JS)  │  │   (Native Dobby)  │
                                        └─────────┬─────────┘  └─────────┬─────────┘
                                                  │                      │
                                                  ▼                      ▼
                                        ┌──────────────────────────────────────────┐
                                        │        Target Flutter App Process        │
                                        │        (arm64-v8a / x86_64 libapp.so)    │
                                        └──────────────────────────────────────────┘
```

---

## 🚀 Key Features

* ⚡ **Dual Hooking Engines**:
  * **Frida Mode**: Standalone dynamic instrumentation without flashing modules or rebooting. Features automatic 1-tap on-device Frida setup.
  * **Dobby Mode**: In-process native inline hooking with Capstone disassembly and ASLR discovery.
* 📦 **Deep VM Snapshot Analysis**: Dumps and visualizes Dart VM Snapshot details (Magic, Kind, SDK Hash, Features, Heap Objects, Base Objects, Isolate addresses, and discovered packages).
* 🎯 **One-Click Function Interception**: Intercept any function offset or Blutter symbol directly from the mobile UI or Web Terminal.
* 🧬 **Semantic Argument & CID Decoding**: Automatically decodes `String`, `int`, `Uint8List`, and Dart heap objects across registers (`X0`–`X7`) and stack slots.
* 🌐 **Real-Time Web Terminal**: Stream clean or verbose live trace logs directly to your workstation browser over WebSockets (`adb forward tcp:8088 tcp:8088`).
* 🐳 **Dockerized Blutter Pipeline**: Pull `libapp.so` via ADB, decompile with Blutter inside an isolated Docker container, and synchronize symbols with the device.

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

### 1. Install Companion Android App (Always Required)
Install the manager APK on your device:
```sh
adb install -r deFlutter-v2.0.0-manager.apk
```

### 2. Choose Your Tracing Engine

#### Option A: Frida Engine (No Reboot Required)
1. Open **deFlutter** on your device.
2. In the **Config** tab under **Frida-Inject Engine**, tap **Install / Update Frida**.
3. You are ready to trace!

#### Option B: Zygisk Native Module (For Dobby Inline Hooks & Live Discovery)
Flash the prebuilt module zip in Magisk / KernelSU / APatch and reboot:
```sh
adb push deFlutter-v2.0.0-zygisk.zip /data/local/tmp/
adb shell su -c "magisk --install-module /data/local/tmp/deFlutter-v2.0.0-zygisk.zip"
```

---

## ⚡ Web Terminal & Desktop Inspector

Forward the companion port to your workstation:
```sh
adb forward tcp:8088 tcp:8088
```
Open **`http://localhost:8088`** in your browser to access the live trace terminal, symbol browser, and 1-click `libapp.so` extractor.

---

## 📁 Repository Structure

```
deFlutter/
├── app/                  # Android Companion App (Jetpack Compose, Ktor Web Server)
│   ├── src/main/assets/web/  # Web Terminal frontend (HTML/CSS/WebSocket)
│   └── src/main/java/        # UI screens, SymbolManager, FridaInstaller, RootShell IPC
├── module/               # Native Zygisk module (C++20)
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
