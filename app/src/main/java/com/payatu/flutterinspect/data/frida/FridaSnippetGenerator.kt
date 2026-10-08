package com.payatu.flutterinspect.data.frida

import com.payatu.flutterinspect.data.models.FunctionEntry

object FridaSnippetGenerator {

    enum class TemplateType(val title: String, val description: String) {
        BASIC_TRACE(
            "Basic Call Tracer",
            "Logs when the target function is invoked with runtime timestamp and ASLR address."
        ),
        DART_ARG_INSPECTOR(
            "Dart AOT Argument Inspector",
            "Universal ARM64 inspector: decodes x15 stack, [FP+0x10], [FP+0x18], Smi, and OneByteString values."
        ),
        MULTI_FUNCTION_BATCH(
            "Batch Multi-Hook",
            "Hooks all filtered functions simultaneously with offset tracking."
        )
    }

    fun generateSnippet(
        templateType: TemplateType,
        targetLib: String,
        targetFunc: FunctionEntry?,
        allFuncs: List<FunctionEntry> = emptyList(),
        isVerbose: Boolean = false,
        traceReturn: Boolean = true
    ): String {
        return when (templateType) {
            TemplateType.BASIC_TRACE -> generateBasicHook(targetLib, targetFunc, isVerbose, traceReturn)
            TemplateType.DART_ARG_INSPECTOR -> generateDartArgInspector(targetLib, targetFunc, isVerbose, traceReturn)
            TemplateType.MULTI_FUNCTION_BATCH -> generateBatchHook(targetLib, allFuncs, isVerbose)
        }
    }

    private fun generateBasicHook(targetLib: String, func: FunctionEntry?, isVerbose: Boolean, traceReturn: Boolean): String {
        val name = func?.name ?: "target_function"
        val offset = func?.offset ?: "0x0"

        val attachLog = if (isVerbose) {
            "console.log(`[+] Attaching to ` + FUNC_NAME + ` at ` + targetAddr + ` (Base: ` + mod.base + `)`);"
        } else {
            "console.log(`[+] Attaching to ` + FUNC_NAME);"
        }

        val onEnterLogs = if (isVerbose) {
            """
                        console.log(`\n[★] Hit ` + FUNC_NAME + ` @ ` + targetAddr);
                        console.log(`    Thread ID: ` + Process.getCurrentThreadId());
                        console.log(`    Return Address: ` + this.returnAddress);
            """.trimIndent()
        } else {
            "console.log(`\\n[★] Hit ` + FUNC_NAME);"
        }

        val onLeaveBlock = if (traceReturn) {
            """
                    onLeave: function(retval) {
                        console.log(`[✓] Left ` + FUNC_NAME + ` -> Retval: ` + retval);
                    }
            """.trimIndent()
        } else {
            "// onLeave skipped (Return tracing disabled)"
        }

        return """
            /**
             * deFlutter Frida Script: Basic Function Tracer
             * Target: $name
             * Module: $targetLib (Offset: $offset)
             * Verbose: $isVerbose
             */
            'use strict';

            const TARGET_MODULE = "$targetLib";
            const TARGET_OFFSET = $offset;
            const FUNC_NAME = "$name";
            const IS_VERBOSE = $isVerbose;

            function attachHook() {
                const mod = Process.findModuleByName(TARGET_MODULE);
                if (!mod) {
                    console.log(`[-] Waiting for module ` + TARGET_MODULE + `...`);
                    return false;
                }
                const targetAddr = mod.base.add(TARGET_OFFSET);
                $attachLog

                Interceptor.attach(targetAddr, {
                    onEnter: function(args) {
                        $onEnterLogs
                    },
                    $onLeaveBlock
                });
                return true;
            }

            if (!attachHook()) {
                const interval = setInterval(() => {
                    if (attachHook()) clearInterval(interval);
                }, 500);
            }
        """.trimIndent()
    }

