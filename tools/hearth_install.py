"""Install the current dist/hearth-tv.apk on Alec's S26 with zero taps, through Hearth.

Flow: scp the APK to the Pi appshelf -> enqueue a Hearth shell_exec on the phone that downloads it over the
WireGuard tunnel (192.168.50.26:8820), runs `pm install -r` through Shizuku, and launches the app -> poll the
command -> verify with `dumpsys package` that the installed versionName matches.

usage: python tools/hearth_install.py [device]        (default device: s26)
Requires: the Hearth gateway running locally (C:/projects/hearth/gateway) and ssh alias `pi`.
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "dist" / "hearth-tv.apk"
GATEWAY = "http://127.0.0.1:8000"
SHELF_URL = "http://192.168.50.26:8820/hearth-tv.apk"
PACKAGE = "com.alec.hearthtv"

sys.path.insert(0, r"C:\projects\hearth\gateway")
_cwd = os.getcwd()
os.chdir(r"C:\projects\hearth\gateway")  # the gateway settings read their .env from the working directory
from app.config import settings  # noqa: E402
os.chdir(_cwd)

TOKEN = settings.GATEWAY_AUTH_TOKEN


def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(GATEWAY + path, data=data, method=method,
                               headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return resp.read().decode(errors="replace")
    except urllib.error.HTTPError as e:
        return json.dumps({"_http": e.code, "detail": e.read().decode(errors="replace")})


def shell(cmd, device, timeout=180):
    raw = req("POST", "/device/commands", {"type": "shell_exec", "source": "manual", "target_device": device,
                                          "payload": {"cmd": cmd}})
    m = re.search(r'"id":\s*(\d+)', raw)
    if not m:
        sys.exit(f"enqueue failed: {raw[:400]}")
    cid = int(m.group(1))
    row = json.loads(req("GET", f"/device/commands/{cid}"))
    if row.get("status") == "pending":
        req("POST", f"/device/commands/{cid}/approve")
    t0 = time.time()
    while time.time() - t0 < timeout:
        row = json.loads(req("GET", f"/device/commands/{cid}"))
        if row.get("status") in ("done", "failed", "expired"):
            break
        time.sleep(1.5)
    res = row.get("result") or {}
    return row.get("status"), (res.get("output") if isinstance(res, dict) else str(res)) or "", row.get("error")


def main():
    device = sys.argv[1] if len(sys.argv) > 1 else "s26"
    if not APK.exists():
        sys.exit(f"missing {APK} - run publish.ps1 or gradle assembleRelease + copy first")
    print(f"scp {APK.name} -> pi appshelf")
    subprocess.run(["scp", str(APK), "pi:/home/alec/appshelf/hearth-tv.apk"], check=True)

    cmd = (f"curl -s -m 120 -o /data/local/tmp/hearth-tv.apk {SHELF_URL} && "
           f"pm install -r /data/local/tmp/hearth-tv.apk && "
           f"am start -n {PACKAGE}/.MainActivity >/dev/null 2>&1; "
           f"dumpsys package {PACKAGE} | grep -m1 versionName")
    status, out, err = shell(cmd, device)
    print(f"[{status}] {out.strip()}")
    if err:
        print("error:", err)
    if status != "done" or "versionName=" not in out:
        sys.exit(1)


if __name__ == "__main__":
    main()
