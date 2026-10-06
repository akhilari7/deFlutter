/**
 * FlutterInspect - Insecure Commands Argument Tracing Script
 * 
 * Specifically targets the compiled routines of insecure_commands.dart
 * and extracts/decodes arguments (strings, integers, objects) passed via ARM64 registers,
 * Stack Pointer (SP), and Frame Pointer (FP).
 */

const TARGET_MODULE = "libapp.so";

function logMsg(msg) {
    try {
        send(msg);
    } catch (_) {}
    console.log(msg);
}

// Offsets discovered by FlutterInspect for 'command' matching insecure_commands.dart
const COMMAND_TARGETS = [
    { name: "insecure_commands #0", offset: 0x2b8af0 },
    { name: "insecure_commands #1", offset: 0x2b8db0 },
    { name: "insecure_commands #2", offset: 0x2b9274 },
    { name: "insecure_commands #3", offset: 0x2b98e8 },
    { name: "insecure_commands #4", offset: 0x2b9c48 },
    { name: "insecure_commands #5", offset: 0x2b9ef4 },
    { name: "insecure_commands #6", offset: 0x2ba39c },
    { name: "secure_commands   #7", offset: 0x2ba4e0 }
];

function tryDecodeDartString(ptrVal) {
    if (!ptrVal || ptrVal.isNull()) return null;
    // Dart HeapObject has LSB = 1
    if (ptrVal.and(1).isNull()) return null;

    const untagged = ptrVal.sub(1);
    try {
        // Offset 8 is length (Smi tagged)
        const lenTagged = untagged.add(8).readPointer();
        let len = -1;
        if (lenTagged.and(1).isNull()) {
            len = lenTagged.shr(1).toInt32();
        } else {
            len = lenTagged.toInt32();
        }

        if (len >= 0 && len <= 4096) {
            const readSize = Math.min(len, 256);
            if (readSize === 0) return '""';
            const buf = untagged.add(16).readByteArray(readSize);
            if (buf) {
                const bytes = new Uint8Array(buf);
                let ascii = "";
                let printable = true;
                for (let i = 0; i < bytes.length; i++) {
                    const c = bytes[i];
                    if (c >= 32 && c <= 126) {
                        ascii += String.fromCharCode(c);
                    } else if (c === 10 || c === 13 || c === 9) {
                        ascii += (c === 10 ? "\\n" : (c === 13 ? "\\r" : "\\t"));
                    } else {
                        printable = false;
                        break;
                    }
                }
                if (printable) {
                    return len > readSize ? `"${ascii}"... [len=${len}]` : `"${ascii}"`;
                }
            }
        }
    } catch (_) {}
    return null;
}

function describeDartValue(val) {
    if (!val || val.isNull()) return "null";

    // Check Smi (bit 0 == 0)
    if (val.and(1).isNull()) {
        const smiVal = val.shr(1).toString();
        return `Smi(${smiVal})`;
    }

    // Try String
    const str = tryDecodeDartString(val);
    if (str !== null) {
        return `String: ${str}`;
    }

    // Check HeapObject fields
    const untagged = val.sub(1);
    try {
        const header = untagged.readU32();
        let desc = `HeapObject(addr=${untagged}, cid=0x${(header >> 12).toString(16)})`;

        // Check if any shallow fields contain strings
        const fieldStrList = [];
        for (let off = 8; off <= 48; off += 8) {
            try {
                const fld = untagged.add(off).readPointer();
                const fldStr = tryDecodeDartString(fld);
                if (fldStr !== null) {
                    fieldStrList.push(`+${off}: ${fldStr}`);
                }
            } catch (_) {}
        }
        if (fieldStrList.length > 0) {
            desc += ` [Fields -> ${fieldStrList.join(", ")}]`;
        }
        return desc;
    } catch (e) {
        return `RawPtr(${val})`;
    }
}

function hookCommandFunctions() {
    const mod = Process.findModuleByName(TARGET_MODULE);
    if (!mod) return false;

    logMsg(`[+] libapp.so located at base: ${mod.base}`);

    COMMAND_TARGETS.forEach(target => {
        const addr = mod.base.add(target.offset);
        try {
            Interceptor.attach(addr, {
                onEnter(args) {
                    const ctx = this.context;
                    logMsg(`\n======================================================`);
                    logMsg(`[*** INSECURE COMMAND HOOK TRIGGERED ***]`);
                    logMsg(`Target  : ${target.name} @ ${addr} (offset 0x${target.offset.toString(16)})`);
                    logMsg(`LR (ret): ${ctx.lr}`);

                    // Inspect Registers
                    logMsg(`Registers:`);
                    logMsg(`  x0: ${describeDartValue(ctx.x0)}`);
                    logMsg(`  x1: ${describeDartValue(ctx.x1)}`);
                    logMsg(`  x2: ${describeDartValue(ctx.x2)}`);
                    logMsg(`  x3: ${describeDartValue(ctx.x3)}`);

                    // Inspect Frame Pointer (Caller's passed arguments in Dart AOT)
                    logMsg(`Frame Pointer [FP / x29] Args:`);
                    for (let off = 0x10; off <= 0x38; off += 8) {
                        try {
                            const val = ctx.fp.add(off).readPointer();
                            logMsg(`  [FP + 0x${off.toString(16)}]: ${describeDartValue(val)}`);
                        } catch (_) {}
                    }

                    // Inspect Stack Pointer [SP]
                    logMsg(`Stack Pointer [SP] Top Slots:`);
                    for (let off = 0; off <= 0x20; off += 8) {
                        try {
                            const val = ctx.sp.add(off).readPointer();
                            logMsg(`  [SP + 0x${off.toString(16)}]: ${describeDartValue(val)}`);
                        } catch (_) {}
                    }

                    logMsg(`======================================================`);
                }
            });
            logMsg(`[+] Hooked ${target.name} (offset 0x${target.offset.toString(16)})`);
        } catch (e) {
            logMsg(`[-] Failed to hook ${target.name}: ${e.message}`);
        }
    });

    logMsg("\n[*] Ready! Trigger the command execution feature in the Insecure App to observe arguments.");
    return true;
}

if (!hookCommandFunctions()) {
    const interval = setInterval(() => {
        if (hookCommandFunctions()) clearInterval(interval);
    }, 500);
}
