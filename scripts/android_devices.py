"""Locate the Android SDK adb and select connected devices for local tools."""

import os
import pathlib
import shutil
import subprocess


def adb_path() -> str:
    roots = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        "~/.local/share/android-sdk",
        "~/Android/Sdk",
        "~/Library/Android/sdk",
        "/usr/local/lib/android/sdk",
    ]
    for root in roots:
        if root:
            candidate = pathlib.Path(root).expanduser() / "platform-tools" / "adb"
            if candidate.is_file():
                return str(candidate.resolve())
    path = shutil.which("adb")
    if path:
        return path
    raise RuntimeError(
        "Android adb not found; set ANDROID_HOME or run make android-sdk"
    )


def ready_device_serials(adb: str) -> list[str]:
    result = subprocess.run(
        [adb, "devices"], check=True, capture_output=True, text=True
    )
    serials: list[str] = []
    for line in result.stdout.splitlines()[1:]:
        serial, separator, state = line.partition("\t")
        if separator and state.split(maxsplit=1)[0] == "device":
            serials.append(serial)
    return serials


def selected_device_serial(adb: str) -> str:
    ready = ready_device_serials(adb)
    requested = os.environ.get("ANDROID_SERIAL")
    if requested:
        if requested not in ready:
            raise RuntimeError(f"ANDROID_SERIAL device is not ready: {requested}")
        return requested
    if len(ready) != 1:
        raise RuntimeError(
            f"Expected one ready Android device, found {len(ready)}; set ANDROID_SERIAL"
        )
    return ready[0]
