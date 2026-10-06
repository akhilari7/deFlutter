/**
 * FlutterInspect - Sample Dart Argument Inspector Script
 * 
 * Demonstrates how to decode Dart AOT runtime argument types (Smi, String, bool, HeapObject)
 * passed across registers (x0, x1, x2, ...) on ARM64 architecture.
 */

const TARGET_MODULE = "libapp.so";

/**
 * Decodes a 64-bit Dart tagged value.
 * In Dart 64-bit AOT:
 *   - LSB (bit 0) == 0: Small Integer (Smi). Value = pointer >> 1.
 *   - LSB (bit 0) == 1: Heap Object Pointer. Untagged address = pointer - 1.
 */
function inspectDartValue(ptrVal) {
    if (!ptrVal || ptrVal.isNull()) {
        return { type: "Null", value: "null", raw: ptrVal };
    }

    // Check if Smi (bit 0 == 0)
    if (ptrVal.and(1).isNull()) {
        const smiVal = ptrVal.shr(1).toString();
        return {
            type: "Smi (Integer)",
            value: smiVal,
            raw: ptrVal
        };
    }

    // HeapObject (bit 0 == 1)
    const untagged = ptrVal.sub(1);

    try {
        // Read object header word (Class ID & flags)
        const header = untagged.readU32();

        // 1. Try decoding as Dart OneByteString:
        //    Offset +8 : String length (stored as a tagged Smi!)
        //    Offset +16: Start of characters (ASCII/UTF-8 bytes)
        try {
            const lengthTagged = untagged.add(8).readPointer();
            let len = -1;
            if (lengthTagged.and(1).isNull()) {
                len = lengthTagged.shr(1).toInt32();
            } else {
                len = lengthTagged.toInt32();
            }
            if (len >= 0 && len <= 8192) {
                    const sampleLen = Math.min(len, 256);
                    if (sampleLen === 0) {
                        return { type: "String", value: '""', length: 0, raw: ptrVal };
                    }
                    const buf = untagged.add(16).readByteArray(sampleLen);
                    if (buf) {
                        const u8 = new Uint8Array(buf);
                        let isPrintable = true;
                        let text = "";
                        for (let i = 0; i < u8.length; i++) {
                            const b = u8[i];
                            if (b >= 32 && b <= 126) {
                                text += String.fromCharCode(b);
                            } else if (b === 10 || b === 13 || b === 9) {
                                text += (b === 10 ? "\\n" : (b === 13 ? "\\r" : "\\t"));
                            } else {
                                isPrintable = false;
                                break;
                            }
                        }
                        if (isPrintable) {
                            const display = len > sampleLen ? `"${text}"... (truncated)` : `"${text}"`;
                            return {
                                type: "String",
                                value: display,
                                length: len,
                                raw: ptrVal
                            };
                        }
                    }
                }
            }
        } catch (_) {}

        // 2. Try checking boolean singletons or other common types
        // In Dart, booleans are heap objects with specific class headers or static addresses
        return {
            type: "HeapObject",
            value: `HeapObject @ ${untagged} (header=0x${header.toString(16)})`,
            raw: ptrVal
        };
    } catch (e) {
        return {
            type: "InvalidPointer",
            value: `Unreadable ptr ${ptrVal} (${e.message})`,
            raw: ptrVal
        };
    }
}

/**
 * Format argument inspection results for logging
 */
function formatDartArg(label, regVal) {
    const inspected = inspectDartValue(regVal);
    if (inspected.type === "String") {
        return `${label}: [String (len=${inspected.length})] ${inspected.value}`;
    } else if (inspected.type.startsWith("Smi")) {
        return `${label}: [Integer] ${inspected.value}`;
    } else {
        return `${label}: [${inspected.type}] ${inspected.value}`;
    }
}

// Example hook configuration for target function
const SAMPLE_TARGET_OFFSET = 0x2b9c48; // e.g. insecure_commands routine

function hookSampleFunction() {
    const mod = Process.findModuleByName(TARGET_MODULE);
    if (!mod) return false;

    const targetAddr = mod.base.add(SAMPLE_TARGET_OFFSET);
    console.log(`[+] Attaching Argument Inspector to ${targetAddr} (offset 0x${SAMPLE_TARGET_OFFSET.toString(16)})...`);

    Interceptor.attach(targetAddr, {
        onEnter(args) {
            console.log(`\n================== [FUNCTION CALL] ==================`);
            console.log(`Address: ${targetAddr} (${TARGET_MODULE} + 0x${SAMPLE_TARGET_OFFSET.toString(16)})`);
            
            // On ARM64 Dart AOT:
            // x0: Receiver (this) or Arg 0
            // x1: Arg 1
            // x2: Arg 2
            // x3: Arg 3
            console.log("Arguments in Registers:");
            console.log("  " + formatDartArg("x0 (Receiver/Arg0)", this.context.x0));
            console.log("  " + formatDartArg("x1 (Arg1)         ", this.context.x1));
            console.log("  " + formatDartArg("x2 (Arg2)         ", this.context.x2));
            console.log("  " + formatDartArg("x3 (Arg3)         ", this.context.x3));
            console.log(`====================================================`);
        }
    });

    return true;
}

if (!hookSampleFunction()) {
    const interval = setInterval(() => {
        if (hookSampleFunction()) clearInterval(interval);
    }, 500);
}
