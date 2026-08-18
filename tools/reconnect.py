#!/usr/bin/env python3
import socket
import subprocess
import concurrent.futures

ip = "192.168.1.13"

def check_port(p):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.12)
    res = s.connect_ex((ip, p))
    s.close()
    return p if res == 0 else None

all_ports = [5555] + list(range(30000, 65536))
print(f"[*] Scanning {len(all_ports)} wireless ADB ports on {ip}...")
with concurrent.futures.ThreadPoolExecutor(max_workers=250) as ex:
    open_ports = [p for p in ex.map(check_port, all_ports) if p is not None]

print(f"[*] Open ports: {open_ports}")

subprocess.run(["adb", "disconnect"])
for p in open_ports:
    subprocess.run(["adb", "connect", f"{ip}:{p}"])

subprocess.run(["adb", "devices"])
