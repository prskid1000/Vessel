#!/usr/bin/env python3
import socket
import subprocess
import os
import concurrent.futures

ip = "192.168.1.13"
apk = os.path.abspath(r"app\build\outputs\apk\sideload\debug\app-sideload-debug.apk")

print(f"[*] Target IP: {ip}")
print(f"[*] APK Path: {apk} ({os.path.getsize(apk):,} bytes)")

def check_port(p):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.12)
    res = s.connect_ex((ip, p))
    s.close()
    return p if res == 0 else None

all_ports = [5555] + list(range(30000, 65536))
print(f"[*] Scanning {len(all_ports)} wireless ADB ports on device (5555 & 30000-65535)...")
with concurrent.futures.ThreadPoolExecutor(max_workers=250) as ex:
    open_ports = [p for p in ex.map(check_port, all_ports) if p is not None]

print(f"[*] Open ports detected: {open_ports}")

installed = False
for port in open_ports:
    target = f"{ip}:{port}"
    res = subprocess.run(["adb", "connect", target], capture_output=True, text=True)
    out = res.stdout.strip()
    print(f"[*] adb connect {target} -> {out}")
    if "connected" in out.lower() and "cannot" not in out.lower():
        print(f"\n[+] Connected to {target}! Installing APK now...")
        proc = subprocess.run(["adb", "-s", target, "install", "-r", "-d", apk], capture_output=True, text=True)
        print("[+] Result:")
        print(proc.stdout)
        if proc.stderr:
            print(proc.stderr)
        if "Success" in proc.stdout:
            installed = True
            break

if not installed:
    print("\n[!] Could not connect to an open ADB port.")
    print("[!] Check the port shown under Settings -> Developer Options -> Wireless Debugging on your phone.")
