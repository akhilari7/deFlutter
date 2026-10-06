#!/usr/bin/env python3
"""
Deflutter Host Companion Bridge
Automates: ADB extraction -> Blutter (Docker or Local) -> symbols.json -> Deflutter Web API sync
"""

import os
import sys
import argparse
import subprocess
import zipfile
import shutil
import tempfile
import urllib.request
import urllib.error
import json
import time

# Ensure UTF-8 output on Windows consoles
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

BANNER = """
===================================================================
          Deflutter Host Companion Bridge (Phase 4)
  Automated Snapshot Pull -> Blutter Decompile -> Symbol Sync
===================================================================
"""

def run_cmd(cmd, check=True, capture=True):
    """Executes a shell command and returns stdout."""
    res = subprocess.run(cmd, shell=True, text=True, capture_output=capture)
    if check and res.returncode != 0:
        err = res.stderr.strip() if res.stderr else f"Command failed with code {res.returncode}"
        raise RuntimeError(f"Failed running '{cmd}': {err}")
    return res.stdout.strip() if capture else ""

def check_adb():
    """Ensures ADB is available and at least one device is connected."""
    try:
        out = run_cmd("adb devices")
        lines = [line.strip() for line in out.splitlines() if line.strip() and not line.startswith("List of")]
        devices = [l.split()[0] for l in lines if "device" in l and not "offline" in l]
        if not devices:
            print("[!] Error: No ADB devices connected or authorized.")
            sys.exit(1)
        print(f"[✓] Connected ADB Device: {devices[0]}")
        return devices[0]
    except Exception as e:
        print(f"[!] Error checking ADB: {e}")
        sys.exit(1)

def extract_libs_from_apk(apk_path, out_dir):
    """Extracts libapp.so and libflutter.so from an APK archive."""
    found_app = False
    found_flutter = False
    with zipfile.ZipFile(apk_path, 'r') as z:
        for name in z.namelist():
            if name.endswith("libapp.so") and ("arm64" in name or not found_app):
                with z.open(name) as src, open(os.path.join(out_dir, "libapp.so"), "wb") as dst:
                    shutil.copyfileobj(src, dst)
                found_app = True
                print(f"    ├── Extracted: {name} -> libapp.so")
            elif name.endswith("libflutter.so") and ("arm64" in name or not found_flutter):
                with z.open(name) as src, open(os.path.join(out_dir, "libflutter.so"), "wb") as dst:
                    shutil.copyfileobj(src, dst)
                found_flutter = True
                print(f"    ├── Extracted: {name} -> libflutter.so")
    return found_app and found_flutter