    private fun generateDartArgInspector(targetLib: String, func: FunctionEntry?, isVerbose: Boolean, traceReturn: Boolean): String {
        val name = func?.name ?: "target_function"
        val offset = func?.offset ?: "0x0"

        return """
            /**
             * deFlutter: Dart ARM64 AOT Runtime Argument & Return Inspector
             * Target: $name ($offset) in $targetLib
             * Verbose: $isVerbose | TraceReturn: $traceReturn
             * 
             * Decodes:
             *  - Registers: x0 (Arg0/Recv), x1 (Arg1), x2 (Arg2), x3 (Arg3), x4 (Arg4)
             *  - Dart Stack (x15) & Caller Frame (x29) slots with compressed pointer resolution
             *  - Types: Smi, OneByteString (ASCII/UTF-8), TwoByteString (UTF-16LE), C-Strings
             *  - Deep Field Traversal (depth 2): extracts nested strings from Controllers, Values, Models
             *  - Dart Return value on exit (x0)
             */
            'use strict';

            const TARGET_MODULE = "$targetLib";
            const TARGET_OFFSET = $offset;
            const FUNC_NAME = "$name";
            const IS_VERBOSE = $isVerbose;

            let heapBase = null;

            function isNullOrZero(p) {
                return !p || p.isNull() || p.toString() === "0x0";
            }

            function getHeapBase(val) {
                if (!heapBase && val && !val.isNull()) {
                    const upper = val.and(ptr("0xffffffff00000000"));
                    if (!upper.isNull()) {
                        heapBase = upper;
                    }
                }
                return heapBase || ptr("0x7700000000");
            }

            function decompress(p) {
                if (isNullOrZero(p)) return p;
                if (p.and(ptr("0xffffffff00000000")).isNull()) {
                    return getHeapBase(p).add(p);
                }
                return p;
            }

            function tryReadPrintableString(targetPtr) {
                if (isNullOrZero(targetPtr)) return null;
                try {
                    const s = targetPtr.readUtf8String();
                    if (s && s.length >= 1 && s.length <= 1024 && /^[\x20-\x7e\t\r\n]+$/.test(s)) {
                        return s;
                    }
                } catch (_) {}
                try {
                    const s = targetPtr.readUtf16String();
                    if (s && s.length >= 1 && s.length <= 512 && /^[\x20-\x7e\t\r\n]+$/.test(s)) {
                        return s + " (utf16)";
                    }
                } catch (_) {}
                return null;
            }

            function tryDecodeDartString(rawVal, depth) {
                if (isNullOrZero(rawVal)) return null;
                if (!depth) depth = 0;

                const ptrVal = decompress(rawVal);
                if (isNullOrZero(ptrVal)) return null;

                // Direct C-string fallback if pointer is untagged (bit 0 == 0)
                if (ptrVal.and(1).isNull()) {
                    try {
                        const cstr = ptrVal.readUtf8String(64);
                        if (cstr && cstr.length >= 2 && /^[\x20-\x7e\t\r\n]+$/.test(cstr)) {
                            return '"' + cstr + '" (C-Str)';
                        }
                    } catch (_) {}
                    return null;
                }

                const untagged = ptrVal.sub(1);
                try {
                    const candidates = [];

                    // Format A: Length at offset +8 (64-bit Dart or 64-bit aligned compressed)
                    try {
                        const lenAt8 = untagged.add(8).readU32();
                        if ((lenAt8 & 1) === 0 && (lenAt8 >> 1) > 0 && (lenAt8 >> 1) <= 2048) {
                            candidates.push({ len: lenAt8 >> 1, dataOff: 16, type: "OneByte" });
                            candidates.push({ len: lenAt8 >> 1, dataOff: 16, type: "TwoByte" });
                        }
                        if (lenAt8 > 0 && lenAt8 <= 2048) {
                            candidates.push({ len: lenAt8, dataOff: 16, type: "OneByte" });
                        }
                    } catch (_) {}

                    // Format B: Length at offset +4 (Compressed 32-bit Dart)
                    try {
                        const lenAt4 = untagged.add(4).readU32();
                        if ((lenAt4 & 1) === 0 && (lenAt4 >> 1) > 0 && (lenAt4 >> 1) <= 2048) {
                            candidates.push({ len: lenAt4 >> 1, dataOff: 12, type: "OneByte" });
                            candidates.push({ len: lenAt4 >> 1, dataOff: 8, type: "OneByte" });
                        }
                        if (lenAt4 > 0 && lenAt4 <= 2048) {
                            candidates.push({ len: lenAt4, dataOff: 12, type: "OneByte" });
                            candidates.push({ len: lenAt4, dataOff: 8, type: "OneByte" });
                        }
                    } catch (_) {}

                    // Format C: Direct payload at offset +8 or +12 or +16 (Uint8List / inline buffer)
                    for (let cOff = 8; cOff <= 16; cOff += 4) {
                        try {
                            const rawBytes = untagged.add(cOff).readByteArray(32);
                            if (rawBytes) {
                                const u8 = new Uint8Array(rawBytes);
                                let s = "";
                                for (let j = 0; j < u8.length; j++) {
                                    const b = u8[j];
                                    if (b === 0) break;
                                    if (b >= 32 && b <= 126) s += String.fromCharCode(b);
                                    else break;
                                }
                                if (s.length >= 4) {
                                    candidates.push({ len: s.length, dataOff: cOff, type: "OneByte" });
                                }
                            }
                        } catch (_) {}
                    }

                    for (let i = 0; i < candidates.length; i++) {
                        const c = candidates[i];
                        if (c.type === "OneByte") {
                            const readLen = Math.min(c.len, 256);
                            const buf = untagged.add(c.dataOff).readByteArray(readLen);
                            if (buf) {
                                const u8 = new Uint8Array(buf);
                                let s = "";
                                let ok = true;
                                for (let j = 0; j < u8.length; j++) {
                                    const b = u8[j];
                                    if (b >= 32 && b <= 126) {
                                        s += String.fromCharCode(b);
                                    } else if (b === 10 || b === 13 || b === 9) {
                                        s += (b === 10 ? "\\n" : (b === 13 ? "\\r" : "\\t"));
                                    } else {
                                        ok = false;
                                        break;
                                    }
                                }
                                if (ok && s.length > 0) {
                                    return c.len > readLen ? '"' + s + '"... [len=' + c.len + ']' : '"' + s + '"';
                                }
                            }
                        } else if (c.type === "TwoByte") {
                            const readLen = Math.min(c.len, 128);
                            const buf = untagged.add(c.dataOff).readByteArray(readLen * 2);
                            if (buf) {
                                const u8 = new Uint8Array(buf);
                                let s = "";
                                let ok = true;
                                for (let j = 0; j < u8.length; j += 2) {
                                    const code = u8[j] | (u8[j+1] << 8);
                                    if (code >= 32 && code <= 126) {
                                        s += String.fromCharCode(code);
                                    } else if (code === 10 || code === 13 || code === 9) {
                                        s += (code === 10 ? "\\n" : (code === 13 ? "\\r" : "\\t"));
                                    } else {
                                        ok = false;
                                        break;
                                    }
                                }
                                if (ok && s.length > 0) {
                                    return c.len > readLen ? '"' + s + '"... (utf16) [len=' + c.len + ']' : '"' + s + '" (utf16)';
                                }
                            }
                        }
                    }
                } catch (_) {}
                return null;
            }

            function bytesToBase64(u8) {
                let binary = "";
                const len = u8.byteLength;
                for (let i = 0; i < len; i++) {
                    binary += String.fromCharCode(u8[i]);
                }
                try {
                    if (typeof btoa === 'function') return btoa(binary);
                } catch (_) {}
                const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
                let res = "";
                for (let i = 0; i < len; i += 3) {
                    const b0 = u8[i], b1 = i + 1 < len ? u8[i + 1] : 0, b2 = i + 2 < len ? u8[i + 2] : 0;
                    res += chars[b0 >> 2];
                    res += chars[((b0 & 3) << 4) | (b1 >> 4)];
                    res += (i + 1 < len) ? chars[((b1 & 15) << 2) | (b2 >> 6)] : "=";
                    res += (i + 2 < len) ? chars[b2 & 63] : "=";
                }
                return res;
            }

            function bytesToHex(u8, maxLen) {
                const l = Math.min(u8.length, maxLen || 12);
                const h = [];
                for (let i = 0; i < l; i++) {
                    h.push(u8[i].toString(16).padStart(2, '0'));
                }
                return h.join(" ") + (u8.length > l ? "..." : "");
            }

            function tryDecodeDartBuffer(rawVal) {
                if (isNullOrZero(rawVal)) return null;
                try {
                    const ptrVal = decompress(rawVal);
                    let untagged;
                    if (ptrVal.and(1).toInt32() === 1) {
                        untagged = ptrVal.sub(1);
                    } else {
                        untagged = ptrVal;
                    }

                    let cid = 0;
                    try {
                        const header = untagged.readU32();
                        cid = (header >> 12) & 0xfffff;
                    } catch (_) {}
                    const cidPrefix = cid > 0 ? "cid=0x" + cid.toString(16) + ", " : "";

                    // 1. Try decoding length-prefixed TypedData / Uint8List
                    for (let lOff = 4; lOff <= 12; lOff += 4) {
                        try {
                            const rawLen = untagged.add(lOff).readU32();
                            let len = -1;
                            if ((rawLen & 1) === 0 && (rawLen >> 1) >= 1 && (rawLen >> 1) <= 2048) {
                                len = rawLen >> 1;
                            } else if (rawLen >= 1 && rawLen <= 2048) {
                                len = rawLen;
                            }
                            if (len > 0) {
                                for (let dOff of [lOff + 4, 8, 12, 16]) {
                                    try {
                                        const readLen = Math.min(len, 64);
                                        const buf = untagged.add(dOff).readByteArray(readLen);
                                        if (buf) {
                                            const u8 = new Uint8Array(buf);
                                            let nonZero = 0;
                                            let printable = "";
                                            for (let j = 0; j < u8.length; j++) {
                                                if (u8[j] !== 0) nonZero++;
                                                if (u8[j] >= 32 && u8[j] <= 126) {
                                                    printable += String.fromCharCode(u8[j]);
                                                }
                                            }
                                            if (nonZero > 0) {
                                                if (printable.length >= 4 && printable.length >= nonZero * 0.8) {
                                                    return "Uint8List(" + cidPrefix + "len=" + len + ") -> \"" + printable + "\"";
                                                }
                                                return "Uint8List(" + cidPrefix + "len=" + len + ")";
                                            }
                                        }
                                    } catch (_) {}
                                }
                            }
                        } catch (_) {}
                    }

                    // 2. Fallback: check if untagged has 16 or 32 raw bytes
                    for (let dOff of [8, 12, 16]) {
                        try {
                            const buf = untagged.add(dOff).readByteArray(16);
                            if (buf) {
                                const u8 = new Uint8Array(buf);
                                let nonZero = 0;
                                for (let j = 0; j < u8.length; j++) {
                                    if (u8[j] !== 0) nonZero++;
                                }
                                if (nonZero >= 12) {
                                    return "Bytes(" + cidPrefix + "len=16)";
                                }
                            }
                        } catch (_) {}
                    }
                } catch (_) {}
                return null;
            }

            function describeDartValue(val, depth) {
                if (isNullOrZero(val)) return { type: 'null', summary: 'null', isNoise: true };
                if (!depth) depth = 0;

                // 1. Smi (Small Integer, bit 0 == 0)
                if (val.and(1).isNull()) {
                    if (val.and(ptr("0xffffffff00000000")).isNull()) {
                        const num = val.toInt32() >> 1;
                        if (Math.abs(num) < 10000000) {
                            return { type: 'smi', summary: num + " (int)", isNoise: false, isString: false, value: num };
                        }
                    }
                    return { type: 'raw', summary: '0x' + val.toString(16), isNoise: true };
                }

                // 2. Direct Dart String
                const str = tryDecodeDartString(val, depth);
                if (str !== null) {
                    return { type: 'string', summary: str, isNoise: false, isString: true, text: str };
                }

                // 2b. Direct Dart Buffer / Uint8List
                const bufStr = tryDecodeDartBuffer(val);
                if (bufStr !== null) {
                    return { type: 'buffer', summary: bufStr, isNoise: false, isString: true, text: bufStr };
                }

                // 3. Dart HeapObject (bit 0 == 1)
                try {
                    const decomp = decompress(val);
                    const untagged = decomp.sub(1);
                    const header = untagged.readU32();
                    const cid = (header >> 12) & 0xfffff;

                    // Null singleton (cid=1 usually in Dart)
                    if (cid === 1) {
                        return { type: 'null', summary: 'null', isNoise: true };
                    }

                    if (depth < 3) {
                        const foundFields = [];
                        const base = getHeapBase(val);

                        // Scan offsets 4..48 (32-bit compressed pointers & Smis)
                        for (let off = 4; off <= 48; off += 4) {
                            try {
                                const fld32 = untagged.add(off).readU32();
                                if (fld32 === 0) continue;

                                // A. Smi field
                                if ((fld32 & 1) === 0) {
                                    const sNum = fld32 >> 1;
                                    if (Math.abs(sNum) > 0 && Math.abs(sNum) < 1000000) {
                                        foundFields.push("fld+0x" + off.toString(16) + "=" + sNum);
                                    }
                                    continue;
                                }

                                // B. Tagged pointer field
                                if ((fld32 & 1) === 1 && fld32 > 0x1000) {
                                    const fldPtr = base.add(fld32);
                                    // String
                                    const fldStr = tryDecodeDartString(fldPtr, depth + 1);
                                    if (fldStr) {
                                        const item = "+0x" + off.toString(16) + ": " + fldStr;
                                        if (!foundFields.includes(item)) foundFields.push(item);
                                        continue;
                                    }
                                    // Buffer / Uint8List
                                    const fldBuf = tryDecodeDartBuffer(fldPtr);
                                    if (fldBuf) {
                                        const item = "+0x" + off.toString(16) + ": " + fldBuf;
                                        if (!foundFields.includes(item)) foundFields.push(item);
                                        continue;
                                    }

                                    // Nested Instance inspection (depth 0 or 1)
                                    if (depth <= 1) {
                                        const nestedUntagged = fldPtr.sub(1);
                                        const nHeader = nestedUntagged.readU32();
                                        const nCid = (nHeader >> 12) & 0xfffff;

                                        // Try buffer on nested object
                                        const nBuf = tryDecodeDartBuffer(fldPtr);
                                        if (nBuf) {
                                            foundFields.push("+0x" + off.toString(16) + ": Instance(cid=0x" + nCid.toString(16) + ") -> " + nBuf);
                                            continue;
                                        }

                                        // Scan nested fields
                                        for (let noff = 4; noff <= 32; noff += 4) {
                                            try {
                                                const nw = nestedUntagged.add(noff).readU32();
                                                if ((nw & 1) === 1 && nw > 0x1000) {
                                                    const nptr = base.add(nw);
                                                    const ns = tryDecodeDartString(nptr, depth + 2);
                                                    if (ns) {
                                                        const nitem = "+0x" + noff.toString(16) + ": " + ns;
                                                        if (!foundFields.includes(nitem)) foundFields.push(nitem);
                                                    }
                                                    const nb = tryDecodeDartBuffer(nptr);
                                                    if (nb) {
                                                        const nitem = "+0x" + noff.toString(16) + ": " + nb;
                                                        if (!foundFields.includes(nitem)) foundFields.push(nitem);
                                                    }
                                                }
                                            } catch (_) {}
                                        }
                                    }
                                }
                            } catch (_) {}
                        }

                        // 64-bit uncompressed pointer scan
                        for (let off = 8; off <= 48; off += 8) {
                            try {
                                const fld64 = untagged.add(off).readPointer();
                                if (fld64.and(1).toInt32() === 1 && fld64.compare(ptr("0x10000")) > 0) {
                                    const fldStr = tryDecodeDartString(fld64, depth + 1);
                                    if (fldStr) {
                                        const item = "+0x" + off.toString(16) + ": " + fldStr;
                                        if (!foundFields.includes(item)) foundFields.push(item);
                                        continue;
                                    }
                                    const fldBuf = tryDecodeDartBuffer(fld64);
                                    if (fldBuf) {
                                        const item = "+0x" + off.toString(16) + ": " + fldBuf;
                                        if (!foundFields.includes(item)) foundFields.push(item);
                                        continue;
                                    }
                                }
                            } catch (_) {}
                        }

                        if (foundFields.length > 0) {
                            foundFields.sort((a, b) => {
                                const aIsStr = a.startsWith('"') || a.includes('-> "');
                                const bIsStr = b.startsWith('"') || b.includes('-> "');
                                if (aIsStr && !bIsStr) return -1;
                                if (!aIsStr && bIsStr) return 1;
                                return 0;
                            });
                            const maxShow = 8;
                            const items = foundFields.slice(0, maxShow);
                            const more = foundFields.length > maxShow ? ", ..." : "";
                            return {
                                type: 'instance',
                                summary: 'Instance(cid=0x' + cid.toString(16) + ') -> ' + (foundFields.length === 1 ? foundFields[0] : '[' + items.join(", ") + more + ']'),
                                isNoise: false,
                                isString: true
                            };
                        }
                    }

                    // If no strings or buffers were recognized, dump the first few raw field words so we see its structure!
                    const rawDumps = [];
                    for (let off = 4; off <= 16; off += 4) {
                        try {
                            const w = untagged.add(off).readU32();
                            rawDumps.push("0x" + w.toString(16));
                        } catch (_) {}
                    }
                    const rawSummary = (IS_VERBOSE && rawDumps.length > 0) ? " [raw: " + rawDumps.join(", ") + "]" : "";

                    if (depth === 0) {
                        return { type: 'instance', summary: 'Instance(cid=0x' + cid.toString(16) + ')' + rawSummary, isNoise: false };
                    }
                    return { type: 'instance', summary: 'Instance(cid=0x' + cid.toString(16) + ')' + rawSummary, isNoise: true };
                } catch (e) {
                    return { type: 'raw', summary: '0x' + val.toString(16), isNoise: true };
                }
            }

            function attachHook() {
                const mod = Process.findModuleByName(TARGET_MODULE);
                if (!mod) {
                    console.log(`[-] Waiting for ` + TARGET_MODULE + `...`);
                    return false;
                }

                const targetAddr = mod.base.add(TARGET_OFFSET);
                if (IS_VERBOSE) {
                    console.log(`[+] Attaching Dart Arg Inspector to ` + FUNC_NAME + ` @ ` + targetAddr + ` (Base: ` + mod.base + `)`);
                } else {
                    console.log(`[+] Attaching Dart Arg Inspector to ` + FUNC_NAME);
                }

                Interceptor.attach(targetAddr, {
                    onEnter: function(args) {
                        const ctx = this.context;
                        this.fnName = FUNC_NAME;
                        this.startTime = Date.now();

                        const regArgs = [];
                        const regs = [
                            { name: "Arg0 (this/recv)", val: ctx.x0 },
                            { name: "Arg1", val: ctx.x1 },
                            { name: "Arg2", val: ctx.x2 },
                            { name: "Arg3", val: ctx.x3 },
                            { name: "Arg4", val: ctx.x4 }
                        ];

                        function formatLabelWithPtr(baseLbl, rawVal) {
                            if (IS_VERBOSE && rawVal && rawVal.and(1).toInt32() === 1 && rawVal.compare(ptr("0x1000")) > 0) {
                                try {
                                    return baseLbl + " (" + decompress(rawVal) + ")";
                                } catch (_) {}
                            }
                            return baseLbl;
                        }

                        for (let i = 0; i < regs.length; i++) {
                            const r = regs[i];
                            const desc = describeDartValue(r.val);
                            const lbl = formatLabelWithPtr(r.name, r.val);
                            if (i === 0) {
                                if (desc && !desc.isNoise) {
                                    regArgs.push({ name: lbl, text: desc.summary });
                                } else if (desc && desc.type === 'instance') {
                                    regArgs.push({ name: lbl, text: desc.summary });
                                }
                            } else {
                                if (desc && !desc.isNoise) {
                                    regArgs.push({ name: lbl, text: desc.summary });
                                }
                            }
                        }

                        // Inspect Dart Stack slots (x15), Context (x27), and Caller Frame slots (FP)
                        const frameSlots = [];
                        const base = getHeapBase(ctx.x0);
                        for (let off = 0; off <= 0x40; off += 8) {
                            try {
                                const val = ctx.x15.add(off).readPointer();
                                const desc = describeDartValue(val);
                                if (desc && !desc.isNoise && (desc.isString || desc.type === 'smi' || desc.type === 'buffer' || desc.type === 'instance')) {
                                    if (!regArgs.some(a => a.text === desc.summary) && !frameSlots.some(a => a.text === desc.summary)) {
                                        frameSlots.push({ name: formatLabelWithPtr(`[Stack+0x` + off.toString(16) + `]`, val), text: desc.summary });
                                    }
                                }
                            } catch (_) {}
                        }

                        // Inspect Dart Context Register: x27 (CTXREG)
                        try {
                            if (ctx.x27 && !isNullOrZero(ctx.x27) && ctx.x27.and(1).toInt32() === 1) {
                                const desc = describeDartValue(ctx.x27);
                                if (desc && !desc.isNoise && (desc.isString || desc.type === 'smi' || desc.type === 'buffer' || desc.type === 'instance')) {
                                    frameSlots.push({ name: formatLabelWithPtr("x27 (Context)", ctx.x27), text: desc.summary });
                                }
                            }
                        } catch (_) {}

                        for (let off = 0x10; off <= 0x40; off += 8) {
                            try {
                                const val = ctx.fp.add(off).readPointer();
                                const desc = describeDartValue(val);
                                if (desc && !desc.isNoise && (desc.isString || desc.type === 'smi' || desc.type === 'buffer' || desc.type === 'instance')) {
                                    if (!regArgs.some(a => a.text === desc.summary) && !frameSlots.some(a => a.text === desc.summary)) {
                                        frameSlots.push({ name: formatLabelWithPtr(`[FP+0x` + off.toString(16) + `]`, val), text: desc.summary });
                                    }
                                }
                            } catch (_) {}
                        }

                        console.log(`\n[★] ⚡ HIT: ` + FUNC_NAME + ` (0x` + TARGET_OFFSET.toString(16) + `)`);
                        if (regArgs.length === 0 && frameSlots.length === 0) {
                            console.log(`    └── (no active arguments or frame data detected)`);
                        } else {
                            if (regArgs.length > 0) {
                                console.log(`    ├── Call Arguments (Registers):`);
                                for (let i = 0; i < regArgs.length; i++) {
                                    const prefix = (i === regArgs.length - 1 && frameSlots.length === 0) ? `    │   └── ` : `    │   ├── `;
                                    console.log(prefix + regArgs[i].name + `: ` + regArgs[i].text);
                                }
                            }
                            if (frameSlots.length > 0) {
                                console.log(`    └── Caller Frame Context (Stack/FP):`);
                                for (let i = 0; i < frameSlots.length; i++) {
                                    const prefix = (i === frameSlots.length - 1) ? `        └── ` : `        ├── `;
                                    console.log(prefix + frameSlots[i].name + `: ` + frameSlots[i].text);
                                }
                            }
                        }
                    },
                    ${if (traceReturn) """
                    onLeave: function(retval) {
                        try {
                            const elapsed = Date.now() - (this.startTime || Date.now());
                            const retValPtr = retval || (this.context ? this.context.x0 : null);
                            const retDesc = describeDartValue(retValPtr);
                            const retText = (retDesc && !retDesc.isNoise) ? retDesc.summary : (retValPtr && !retValPtr.isNull() ? ("0x" + retValPtr.toString(16)) : "null");
                            let ptrSuffix = "";
                            if (IS_VERBOSE && retValPtr && retValPtr.and(1).toInt32() === 1 && retValPtr.compare(ptr("0x1000")) > 0) {
                                try { ptrSuffix = " (" + decompress(retValPtr) + ")"; } catch (_) {}
                            }
                            console.log(`[✓] ==> RETURN: ` + (this.fnName || FUNC_NAME) + ptrSuffix + ` -> ` + retText + ` [` + elapsed + `ms]\n`);
                        } catch (err) {
                            console.log(`[✓] ==> RETURN: ` + (this.fnName || FUNC_NAME) + ` -> (parse error: ` + err + `)\n`);
                        }
                    }
                    """.trimIndent() else "// onLeave skipped (Return tracing disabled)"}
                });
                return true;
            }

            if (!attachHook()) {
                const iv = setInterval(() => {
                    if (attachHook()) clearInterval(iv);
                }, 500);
            }
        """.trimIndent()
    }

