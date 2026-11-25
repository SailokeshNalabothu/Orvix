import os
import subprocess
import time
import socket
import urllib.request
import json
import sys

JAVA_HOME = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.13.10002-hotspot"
JAVA_BIN = os.path.join(JAVA_HOME, "bin", "java.exe")

SERVICES = [
    {"name": "guardrail-service", "port": 8081, "jar": "guardrail-service\\target\\guardrail-service-1.0.0-SNAPSHOT.jar"},
    {"name": "cache-service", "port": 8082, "jar": "cache-service\\target\\cache-service-1.0.0-SNAPSHOT.jar"},
    {"name": "billing-service", "port": 8083, "jar": "billing-service\\target\\billing-service-1.0.0-SNAPSHOT.jar"},
    {"name": "gateway-service", "port": 8080, "jar": "gateway-service\\target\\gateway-service-1.0.0-SNAPSHOT.jar"},
]

DETACHED_FLAGS = 0x00000008 | 0x00000200 # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
BREAKAWAY_FLAGS = 0x01000000 | DETACHED_FLAGS # CREATE_BREAKAWAY_FROM_JOB

def is_port_open(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1.0)
        return s.connect_ex(("localhost", port)) == 0

def wait_for_health(port, name, timeout=100):
    url = f"http://localhost:{port}/actuator/health"
    start = time.time()
    while time.time() - start < timeout:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "HealthChecker/1.0"})
            with urllib.request.urlopen(req, timeout=2) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode())
                    if data.get("status") == "UP":
                        print(f"[HEALTH-OK] {name} on port {port} is UP!")
                        return True
        except Exception:
            pass
        time.sleep(1)
    print(f"[HEALTH-TIMEOUT] {name} on port {port} failed to become UP within {timeout}s")
    return False

def kill_port(port):
    try:
        res = subprocess.run(["powershell", "-Command", f"(Get-NetTCPConnection -LocalPort {port} -State Listen -ErrorAction SilentlyContinue).OwningProcess"], capture_output=True, text=True)
        out = res.stdout.strip()
        if out and out.isdigit():
            subprocess.run(["powershell", "-Command", f"Stop-Process -Id {out} -Force -ErrorAction SilentlyContinue"], capture_output=True)
            time.sleep(1)
    except Exception:
        pass

def main():
    force_restart = "--restart" in sys.argv
    if force_restart:
        print("[RESTART] Stopping existing microservices on ports 8080, 8081, 8082, 8083...")
        for port in [8080, 8081, 8082, 8083]:
            kill_port(port)
        time.sleep(2)

    env = os.environ.copy()
    env["JAVA_HOME"] = JAVA_HOME
    env["PATH"] = f"{JAVA_HOME}\\bin;{env.get('PATH', '')}"

    for svc in SERVICES:
        if is_port_open(svc["port"]):
            print(f"[RUNNING] {svc['name']} is already listening on port {svc['port']}")
        else:
            print(f"[STARTING] Launching {svc['name']} ({svc['jar']})...")
            try:
                subprocess.Popen([JAVA_BIN, "-jar", svc["jar"]], 
                                 env=env, 
                                 stdin=subprocess.DEVNULL, 
                                 stdout=subprocess.DEVNULL, 
                                 stderr=subprocess.DEVNULL, 
                                 creationflags=BREAKAWAY_FLAGS)
            except Exception:
                subprocess.Popen([JAVA_BIN, "-jar", svc["jar"]], 
                                 env=env, 
                                 stdin=subprocess.DEVNULL, 
                                 stdout=subprocess.DEVNULL, 
                                 stderr=subprocess.DEVNULL, 
                                 creationflags=DETACHED_FLAGS)

    print("\nWaiting for all microservices to report UP...")
    all_ok = True
    for svc in SERVICES:
        ok = wait_for_health(svc["port"], svc["name"])
        if not ok:
            all_ok = False

    if all_ok:
        print("\n[SUCCESS] All 4 Orvix microservices are UP and healthy!")
        sys.exit(0)
    else:
        print("\n[FAILED] One or more microservices failed to start.")
        sys.exit(1)

if __name__ == "__main__":
    main()
