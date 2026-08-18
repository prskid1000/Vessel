#!/usr/bin/env python3
"""
ADB Wireless Keepalive & Auto-Discovery
Maintains a persistent wireless ADB connection by:
1. Pinging a target (gateway/DNS) every X seconds directly from the mobile device to prevent Wi-Fi/ADB sleep.
2. Automatically discovering and reconnecting if the mobile's IP or wireless debugging port rotates.
"""

import sys
import time
import subprocess
import asyncio
import re
import argparse
from datetime import datetime

def log(msg, level="INFO"):
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    prefix = {
        "INFO": "\033[94m[*]\033[0m",
        "OK": "\033[92m[+]\033[0m",
        "WARN": "\033[93m[!]\033[0m",
        "ERR": "\033[91m[-]\033[0m"
    }.get(level, "[*]")
    print(f"[{ts}] {prefix} {msg}", flush=True)

def run_cmd(cmd, timeout=10):
    try:
        res = subprocess.run(
            cmd,
            shell=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout
        )
        return res.returncode, res.stdout.strip(), res.stderr.strip()
    except subprocess.TimeoutExpired:
        return -1, "", "Command timed out"
    except Exception as e:
        return -1, "", str(e)

def is_device_connected(target_ip=None):
    code, out, _ = run_cmd("adb devices")
    if code != 0:
        return False
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            if target_ip is None or target_ip in parts[0]:
                return True
    return False

def get_arp_ips(target_mac=None):
    """Find candidate device IPs in ARP table, matching known MAC if provided."""
    code, out, _ = run_cmd("arp -a")
    ips = []
    if code != 0:
        return ips
    for line in out.splitlines():
        line = line.strip()
        match = re.search(r"(\d+\.\d+\.\d+\.\d+)\s+([0-9a-fA-F-]+)", line)
        if match:
            ip, mac = match.group(1), match.group(2).lower().replace("-", ":")
            if target_mac and target_mac.lower() in mac:
                return [ip]
            if (ip.startswith("192.168.") or ip.startswith("10.") or ip.startswith("172.")) and not ip.endswith(".255") and not ip.endswith(".1"):
                ips.append(ip)
    return ips

async def check_port_async(ip, port, timeout=0.15):
    try:
        _, writer = await asyncio.wait_for(asyncio.open_connection(ip, port), timeout=timeout)
        writer.close()
        await writer.wait_closed()
        return port
    except Exception:
        return None

async def scan_ports_async(ip, start_port=30000, end_port=45000, concurrency=250):
    sem = asyncio.Semaphore(concurrency)
    async def sem_check(p):
        async with sem:
            return await check_port_async(ip, p)
    
    tasks = [sem_check(p) for p in range(start_port, end_port + 1)]
    results = await asyncio.gather(*tasks)
    return [p for p in results if p is not None]

def find_active_adb_port(candidate_ips):
    """Scans candidate IPs for open high ports and attempts adb connect."""
    # Also check adb mdns services first
    code, mdns_out, _ = run_cmd("adb mdns services", timeout=3)
    if code == 0 and mdns_out:
        for line in mdns_out.splitlines():
            match = re.search(r"(\d+\.\d+\.\d+\.\d+):(\d+)", line)
            if match:
                ip, port = match.group(1), int(match.group(2))
                log(f"Discovered via mDNS: {ip}:{port}", "INFO")
                if connect_adb(ip, port):
                    return ip, port

    for ip in candidate_ips:
        log(f"Scanning {ip} for open wireless ADB ports (30000-45000)...", "INFO")
        open_ports = asyncio.run(scan_ports_async(ip, 30000, 45000))
        if open_ports:
            log(f"Found open ports on {ip}: {open_ports}", "INFO")
            for port in open_ports:
                if connect_adb(ip, port):
                    return ip, port
    return None, None

