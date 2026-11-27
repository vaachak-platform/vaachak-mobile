#!/usr/bin/env python3
"""Normalize the disposable Sonar CI AVD after creation and before launch."""
import os
from pathlib import Path

if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("ERROR: This hook is restricted to GitHub Actions.")

avd_home = os.environ.get("ANDROID_AVD_HOME")
if not avd_home:
    raise SystemExit("ERROR: Emulator runner did not set ANDROID_AVD_HOME.")

config = Path(avd_home) / "vaachak-ci-api35.avd" / "config.ini"
if not config.is_file():
    raise SystemExit(f"ERROR: Missing CI AVD config: {config}")

lines = config.read_text().splitlines()

def key(line):
    return line.partition("=")[0].strip() if "=" in line else ""

def values(name):
    return [line.partition("=")[2].strip() for line in lines if key(line) == name]

image = values("image.sysdir.1")
if len(image) != 1 or not image[0].rstrip("/").endswith(
    "system-images/android-35/google_apis/x86_64"
):
    raise SystemExit(f"ERROR: Unexpected system image: {image}")

# Preserve the existing API/ABI/image and normalize hardware values. The
# pinned action appends settings, so remove old copies instead of appending
# another conflicting value. The qualified AVD uses vm.heapSize (in MB).
settings = {
    "hw.lcd.width": "1080",
    "hw.lcd.height": "2400",
    "hw.lcd.density": "420",
    "skin.name": "1080x2400",
    "skin.path": "_no_skin",
    "hw.cpu.ncore": "4",
    "hw.ramSize": "4096",
    "vm.heapSize": "228",
    "disk.dataPartition.size": "10G",
}
remove = set(settings) | {"hw.heapSize"}
updated = [line for line in lines if key(line) not in remove]
updated.extend(f"{name}={value}" for name, value in settings.items())
config.write_text("\n".join(updated) + "\n")

# Re-read the on-disk file and verify unique, exact values before launch.
lines = config.read_text().splitlines()
for name, value in settings.items():
    if values(name) != [value]:
        raise SystemExit(f"ERROR: AVD setting validation failed: {name}")
    print(f"{name}={value}")
if values("hw.heapSize"):
    raise SystemExit("ERROR: Unexpected alternate heap key.")
print("PASS: CI AVD configuration normalized before boot.")