    private fun generateBatchHook(targetLib: String, funcs: List<FunctionEntry>, isVerbose: Boolean): String {
        val targetsJson = funcs.take(20).map { f ->
            "    { name: \"${f.name.replace("\"", "\\\"")}\", offset: ${f.offset} }"
        }.joinToString(",\n")

        val attachModuleLog = if (isVerbose) {
            "console.log(`[+] Attached to ` + TARGET_MODULE + ` base: ` + mod.base);"
        } else {
            "console.log(`[+] Attached to ` + TARGET_MODULE);"
        }

        val hitLog = if (isVerbose) {
            "console.log(`[+] HIT: ` + target.name + ` @ ` + targetAddr + ` (offset: ` + ptr(target.offset) + `)`);"
        } else {
            "console.log(`[+] HIT: ` + target.name + ` (` + target.offset + `)`);"
        }

        return """
            /**
             * deFlutter Batch Hook Script
             * Targets: ${funcs.take(20).size} resolved Dart functions in $targetLib (capped to top 20 for stability)
             * Verbose: $isVerbose
             */
            'use strict';

            const TARGET_MODULE = "$targetLib";
            const IS_VERBOSE = $isVerbose;
            const HOOK_TARGETS = [
            $targetsJson
            ];

            function setupBatchHooks() {
                const mod = Process.findModuleByName(TARGET_MODULE);
                if (!mod) {
                    console.log(`[-] Waiting for ` + TARGET_MODULE + `...`);
                    return false;
                }

                $attachModuleLog
                let attachedCount = 0;

                HOOK_TARGETS.forEach(target => {
                    try {
                        const targetAddr = mod.base.add(target.offset);
                        Interceptor.attach(targetAddr, {
                            onEnter: function(args) {
                                $hitLog
                            }
                        });
                        attachedCount++;
                    } catch (e) {
                        console.log(`[-] Failed to hook ` + target.name + `: ` + e.message);
                    }
                });

                console.log(`[+] Successfully hooked ` + attachedCount + ` / ` + HOOK_TARGETS.length + ` functions.`);
                return true;
            }

            if (!setupBatchHooks()) {
                const interval = setInterval(() => {
                    if (setupBatchHooks()) clearInterval(interval);
                }, 500);
            }
        """.trimIndent()
    }
}