def connect_adb(ip, port):
    log(f"Attempting adb connect {ip}:{port}...", "INFO")
    code, out, _ = run_cmd(f"adb connect {ip}:{port}", timeout=6)
    if ("connected to" in out.lower() or "already connected to" in out.lower()) and is_device_connected(ip):
        log(f"Successfully connected to {ip}:{port}", "OK")
        return True
    return False

def main():
    parser = argparse.ArgumentParser(description="ADB Wireless Keepalive & Auto-Discovery")
    parser.add_argument("target", nargs="?", default="192.168.1.13:40845", help="Initial device IP:Port (e.g. 192.168.1.13:40845)")
    parser.add_argument("--ip", default=None, help="Initial device IP")
    parser.add_argument("--port", type=int, default=None, help="Initial device port")
    parser.add_argument("--mac", default="be:36:cc:e6:af:32", help="Device MAC address for IP rotation tracking")
    parser.add_argument("--ping-target", default="192.168.1.1", help="Target IP for ping keepalive (default: 192.168.1.1)")
    parser.add_argument("--interval", type=float, default=2.0, help="Ping interval in seconds (default: 2.0)")
    args = parser.parse_args()

    if ":" in args.target and not args.ip:
        target_ip, target_port = args.target.split(":", 1)
        current_ip = target_ip
        current_port = int(target_port)
    else:
        current_ip = args.ip or "192.168.1.13"
        current_port = args.port or 40845

    mac = args.mac
    ping_target = args.ping_target
    interval = args.interval

    log(f"ADB Keepalive started. Device: {current_ip}:{current_port}, Ping target: {ping_target}, Interval: {interval}s", "INFO")

    # Initial connection check
    if not is_device_connected(current_ip):
        if not connect_adb(current_ip, current_port):
            log("Initial direct connection failed. Starting auto-discovery...", "WARN")
            candidate_ips = [current_ip]
            arp_ips = get_arp_ips(mac)
            for a_ip in arp_ips:
                if a_ip not in candidate_ips:
                    candidate_ips.append(a_ip)
            new_ip, new_port = find_active_adb_port(candidate_ips)
            if new_ip and new_port:
                current_ip, current_port = new_ip, new_port
            else:
                log("Could not find active wireless ADB service. Ensure Wireless Debugging is enabled.", "ERR")

    consecutive_failures = 0

    while True:
        try:
            cmd = f'adb shell "ping -c 1 -W 1 {ping_target} 2>&1"'
            code, out, err = run_cmd(cmd, timeout=5)
            
            if code == 0 and "bytes from" in out:
                consecutive_failures = 0
                time_match = re.search(r"time=([0-9.]+)\s*ms", out)
                latency = time_match.group(0) if time_match else "OK"
                log(f"Ping OK -> {ping_target} ({latency}) [{current_ip}:{current_port}]", "OK")
            else:
                consecutive_failures += 1
                log(f"Ping failed ({consecutive_failures}/2): {out or err}", "WARN")

                if consecutive_failures >= 2:
                    log("Connection dropped. Attempting auto-reconnect & discovery...", "WARN")
                    if connect_adb(current_ip, current_port):
                        consecutive_failures = 0
                        continue
                    
                    candidate_ips = []
                    arp_ips = get_arp_ips(mac)
                    if arp_ips:
                        candidate_ips.extend(arp_ips)
                    if current_ip not in candidate_ips:
                        candidate_ips.append(current_ip)

                    new_ip, new_port = find_active_adb_port(candidate_ips)
                    if new_ip and new_port:
                        current_ip, current_port = new_ip, new_port
                        consecutive_failures = 0
                    else:
                        log("Auto-discovery in progress, retrying next cycle...", "WARN")

            time.sleep(interval)
        except KeyboardInterrupt:
            log("Stopping ADB keepalive script.", "INFO")
            break
        except Exception as ex:
            log(f"Unexpected error: {ex}", "ERR")
            time.sleep(interval)

if __name__ == "__main__":
    main()
