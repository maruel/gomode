#!/usr/bin/env python3
"""Stop the running Go Mode test emulator, if present."""

import subprocess
import sys

from android_devices import adb_path
from android_start_emulator import _running_avd_serial


def main() -> int:
    try:
        adb = adb_path()
    except RuntimeError as error:
        print(error, file=sys.stderr)
        return 1
    serial = _running_avd_serial(adb)
    if serial is None:
        print("No Go Mode emulator is running")
        return 0
    subprocess.run([adb, "-s", serial, "emu", "kill"], check=True)
    print(f"Stopped Go Mode emulator {serial}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
