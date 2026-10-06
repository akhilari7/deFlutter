// FlutterInspect demonstration Frida hook script for keyword 'command'
console.log("[*] FlutterInspect Frida Hook Script loaded (Keyword: command)");

const TARGET_MODULE = "libapp.so";

// Offsets dynamically resolved by FlutterInspect for 'command'
const TARGET_HOOKS = [
    { name: "insecure_commands.dart", offset: 0x2b9c48 },
    { name: "cure_commands.dart", offset: 0x2b9ef4 },
    { name: "ateCommand", offset: 0x3d5740 }
];

function hookDartFunctions() {
    const mod = Process.findModuleByName(TARGET_MODULE);
    if (!mod) {
        console.log(`[-] Waiting for ${TARGET_MODULE} to be loaded...`);
        return false;
    }

    console.log(`[+] Found ${TARGET_MODULE} at base address: ${mod.base}`);

    TARGET_HOOKS.forEach(target => {
        const targetAddr = mod.base.add(target.offset);
        try {
            Interceptor.attach(targetAddr, {
                onEnter(args) {
                    console.log(`\n[*** HOOK TRIGGERED ***] Function: ${target.name}`);
                    console.log(`    Address: ${targetAddr} (libapp.so + 0x${target.offset.toString(16)})`);
                    console.log(`    Thread ID: ${Process.getCurrentThreadId()}`);
                }
            });
            console.log(`[+] Attached hook: ${target.name} @ ${targetAddr} (offset 0x${target.offset.toString(16)})`);
        } catch (e) {
            console.error(`[-] Failed to hook ${target.name}: ${e.message}`);
        }
    });

    console.log("\n[*] All hooks successfully attached! Ready for triggers.");
    return true;
}

if (!hookDartFunctions()) {
    const interval = setInterval(() => {
        if (hookDartFunctions()) {
            clearInterval(interval);
        }
    }, 500);
}
