import os
import zipfile
import subprocess
import glob

root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
dist_dir = os.path.join(root_dir, 'dist')
os.makedirs(dist_dir, exist_ok=True)
zip_path = os.path.join(dist_dir, 'FlutterInspect.zip')

ndk_strip = r"C:\Users\AkshayKhilari\AppData\Local\Android\Sdk\ndk\27.2.12479018\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"

# Find .so files
arm64_files = glob.glob(os.path.join(root_dir, 'module', 'build', 'intermediates', 'cxx', '**', 'arm64-v8a', 'libflutterlens.so'), recursive=True)
x86_files = glob.glob(os.path.join(root_dir, 'module', 'build', 'intermediates', 'cxx', '**', 'x86_64', 'libflutterlens.so'), recursive=True)

if not arm64_files:
    raise RuntimeError("arm64-v8a libflutterlens.so not found")
if not x86_files:
    raise RuntimeError("x86_64 libflutterlens.so not found")

arm64_so = arm64_files[0]
x86_so = x86_files[0]

# Strip copies
temp_arm64 = os.path.join(dist_dir, 'arm64-v8a.so')
temp_x86 = os.path.join(dist_dir, 'x86_64.so')

with open(arm64_so, 'rb') as f_in, open(temp_arm64, 'wb') as f_out:
    f_out.write(f_in.read())
with open(x86_so, 'rb') as f_in, open(temp_x86, 'wb') as f_out:
    f_out.write(f_in.read())

subprocess.run([ndk_strip, temp_arm64], check=True)
subprocess.run([ndk_strip, temp_x86], check=True)

with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_DEFLATED) as z:
    z.write(temp_arm64, 'zygisk/arm64-v8a.so')
    z.write(temp_x86, 'zygisk/x86_64.so')
    
    template_dir = os.path.join(root_dir, 'module', 'template')
    for item in os.listdir(template_dir):
        full_p = os.path.join(template_dir, item)
        if os.path.isfile(full_p):
            z.write(full_p, item)

os.remove(temp_arm64)
os.remove(temp_x86)

print(f"Created: {zip_path} ({os.path.getsize(zip_path)} bytes)")
