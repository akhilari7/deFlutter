#!/usr/bin/env python3
"""
blutter2symbols.py - Converts Blutter output (asm/ and blutter_frida.js)
into a compact symbols.json dictionary for FlutterInspect.
"""

import os
import sys
import re
import json
import time

def parse_blutter_frida_js(frida_js_path):
    """Extracts class definitions from blutter_frida.js const Classes array."""
    classes = {}
    if not os.path.exists(frida_js_path):
        return classes

    pattern = re.compile(r'\{id:(\d+),name:"([^"]+)"(?:,.*?size:(\d+))?')
    try:
        with open(frida_js_path, 'r', encoding='utf-8', errors='ignore') as f:
            for line in f:
                if '{id:' in line:
                    for m in pattern.finditer(line):
                        cid = int(m.group(1))
                        name = m.group(2)
                        size = int(m.group(3)) if m.group(3) else 0
                        hex_cid = f"0x{cid:x}"
                        classes[hex_cid] = {
                            "cid": hex_cid,
                            "cid_dec": cid,
                            "name": name,
                            "size": size,
                            "fields": {}
                        }
    except Exception as e:
        print(f"[!] Warning reading {frida_js_path}: {e}")
    return classes

def parse_asm_directory(asm_dir, existing_classes):
    """Scans all .dart files in asm/ directory and extracts classes, fields, and functions."""
    functions = {}
    classes = existing_classes

    # Regex patterns
    re_lib = re.compile(r'//\s*lib:\s*.*?,\s*url:\s*(.+)')
    re_class_id = re.compile(r'//\s*class id:\s*(\d+)')
    re_class_decl = re.compile(r'class\s+([A-Za-z0-9_<>]+)')
    re_field = re.compile(r'^\s*.*?\b([A-Za-z0-9_]+)\s*;\s*//\s*offset:\s*(0x[0-9a-fA-F]+)')
    re_method_decl = re.compile(r'^\s*(?:static\s+|factory\s+|get\s+|set\s+)*(?:[A-Za-z0-9_<>?]+\s+)?([A-Za-z0-9_<>.]+|==)\s*\(')
    re_addr = re.compile(r'//\s*\*\*\s*addr:\s*(0x[0-9a-fA-F]+)(?:,\s*size:\s*(0x[0-9a-fA-F]+))?')

    re_setup_params = re.compile(r'SetupParameters\((.*?)\)')

    def parse_params(param_str, method_name):
        if not param_str or not param_str.strip():
            return []
        parts = []
        current = []
        in_comment = False
        brace_depth = 0
        i = 0
        while i < len(param_str):
            ch = param_str[i]
            if param_str[i:i+2] == '/*':
                in_comment = True
                current.append('/*')
                i += 2
                continue
            elif param_str[i:i+2] == '*/':
                in_comment = False
                current.append('*/')
                i += 2
                continue
            if not in_comment:
                if ch in '{[':
                    brace_depth += 1
                elif ch in '}]':
                    brace_depth -= 1
                elif ch == ',' and brace_depth == 0:
                    parts.append(''.join(current).strip())
                    current = []
                    i += 1
                    continue
            current.append(ch)
            i += 1
        if current:
            parts.append(''.join(current).strip())

        parsed = []
        for idx, p in enumerate(parts):
            if not p: continue
            m_comm = re.search(r'/\*\s*(.*?)\s*\*/', p)
            reg = ''
            slot = ''
            if m_comm:
                comm = m_comm.group(1)
                m_reg = re.search(r'\b([rx]\d+)\b', comm)
                if m_reg: reg = m_reg.group(1)
                m_slot = re.search(r'(fp[+-]0x[0-9a-fA-F]+)', comm)
                if m_slot: slot = m_slot.group(1)
                p_clean = re.sub(r'/\*.*?\*/', '', p).strip()
            else:
                p_clean = p.strip()

            p_clean = p_clean.strip('{}[]').strip()
            if '=' in p_clean:
                p_clean = p_clean.split('=')[0].strip()

            tokens = p_clean.split()
            if len(tokens) >= 2:
                p_type = tokens[0]
                p_name = tokens[1]
            elif len(tokens) == 1:
                if tokens[0] == 'this':
                    p_type = 'dynamic'
                    p_name = 'this'
                else:
                    p_type = tokens[0]
                    p_name = f'arg{idx}'
            else:
                p_type = 'dynamic'
                p_name = f'arg{idx}'

            # Semantic enrichment for generic placeholders ('_')
            semantic_name = p_name
            if p_name in ('_', 'arg', ''):
                m_lower = method_name.lower()
                t_lower = p_type.lower()
                if t_lower not in ('dynamic', 'object', 'null', '_'):
                    semantic_name = t_lower
                elif 'decrypt' in m_lower:
                    names = ['cipherText', 'key', 'iv']
                    pos = idx - 1 if idx > 0 else 0
                    semantic_name = names[pos] if pos < len(names) else f'arg{idx}'
                elif 'encrypt' in m_lower:
                    names = ['plainText', 'key', 'iv']
                    pos = idx - 1 if idx > 0 else 0
                    semantic_name = names[pos] if pos < len(names) else f'arg{idx}'
                elif 'query' in m_lower or 'sql' in m_lower:
                    names = ['sql', 'arguments']
                    pos = idx - 1 if idx > 0 else 0
                    semantic_name = names[pos] if pos < len(names) else f'arg{idx}'
                elif 'insert' in m_lower or 'update' in m_lower:
                    names = ['table', 'values', 'where']
                    pos = idx - 1 if idx > 0 else 0
                    semantic_name = names[pos] if pos < len(names) else f'arg{idx}'
                elif 'auth' in m_lower or 'login' in m_lower:
                    names = ['password', 'username', 'token']
                    pos = idx - 1 if idx > 0 else 0
                    semantic_name = names[pos] if pos < len(names) else f'arg{idx}'
                elif reg:
                    semantic_name = f'arg_{reg}'
                else:
                    semantic_name = f'arg{idx}'

            parsed.append({
                "idx": idx,
                "name": semantic_name,
                "type": p_type,
                "reg": reg,
                "slot": slot
            })
        return parsed

    file_count = 0
    total_files = sum(len(files) for _, _, files in os.walk(asm_dir))

    for root, _, files in os.walk(asm_dir):
        for file in files:
            if not file.endswith('.dart'):
                continue
            file_count += 1
            file_path = os.path.join(root, file)

            current_url = ""
            current_cid = None
            current_class = "::"
            last_method_name = ""
            current_fn_addr = None

            try:
                with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
                    for line in f:
                        line_strip = line.strip()

                        # 1. Library URL
                        m_lib = re_lib.search(line_strip)
                        if m_lib:
                            current_url = m_lib.group(1).strip()
                            continue

                        # 2. Class ID
                        m_cid = re_class_id.search(line_strip)
                        if m_cid:
                            cid_dec = int(m_cid.group(1))
                            current_cid = f"0x{cid_dec:x}"
                            continue

                        # 3. Class Declaration
                        m_cls = re_class_decl.search(line_strip)
                        if m_cls:
                            current_class = m_cls.group(1)
                            if current_cid:
                                if current_cid not in classes:
                                    classes[current_cid] = {
                                        "cid": current_cid,
                                        "name": current_class,
                                        "lib": current_url,
                                        "fields": {}
                                    }
                                else:
                                    classes[current_cid]["name"] = current_class
                                    classes[current_cid]["lib"] = current_url
                            continue

                        # 4. Field declaration
                        m_field = re_field.search(line)
                        if m_field and current_cid and current_cid in classes:
                            f_name = m_field.group(1)
                            f_off = m_field.group(2).lower()
                            classes[current_cid]["fields"][f_off] = f_name
                            continue

                        # 5. Method declaration line
                        m_mth = re_method_decl.search(line)
                        if m_mth:
                            last_method_name = m_mth.group(1)
                            continue

                        # 6. Function Code Offset
                        m_addr = re_addr.search(line_strip)
                        if m_addr:
                            fn_addr = m_addr.group(1).lower()
                            fn_size = m_addr.group(2) if m_addr.group(2) else ""
                            mth = last_method_name if last_method_name else "fn"
                            full_name = f"{current_class}.{mth}" if current_class != "::" else mth

                            functions[fn_addr] = {
                                "name": full_name,
                                "class": current_class,
                                "method": mth,
                                "lib": current_url,
                                "size": fn_size,
                                "params": []
                            }
                            current_fn_addr = fn_addr
                            last_method_name = ""
                            continue

                        # 7. SetupParameters instruction within function body
                        if current_fn_addr and "SetupParameters" in line_strip:
                            m_sp = re_setup_params.search(line_strip)
                            if m_sp and current_fn_addr in functions:
                                mth_name = functions[current_fn_addr]["method"]
                                params = parse_params(m_sp.group(1), mth_name)
                                functions[current_fn_addr]["params"] = params
                                current_fn_addr = None
            except Exception as e:
                print(f"[!] Error reading {file_path}: {e}")

    return classes, functions

