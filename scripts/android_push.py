#!/usr/bin/env python3
"""Install and launch the debug Go Mode APK on a selected Android device."""

import os
import subprocess
import sys

from android_devices import adb_path, ready_device_serials


def main() -> int:
    adb = adb_path()
    ready = ready_device_serials(adb)
    requested = os.environ.get("ANDROID_SERIAL")
    if requested:
        if requested not in ready:
            raise RuntimeError(f"ANDROID_SERIAL device is not ready: {requested}")
        ready = [requested]
    if not ready:
        raise RuntimeError("No ready Android device found")

    apk = "android/gomode/build/outputs/apk/debug/gomode-debug.apk"
    for serial in ready:
        print(f"Installing Go Mode on {serial}", flush=True)
        subprocess.run([adb, "-s", serial, "install", "-r", apk], check=True)
        subprocess.run(
            [
                adb,
                "-s",
                serial,
                "shell",
                "am",
                "start",
                "-n",
                "com.fghbuild.gomode/.MainActivity",
            ],
            check=True,
        )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
