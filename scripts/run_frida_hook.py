import sys
import time
import frida

if sys.stdout.encoding.lower() != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

PACKAGE_NAME = "co.ostorlab.insecure_app"
SCRIPT_FILE = sys.argv[1] if len(sys.argv) > 1 else "scripts/hook_insecure_commands_args.js"
DEVICE_ID = "10.55.55.109:5555"

def on_message(message, data):
    if message['type'] == 'send':
        print(f"[Frida] {message['payload']}", flush=True)
    elif message['type'] == 'error':
        print(f"[Frida ERROR] {message['description']}", file=sys.stderr, flush=True)
        if 'stack' in message:
            print(message['stack'], file=sys.stderr, flush=True)
    elif message['type'] == 'log':
        print(f"[Frida LOG] {message['payload']}", flush=True)
    else:
        print(f"[Frida RAW] {message}", flush=True)

def main():
    print(f"[*] Connecting to Frida device ({DEVICE_ID})...", flush=True)
    mgr = frida.get_device_manager()
    device = None
    for d in mgr.enumerate_devices():
        if d.id == DEVICE_ID:
            device = d
            break
    if not device:
        print(f"[!] Device {DEVICE_ID} not found in frida devices, attempting get_device()...", flush=True)
        device = frida.get_device(DEVICE_ID)

    print(f"[+] Connected to device: {device.name} ({device.id})", flush=True)

    with open(SCRIPT_FILE, "r", encoding="utf-8") as f:
        hook_code = f.read()

    print(f"[*] Attaching to target app...", flush=True)
    session = None
    target_pid = None
    for p in device.enumerate_processes():
        if p.name in ["Insecure_App", "co.ostorlab.insecure_app"]:
            target_pid = p.pid
            print(f"[+] Found process: {p.name} (PID: {p.pid})", flush=True)
            break

    if target_pid:
        session = device.attach(target_pid)
    else:
        print("[!] Target process not currently running. Spawning...", flush=True)
        target_pid = device.spawn(["co.ostorlab.insecure_app"])
        session = device.attach(target_pid)
        device.resume(target_pid)

    script = session.create_script(hook_code)
    script.on("message", on_message)
    script.load()
    print("[+] Attached and hook script loaded. Listening for events (press Ctrl+C to stop)...", flush=True)

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[*] Detaching and exiting...", flush=True)
        session.detach()

if __name__ == "__main__":
    main()
