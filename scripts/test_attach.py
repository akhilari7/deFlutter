import frida
import time

d = frida.get_device_manager().add_remote_device("127.0.0.1:27042")
session = d.attach("Insecure_App")
code = """
console.log("[+] Frida attached successfully! PID: " + Process.id);
const m = Process.findModuleByName("libapp.so");
if (m) {
    console.log("[+] libapp.so base: " + m.base);
} else {
    console.log("[-] libapp.so not found");
}
"""
def on_msg(msg, data):
    print("FRIDA MSG:", msg)

script = session.create_script(code)
script.on("message", on_msg)
script.load()
time.sleep(2)
session.detach()
print("[+] Test completed successfully!")
