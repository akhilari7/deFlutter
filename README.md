# Deflutter ⚡
> **The Dynamic Dart AOT Runtime Dissector & Execution Tracer for Android**

[![Platform](https://img.shields.io/badge/Platform-Android%2010--15-brightgreen.svg)]()
[![Arch](https://img.shields.io/badge/Arch-arm64--v8a%20%7C%20x86__64-blue.svg)]()
[![Injection](https://img.shields.io/badge/Injection-Zygisk%20(Magisk%20%2F%20KernelSU%20%2F%20APatch)-orange.svg)]()
[![Hooking](https://img.shields.io/badge/Hooking-Dobby%20%2B%20Capstone-purple.svg)]()

---

## 🔍 What is Deflutter?

Flutter Ahead-of-Time (AOT) compiles Dart applications into raw, stripped ARM64 machine code (`libapp.so`). There are no JVM method tables, standard symbols are completely omitted, and traditional dynamic instrumentation tools struggle against runtime ASLR, custom object pools, and Dart's non-standard register-based calling convention.

**Deflutter** pierces the opaque veil of compiled Flutter binaries:
1. **Zygisk In-Process Injection**: Injects into target apps at the zygote stage (Magisk, KernelSU, APatch) before Flutter initializes.
2. **Live ASLR Disassembly**: Scans `.rodata` for embedded string literals and disassembles cross-references using **Capstone** to extract live, ASLR-corrected function offsets—completely on-device without cables or offline tools.
3. **Dart VM Snapshot Introspection**: Parses the runtime Dart AOT snapshot directly from memory, recovering SDK hashes, VM compiler flags, heap clusters, and thousands of internal package/library URIs.
4. **Native Dobby Inline Hooking**: Intercepts Dart functions at runtime, automatically decoding Dart calling convention arguments, String instances, Class IDs (CIDs), and return values.
5. **Interactive Web Terminal & Host Bridge**: Provides a sleek browser console (`http://localhost:8088`) with real-time WebSocket log streaming, live symbols search, one-click hooking, and automated Blutter Docker decompilation.

---

## 🛠️ Architecture

```
                          ┌─────────────────────────────────────┐
                          │         Deflutter Web Terminal      │
                          │        http://localhost:8088        │
                          └──────────────┬──────────────────────┘
                                         │ WebSocket / REST
                                         ▼
┌──────────────────────┐  ADB / REST     ┌─────────────────────────────────────┐
│ Host Bridge (Python) │ ──────────────► │    Deflutter Android Companion      │
│  - Docker / Blutter  │                 │    (Jetpack Compose + Ktor Server)  │
│  - Symbol Extractor  │                 └──────────────┬──────────────────────┘
└──────────────────────┘                                │ Root IPC / Config
                                                        ▼
                                         ┌─────────────────────────────────────┐
                                         │       Target Flutter App Process    │
                                         │  ┌────────────────────────────────┐ │
                                         │  │ Deflutter Zygisk Module (Native│ │
                                         │  │  - Capstone Disassembler       │ │
                                         │  │  - Dobby Inline Hooking        │ │
                                         │  │  - Dart VM Snapshot Parser     │ │
                                         │  └────────────────────────────────┘ │
                                         └─────────────────────────────────────┘
```

---

## 🚀 Key Features

* ⚡ **Live ASLR Offset Resolution**: Extracts live function offsets from `.rodata` and `.text` on-device with zero offline preparation.
* 📦 **Deep VM Snapshot Analysis**: Dumps and visualizes Dart VM Snapshot details (Magic, Kind, SDK Hash, Features, Heap Objects, Base Objects, Isolate addresses, and 3,000+ discovered packages).
* 🎯 **One-Click Dobby Hooking**: Intercepts any function offset directly from the mobile UI or Web Terminal without crashing the process.
* 🧬 **Semantic Argument & CID Decoding**: Automatically decodes `String`, `int`, `Uint8List`, and Dart heap objects across registers (`X0`–`X7`) and stack slots.
* 🌐 **Real-Time Web Terminal**: Stream clean or verbose live trace logs directly to your workstation browser over WebSockets.
* 🐳 **Dockerized Blutter Pipeline**: Pull `libapp.so` via ADB, decompile with Blutter inside an isolated Docker container, and synchronize 140,000+ functions to the device with one command.

---

## 📋 Prerequisites

* **Android Device**: Rooted Android 10–15 (`arm64-v8a` or `x86_64`).
* **Root Solution**: Magisk (with Zygisk enabled), KernelSU (with Zygisk Next / NeoZygisk), or APatch.
* **Workstation**:
  * Python 3.6+ (zero external pip packages required for companion scripts).
  * Docker Desktop (optional, for automated Blutter decompilation).
  * Android NDK r27+ (only required if building native module from source).

---

## 📦 Installation & Setup

### 1. Install Native Zygisk Module
Flash the prebuilt module zip in Magisk / KernelSU / MMRL and reboot:
```sh
# Push and install via root shell (or flash in Magisk app):
adb push dist/FlutterInspect.zip /data/local/tmp/deflutter.zip
adb shell su -c "magisk --install-module /data/local/tmp/deflutter.zip"
```

### 2. Install Companion Android App
Install the manager APK on your device:
```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Forward Web Terminal Port
Access the Web Terminal from your workstation browser:
```sh
adb forward tcp:8088 tcp:8088
```
Open **`http://localhost:8088`** in your browser to access the live trace terminal, symbol browser, and snapshot inspector.

---

## ⚡ Usage Workflows

### Workflow A: Zero-Config On-Device Discovery (Instant)
1. Open the **Deflutter** app on your phone.
2. Select your target app from the installed apps dropdown (e.g. `com.eightksec.droidpass`).
3. Tap **Restart App & Scan**.
4. Deflutter injects at startup, parses the Dart snapshot, and immediately populates:
   - **Functions & Offsets**: All functions referencing matched strings.
   - **Dart VM Snapshot**: Magic, SDK Hash, flags, and discovered libraries.
   - **Trace Lab**: Real-time live argument logger.

### Workflow B: Automated Host Bridge & Decompilation (Super Apps)
For large or obfuscated applications (e.g. 140,000+ symbols):

```sh
# One-command full pipeline:
# 1. Pulls libapp.so & libflutter.so via ADB
# 2. Runs Blutter inside Docker container
# 3. Generates structured symbols.json
# 4. Uploads symbols to the device over port 8088
python scripts/host_bridge.py --pkg com.tatadigital.tcp
```

If you already have a `symbols.json` from a previous run:
```sh
python scripts/host_bridge.py --pkg com.tatadigital.tcp --symbols path/to/symbols.json
```

---

## 📁 Repository Structure

```
Deflutter/
├── app/                  # Android Companion App (Jetpack Compose, Ktor Web Server)
│   ├── src/main/assets/web/  # Web Terminal frontend (HTML/CSS/WebSocket)
│   └── src/main/java/        # UI screens, SymbolManager, RootShell IPC
├── module/               # Native Zygisk module (C++20)
│   ├── src/main/cpp/
│   │   ├── main.cpp          # Zygisk lifecycle & dlopen interception
│   │   ├── scanner.cpp       # .rodata parser & Capstone disassembler
│   │   ├── snapshot.cpp      # Dart VM AOT snapshot deserializer
│   │   └── dobby_hook.cpp    # Dobby inline hooking & Dart ABI tracer
├── scripts/              # Workstation automation toolkit
│   ├── host_bridge.py        # Automated ADB pull, Docker runner, & symbol sync
│   ├── blutter2symbols.py    # Standalone Blutter ASM to symbols converter
│   └── Dockerfile.bridge     # Isolated Linux environment for Blutter
├── dist/                 # Prebuilt release packages & module zips
├── gradle/               # Gradle wrapper and build tooling
└── README.md             # Project documentation
```

---

## 🔨 Building from Source

### 1. Clone Repository
```sh
git clone --recursive https://github.com/YourUsername/Deflutter.git
cd Deflutter
```

### 2. Build Android App
```sh
./gradlew :app:assembleDebug
```

### 3. Build Native Zygisk Module
```sh
./gradlew :module:assembleRelease
```

---

## ⚖️ License & Disclaimer

Deflutter is developed for security research, penetration testing, and reverse engineering education. Ensure you have proper authorization before testing target applications.

Developed with ⚡ by the Deflutter team.