def pull_libs_from_device(pkg_name, out_dir):
    """Pulls libapp.so and libflutter.so from device for a given package."""
    print(f"[*] Locating package '{pkg_name}' on device...")
    path_output = run_cmd(f"adb shell pm path {pkg_name}")
    apk_paths = [p.replace("package:", "").strip() for p in path_output.splitlines() if p.strip()]

    if not apk_paths:
        print(f"[!] Error: Package '{pkg_name}' not found on device.")
        sys.exit(1)

    print(f"[*] Found {len(apk_paths)} APK part(s) on device.")
    temp_apk_dir = os.path.join(out_dir, "apks")
    os.makedirs(temp_apk_dir, exist_ok=True)

    extracted_both = False
    for remote_apk in apk_paths:
        local_name = os.path.basename(remote_apk)
        local_apk = os.path.join(temp_apk_dir, local_name)
        print(f"[*] Pulling {local_name}...")
        run_cmd(f"adb pull \"{remote_apk}\" \"{local_apk}\"")
        if extract_libs_from_apk(local_apk, out_dir):
            extracted_both = True

    # If APK didn't contain extracted native libs (e.g. android:extractNativeLibs=true in older installs)
    if not extracted_both:
        app_so = os.path.join(out_dir, "libapp.so")
        flutter_so = os.path.join(out_dir, "libflutter.so")
        if not (os.path.exists(app_so) and os.path.exists(flutter_so)):
            print("[*] Checking native library directory on device via root...")
            try:
                remote_lib_app = run_cmd(f"adb shell su -c 'find /data/app -path \"*{pkg_name}*/lib/arm64/libapp.so\"'").strip()
                remote_lib_flutter = run_cmd(f"adb shell su -c 'find /data/app -path \"*{pkg_name}*/lib/arm64/libflutter.so\"'").strip()
                if remote_lib_app and remote_lib_flutter:
                    run_cmd(f"adb shell su -c 'cp \"{remote_lib_app}\" /data/local/tmp/libapp.so && chmod 644 /data/local/tmp/libapp.so'")
                    run_cmd(f"adb shell su -c 'cp \"{remote_lib_flutter}\" /data/local/tmp/libflutter.so && chmod 644 /data/local/tmp/libflutter.so'")
                    run_cmd(f"adb pull /data/local/tmp/libapp.so \"{app_so}\"")
                    run_cmd(f"adb pull /data/local/tmp/libflutter.so \"{flutter_so}\"")
                    run_cmd("adb shell rm -f /data/local/tmp/libapp.so /data/local/tmp/libflutter.so")
                    print("[✓] Successfully pulled libapp.so and libflutter.so via root access.")
            except Exception as e:
                print(f"[!] Root fallback extraction error: {e}")

    app_so = os.path.join(out_dir, "libapp.so")
    flutter_so = os.path.join(out_dir, "libflutter.so")
    if not (os.path.exists(app_so) and os.path.exists(flutter_so)):
        print(f"[!] Error: Could not extract both libapp.so and libflutter.so for '{pkg_name}'.")
        sys.exit(1)

    print(f"[✓] Libraries ready in: {out_dir}")

