#!/usr/bin/env python3
"""
FlutterInspect Bridge Docker Entrypoint
Orchestrates: blutter.py -> blutter2symbols.py -> symbols.json
"""

import os
import sys
import shutil
import subprocess

def main():
    print("=" * 60)
    print(" FlutterInspect Bridge Engine (Docker Container)")
    print("=" * 60)

    work_dir = "/io"
    if len(sys.argv) > 1 and os.path.isdir(sys.argv[1]):
        work_dir = sys.argv[1]

    # Search for libapp.so and libflutter.so in work_dir or work_dir/input
    in_dir = work_dir
    if not (os.path.exists(os.path.join(in_dir, "libapp.so")) and os.path.exists(os.path.join(in_dir, "libflutter.so"))):
        sub_in = os.path.join(work_dir, "input")
        if os.path.exists(os.path.join(sub_in, "libapp.so")):
            in_dir = sub_in

    libapp = os.path.join(in_dir, "libapp.so")
    libflutter = os.path.join(in_dir, "libflutter.so")

    if not os.path.exists(libapp):
        print(f"[!] Error: Could not find libapp.so in {in_dir}")
        sys.exit(1)
    if not os.path.exists(libflutter):
        print(f"[!] Error: Could not find libflutter.so in {in_dir}")
        sys.exit(1)

    print(f"[*] Found libapp.so:    {libapp}")
    print(f"[*] Found libflutter.so: {libflutter}")

    blutter_out = os.path.join(work_dir, "blutter_out")
    os.makedirs(blutter_out, exist_ok=True)

    # 1. Run blutter.py
    print("\n[*] Phase 1: Running Blutter decompilation...")
    blutter_py = "/app/blutter/blutter.py"
    cmd_blutter = [sys.executable, blutter_py, in_dir, blutter_out]
    res = subprocess.run(cmd_blutter)
    if res.returncode != 0:
        print(f"[!] Blutter decompilation exited with code {res.returncode}")
        # Check if partial asm was produced anyway
        asm_dir = os.path.join(blutter_out, "asm")
        if not os.path.exists(asm_dir) or not os.listdir(asm_dir):
            sys.exit(res.returncode)

    # 2. Run blutter2symbols.py
    print("\n[*] Phase 2: Generating FlutterInspect symbols.json...")
    blutter2symbols_py = "/app/blutter2symbols.py"
    symbols_json = os.path.join(work_dir, "symbols.json")
    cmd_symbols = [sys.executable, blutter2symbols_py, blutter_out, symbols_json]
    res_sym = subprocess.run(cmd_symbols)
    if res_sym.returncode != 0:
        print(f"[!] Error converting blutter output to symbols.json")
        sys.exit(res_sym.returncode)

    if os.path.exists(symbols_json):
        size_kb = os.path.getsize(symbols_json) / 1024
        print(f"\n[+] SUCCESS! Produced {symbols_json} ({size_kb:.1f} KB)")
    else:
        print("[!] Error: symbols.json was not created.")
        sys.exit(1)

if __name__ == "__main__":
    main()
