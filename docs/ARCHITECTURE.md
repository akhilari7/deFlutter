# FlutterInspect — Architecture & Technical Reference

_by Akshay Khilari (based on the Zygisk native patterns established in FlutterTap by Eduardo Lopes)_

FlutterInspect resolves Flutter/Dart function addresses and base-relative offsets dynamically at runtime inside the target app process. It eliminates the need for offline disassembly tools (like `blutter`) and brittle Frida scripts tethered to hardcoded addresses.

---

## 1. High-Level Concept

In Flutter AOT compiled binaries (`libapp.so` or `libflutter.so`), user classes, methods, stubs, and reflection metadata preserve symbol strings inside the non-executable, read-only data segment (`.rodata`). 

Code in `.text` accesses these strings via PC-relative addressing:
- **arm64:** `adrp` (load 4KB page) followed by `add` (low 12-bit immediate offset)
- **x86_64:** `lea reg, [rip + disp]`

FlutterInspect reverses this relationship at runtime:
```
1. Target App Launched (Zygote fork)
   └── Zygisk preAppSpecialize: Match package name from config.json.
       If NOT targeted: DLCLOSE_MODULE_LIBRARY (instant unload, zero overhead).
       If TARGETED: Proceed.

2. Monitoring Thread Spawned (postAppSpecialize)
   └── Polls dl_iterate_phdr waiting for libapp.so (or libflutter.so).

3. ELF Parsing & Segment Mapping
   └── Parses ELF headers (PT_LOAD) to identify .text and .rodata boundaries.

4. String Scanning (rodata_scan.cpp)
   ├── Mode 1 (keyword_search): Scans .rodata for requested string literals.
   └── Mode 2 (full_enumerate): Scans .rodata for Dart identifier patterns (ClassName.method, get:foo).

5. Capstone Xref Resolution & Prologue Backtracing (dart_resolver.cpp)
   ├── Disassembles .text with Capstone.
   ├── Identifies adrp+add or lea referencing the string literal.
   └── Backtraces to function prologue (stp x29, x30, [sp, #-N]! or push rbp)
       to determine the actual function entrypoint.

6. Result Emission (result_writer.cpp)
   ├── Emits structured logs to logcat (`adb logcat -s FlutterInspect:V`).
   └── Writes JSON payload to `/data/local/tmp/flutterlens_<pkg>.json`.
```

---

## 2. In-Depth Component Breakdown

### A. Process Isolation & Zygisk Next Compatibility
- **Signal Guard Safety:** The module registers a `SIGSEGV`/`SIGBUS` crash guard around memory scans using `sigsetjmp`/`siglongjmp` (`mem_scan.cpp`). This prevents invalid pointer dereferences from crashing the host app.
- **Zygote Preload Isolation:** Under Zygisk Next Linker, `onLoad()` is executed in Zygote before `fork()`. Any global signal handlers installed there would leak into system processes. Hence, the crash guard is strictly activated *after* process specialization in the target app context.
- **Thread-Specific Storage:** To prevent dynamic-TLS relocations (`R_AARCH64_TLSDESC` / `R_X86_64_DTPMOD64`) which fail under custom ELF loaders, all thread context uses POSIX thread-specific keys (`pthread_getspecific` / `pthread_setspecific`).

### B. ELF Segment Parsing (`elf_utils.cpp`)
Instead of reading `/proc/self/maps`, FlutterInspect queries the linker via `dl_iterate_phdr`. This ensures that even when APKs are built with `android:extractNativeLibs="false"` (where `.so` files are mapped directly from within `base.apk!/lib/...`), the virtual load base and program headers (`Elf64_Phdr`) are accurately mapped.

Both `.text` (`PT_LOAD` + `PF_X`) and `.rodata` (`PT_LOAD` + `PF_R` without `PF_X` or `PF_W`) are parsed.

### C. Pattern & String Matching (`rodata_scan.cpp`)
- **Keyword Search:** Scans memory using byte comparison with string boundary validation (walking back to null terminators).
- **Dart Identifier Extraction:** Employs heuristics to detect Dart symbols:
  - Pattern: `ClassName.methodName`
  - Getter/Setter: `get:property`, `set:property`
  - Closures / Tear-offs: `Closure of ...`, `ClassName@...`
  - Ignores: URLs (`http://`, `https://`), file paths (`/data/...`), hex dumps, BoringSSL error strings.

### D. Capstone Xref & Prologue Backtracing (`dart_resolver.cpp`)

#### arm64 Disassembly Engine:
1. Translates string runtime address into its 4KB page: `page = addr & ~0xFFF`.
2. Scans `.text` for `adrp` instructions (`(word & 0x9F000000) == 0x90000000`).
3. Uses Capstone to decode `adrp` destination register and target page.
4. Checks the subsequent instruction for `add <reg>, <adrpReg>, #<imm12>`.
5. Verifies if `(page + imm12) == string_addr`.
6. Once an xref instruction is confirmed, steps backwards (up to 512 instructions) looking for the standard function prologue:
   - `stp x29, x30, [sp, #-N]!`
   - `sub sp, sp, #N`

#### x86_64 Disassembly Engine:
1. Steps byte-by-byte through `.text` disassembling single instructions.
2. Checks for `lea reg, [rip + disp]` where `(next_insn_addr + disp) == string_addr`.
3. Steps backwards searching for prologue instructions (`push rbp` or `push r15`).

---

## 3. Consuming Results in Frida

The generated JSON file provides:
```json
{
  "name": "LoginBloc.authenticate",
  "abs_addr": "0x70abc56780",
  "offset": "0x44780",
  "xref_addr": "0x70abc56740"
}
```

Since ASLR shifts absolute addresses on every app launch, use the base-relative `offset`:
```javascript
const lib = Process.getModuleByName("libapp.so");
const funcAddress = lib.base.add(0x44780);

Interceptor.attach(funcAddress, {
    onEnter(args) {
        console.log("[+] Hooked LoginBloc.authenticate at", funcAddress);
    },
    onLeave(retval) {
        console.log("[+] Return value:", retval);
    }
});
```