def check_docker_available():
    """Checks if Docker daemon is running."""
    try:
        res = subprocess.run("docker info", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return res.returncode == 0
    except Exception:
        return False

def run_blutter_docker(work_dir):
    """Executes Blutter decompilation using the Docker container."""
    image_tag = "flutterinspect/bridge:latest"
    print(f"[*] Checking Docker image '{image_tag}'...")
    inspect_res = subprocess.run(f"docker inspect --type=image {image_tag}", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if inspect_res.returncode != 0:
        print(f"[*] Docker image '{image_tag}' not found locally. Building from bridge/Dockerfile...")
        repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        bridge_dir = os.path.join(repo_root, "bridge")
        build_cmd = f"docker build -t {image_tag} \"{bridge_dir}\""
        print(f"[*] Executing: {build_cmd}")
        build_res = subprocess.run(build_cmd, shell=True)
        if build_res.returncode != 0:
            raise RuntimeError("Failed to build Docker bridge image.")

    abs_work_dir = os.path.abspath(work_dir).replace("\\", "/")
    print(f"[*] Spawning Docker container with volume: {abs_work_dir} -> /io")
    docker_run_cmd = f"docker run --rm -v \"{abs_work_dir}:/io\" {image_tag}"
    res = subprocess.run(docker_run_cmd, shell=True)
    if res.returncode != 0:
        raise RuntimeError(f"Docker bridge exited with code {res.returncode}")

def run_blutter_local(work_dir, blutter_path):
    """Fallback: runs local Blutter installation."""
    blutter_py = os.path.join(blutter_path, "blutter.py")
    if not os.path.exists(blutter_py):
        raise RuntimeError(f"blutter.py not found at {blutter_py}")

    blutter_out = os.path.join(work_dir, "blutter_out")
    os.makedirs(blutter_out, exist_ok=True)

    print(f"[*] Running local Blutter: {blutter_py} {work_dir} {blutter_out}")
    res = subprocess.run([sys.executable, blutter_py, work_dir, blutter_out])
    if res.returncode != 0:
        print(f"[!] Warning: Blutter returned code {res.returncode}")

    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    blutter2symbols = os.path.join(repo_root, "scripts", "blutter2symbols.py")
    symbols_json = os.path.join(work_dir, "symbols.json")

    print(f"[*] Generating symbols.json with blutter2symbols.py...")
    res2 = subprocess.run([sys.executable, blutter2symbols, blutter_out, symbols_json])
    if res2.returncode != 0:
        raise RuntimeError("Failed to convert blutter output to symbols.json")

def upload_symbols_to_device(symbols_json_path, pkg_name, port=8088):
    """Sends symbols.json to FlutterInspect via fast ADB push + API sync."""
    print(f"\n[*] Forwarding port {port} to device via ADB...")
    run_cmd(f"adb forward tcp:{port} tcp:{port}")

    file_size_mb = os.path.getsize(symbols_json_path) / (1024 * 1024)
    print(f"[*] Symbols file size: {file_size_mb:.2f} MB")

    # Fast-path: Push directly to device storage over USB ADB
    # /data/local/tmp is universal POSIX Linux storage accessible to all apps without Scoped Storage restrictions
    remote_tmp_file = f"/data/local/tmp/{pkg_name}_symbols.json"
    remote_pkg_dir = f"/sdcard/Download/FlutterInspect/{pkg_name}"
    remote_pkg_file = f"{remote_pkg_dir}/symbols.json"
    remote_generic_file = "/sdcard/Download/FlutterInspect/symbols.json"

    adb_pushed = False
    try:
        print(f"[*] Pushing symbols directly to device storage via ADB...")
        run_cmd(f'adb push "{symbols_json_path}" "{remote_tmp_file}"')
        run_cmd(f'adb shell "chmod 666 \'{remote_tmp_file}\'"')
        # Also copy to Download folder for offline access
        run_cmd(f'adb shell "mkdir -p \'{remote_pkg_dir}\'"')
        run_cmd(f'adb push "{symbols_json_path}" "{remote_pkg_file}"')
        run_cmd(f'adb push "{symbols_json_path}" "{remote_generic_file}"')
        adb_pushed = True
        print(f"[✓] Fast ADB push completed successfully.")
    except Exception as e:
        print(f"[!] Direct ADB push encountered an issue ({e}), falling back to HTTP upload...")

    url = f"http://localhost:{port}/api/load-symbols?pkg={pkg_name}&path={remote_tmp_file}" if adb_pushed else f"http://localhost:{port}/api/upload-symbols"
    print(f"[*] Synchronizing with FlutterInspect API: {url} (target: {pkg_name})")

    if adb_pushed:
        req = urllib.request.Request(
            url,
            data=b"{}",
            headers={"Content-Type": "application/json", "X-Package-Name": pkg_name},
            method="POST"
        )
    else:
        with open(symbols_json_path, "rb") as f:
            data = f.read()
        req = urllib.request.Request(
            url,
            data=data,
            headers={"Content-Type": "application/json", "X-Package-Name": pkg_name},
            method="POST"
        )

    # 120s timeout allows the phone to parse massive 100k+ symbol databases cleanly
    max_retries = 3
    for attempt in range(max_retries):
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                resp_text = resp.read().decode('utf-8')
                result = json.loads(resp_text)
                if result.get("success"):
                    print(f"\n[✓] SUCCESS! FlutterInspect live synchronization complete:")
                    print(f"    ├── Functions Loaded: {result.get('functions', 0)}")
                    print(f"    ├── Classes Loaded:   {result.get('classes', 0)}")
                    print(f"    └── Message:          {result.get('message', 'OK')}")
                    return True
                else:
                    print(f"[!] API returned error: {result.get('error')}")
                    return False
        except urllib.error.URLError as e:
            if attempt < max_retries - 1:
                print(f"[*] Device port {port} not responding yet, waiting 2s... (attempt {attempt+1})")
                time.sleep(2)
            else:
                print(f"[!] Failed connecting to Deflutter API at {url}: {e}")
                print(f"[*] Please ensure Deflutter App is running on the device.")
                return False
    return False

def main():
    print(BANNER)
    parser = argparse.ArgumentParser(description="Deflutter Host Companion Bridge")
    parser.add_argument("--pkg", help="Target Android package name (e.g. com.eightksec.droidpass)")
    parser.add_argument("--libapp", help="Path to existing libapp.so (skips ADB pull)")
    parser.add_argument("--libflutter", help="Path to existing libflutter.so (skips ADB pull)")
    parser.add_argument("--outdir", default="workspace", help="Local directory to store pulled libraries and output")
    parser.add_argument("--local-blutter", help="Path to local Blutter repository (skips Docker)")
    parser.add_argument("--symbols", help="Path to existing symbols.json file to upload directly (skips decompilation)")
    parser.add_argument("--port", type=int, default=8088, help="Deflutter Web Terminal port (default 8088)")
    parser.add_argument("--skip-upload", action="store_true", help="Only generate symbols.json without uploading")

    args = parser.parse_args()

    target_pkg = args.pkg or "custom_target"

    # Fast-path: Directly upload existing symbols.json
    if args.symbols:
        if not os.path.exists(args.symbols):
            print(f"[!] Error: Symbols file '{args.symbols}' not found.")
            sys.exit(1)
        check_adb()
        upload_symbols_to_device(args.symbols, target_pkg, port=args.port)
        return

    if not args.pkg and not (args.libapp and args.libflutter):
        parser.error("Either --symbols, --pkg <package_name>, or both --libapp and --libflutter must be provided.")

    work_dir = os.path.join(args.outdir, target_pkg)
    os.makedirs(work_dir, exist_ok=True)

    # 1. Acquire libraries
    if args.libapp and args.libflutter:
        print(f"[*] Using provided libraries:")
        print(f"    ├── libapp.so:    {args.libapp}")
        print(f"    └── libflutter.so: {args.libflutter}")
        shutil.copyfile(args.libapp, os.path.join(work_dir, "libapp.so"))
        shutil.copyfile(args.libflutter, os.path.join(work_dir, "libflutter.so"))
    else:
        check_adb()
        pull_libs_from_device(args.pkg, work_dir)

    # 2. Decompile with Blutter & Generate Symbols
    symbols_json = os.path.join(work_dir, "symbols.json")

    # Determine execution engine
    use_docker = True
    if args.local_blutter:
        local_blutter_dir = args.local_blutter
        if not os.path.exists(os.path.join(local_blutter_dir, "blutter.py")):
            print(f"[!] Error: blutter.py not found in {local_blutter_dir}")
            sys.exit(1)
        print(f"[*] Engine explicitly selected: Local Blutter ({local_blutter_dir})")
        use_docker = False
    else:
        if not check_docker_available():
            print("\n[!] Docker Engine is not currently running or reachable.")
            print("[*] To run zero-dependency decompilation without installing CMake/Clang on your host:")
            print("    1. Start Docker Desktop on your machine.")
            print("    2. Re-run this command once Docker is running.")
            print("    (Or pass --local-blutter <path> if you have a configured C++ toolchain)\n")
            sys.exit(1)
        print("[*] Engine selected: Docker Container (flutterinspect/bridge)")
        use_docker = True

    t0 = time.time()
    if use_docker:
        run_blutter_docker(work_dir)
    else:
        run_blutter_local(work_dir, local_blutter_dir)

    elapsed = time.time() - t0
    print(f"\n[✓] Decompilation & symbol generation finished in {elapsed:.1f}s.")

    if not os.path.exists(symbols_json):
        print(f"[!] Error: Expected symbols file {symbols_json} was not generated.")
        sys.exit(1)

    # 3. Synchronize with FlutterInspect
    if not args.skip_upload:
        upload_symbols_to_device(symbols_json, target_pkg, port=args.port)
    else:
        print(f"[*] Skipping upload (--skip-upload set). Symbols saved to: {symbols_json}")

if __name__ == "__main__":
    main()