import argparse

def main():
    parser = argparse.ArgumentParser(
        description="Converts Blutter output (asm/ and blutter_frida.js) into symbols.json for FlutterInspect"
    )
    parser.add_argument("input_dir", help="Path to Blutter output directory (containing asm/ and blutter_frida.js)")
    parser.add_argument("output_pos", nargs="?", default=None, help="Optional positional output JSON path")
    parser.add_argument("-o", "--output", dest="output_opt", default=None, help="Output symbols JSON path (default: symbols.json)")
    args = parser.parse_args()

    input_dir = args.input_dir
    output_file = args.output_opt or args.output_pos or "symbols.json"

    t0 = time.time()
    print(f"[*] Processing Blutter output from: {input_dir}")

    # Look for blutter_frida.js
    frida_js_path = os.path.join(input_dir, "blutter_frida.js")
    if not os.path.exists(frida_js_path):
        frida_js_path = os.path.join(input_dir, "..", "blutter_frida.js")

    classes = {}
    if os.path.exists(frida_js_path):
        print(f"[*] Reading Class IDs from: {frida_js_path}")
        classes = parse_blutter_frida_js(frida_js_path)
        print(f"    +-- Found {len(classes)} classes in Frida table")

    # Look for asm/ directory
    asm_dir = os.path.join(input_dir, "asm")
    if not os.path.exists(asm_dir):
        # Maybe input_dir is asm itself
        asm_dir = input_dir

    print(f"[*] Scanning assembly files in: {asm_dir}")
    classes, functions = parse_asm_directory(asm_dir, classes)
    print(f"    +-- Found {len(functions)} functions and {len(classes)} classes")

    result = {
        "generator": "FlutterInspect blutter2symbols",
        "timestamp": int(time.time()),
        "stats": {
            "classes_count": len(classes),
            "functions_count": len(functions)
        },
        "classes": classes,
        "functions": functions
    }

    print(f"[*] Writing symbols database to: {output_file}")
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(result, f, indent=2)

    elapsed = time.time() - t0
    size_kb = os.path.getsize(output_file) / 1024
    print(f"[+] Done in {elapsed:.2f}s! Generated {output_file} ({size_kb:.1f} KB)")

if __name__ == "__main__":
    main()
