/**
 * FlutterInspect - Sample Generic Function Hooking Script
 * 
 * Demonstrates how to hook functions in Flutter's compiled AOT engine (libapp.so)
 * using relative offsets identified by FlutterInspect.
 */

const TARGET_MODULE = "libapp.so";

// Replace these offsets with the ones resolved in flutterlens.json
const HOOK_TARGETS = [
    { name: "insecure_commands_entry_0", offset: 0x2b8af0 },
    { name: "insecure_commands_entry_1", offset: 0x2b8db0 },
    { name: "insecure_commands_entry_2", offset: 0x2b9274 },
    { name: "insecure_commands_entry_3", offset: 0x2b98e8 },
    { name: "insecure_commands_entry_4", offset: 0x2b9c48 },
    { name: "insecure_commands_entry_5", offset: 0x2b9ef4 },
    { name: "insecure_commands_entry_6", offset: 0x2ba39c },
    { name: "secure_commands_entry_0",   offset: 0x2ba4e0 }
];

function setupHooks() {
    const mod = Process.findModuleByName(TARGET_MODULE);
    if (!mod) {
        console.log(`[-] ${TARGET_MODULE} not loaded yet. Waiting...`);
        return false;
    }

    console.log(`[+] Found ${TARGET_MODULE} @ ${mod.base}`);

    HOOK_TARGETS.forEach(target => {
        const targetAddr = mod.base.add(target.offset);
        try {
            Interceptor.attach(targetAddr, {
                onEnter(args) {
                    console.log(`\n[+] >>> Hook Hit: ${target.name}`);
                    console.log(`    Address : ${targetAddr} (${TARGET_MODULE} + 0x${target.offset.toString(16)})`);
                    console.log(`    Thread  : ${Process.getCurrentThreadId()}`);
                    console.log(`    LR (ret): ${this.context.lr}`);
                },
                onLeave(retval) {
                    console.log(`[-] <<< Return from: ${target.name} (retval: ${retval})`);
                }
            });
            console.log(`[+] Installed hook on ${target.name} (0x${target.offset.toString(16)})`);
        } catch (err) {
            console.error(`[-] Failed to hook ${target.name}: ${err.message}`);
        }
    });

    console.log("[*] All hooks configured.");
    return true;
}

if (!setupHooks()) {
    const timer = setInterval(() => {
        if (setupHooks()) {
            clearInterval(timer);
        }
    }, 500);
}
