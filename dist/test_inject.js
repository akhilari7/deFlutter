console.log("[+] Frida-inject test script loaded inside " + Process.id);
const mod = Process.findModuleByName("libapp.so");
if (mod) {
    console.log("[+] Found libapp.so base: " + mod.base);
} else {
    console.log("[-] libapp.so not found");
}