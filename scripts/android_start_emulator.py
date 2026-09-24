#!/usr/bin/env python3
"""Reuse a connected device or set up and start the gomode_test emulator."""

import argparse
import fcntl
import os
import pathlib
import platform
import shlex
import shutil
import subprocess
import sys
import tempfile
import time

from android_devices import adb_path, ready_device_serials

AVD_NAME = "gomode_test"
DEFAULT_SDK_ROOT = os.path.expanduser("~/.local/share/android-sdk")

EMULATOR_ARGS = [
    "-no-window",
    "-no-audio",
    "-gpu",
    "swiftshader_indirect",
    "-change-locale",
    "en-US",
    "-dpi-device",
    "420",
    "-no-boot-anim",
    "-no-snapstorage",
    # The test shell has no telephony flows. Emulator 37's internal modem can
    # fail to connect to ::1 here, repeatedly ANRing com.android.phone.
    "-feature",
    "-ModemSimulator",
    "-timezone",
    "UTC",
    "-wipe-data",
    "-memory",
    "2048",
    "-partition-size",
    "4096",
]


def _sdk_root() -> str | None:
    """Return the Android SDK root from common local and CI locations."""
    roots: list[str] = []
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(var)
        if value and value not in roots:
            roots.append(value)
    for root in (
        DEFAULT_SDK_ROOT,
        os.path.expanduser("~/Android/Sdk"),
        os.path.expanduser("~/Library/Android/sdk"),
        "/usr/local/lib/android/sdk",
    ):
        if root not in roots:
            roots.append(root)
    for root in roots:
        if os.path.isdir(root):
            return root
    print(
        "Could not find Android SDK. Set ANDROID_HOME or run:\n  make android-start-emulator",
        file=sys.stderr,
    )
    return None


def _find_tool(name: str, sdk_root: str) -> str | None:
    """Return an SDK tool path, falling back to PATH if needed."""
    for subdir in ("emulator", "platform-tools", "cmdline-tools/latest/bin"):
        candidate = os.path.join(sdk_root, subdir, name)
        if os.path.isfile(candidate):
            return candidate
    path = shutil.which(name)
    if path:
        return path

    print(
        f"Could not find '{name}'. Run 'make android-start-emulator' first.\nSearched PATH and {sdk_root}",
        file=sys.stderr,
    )
    return None


def _running_avd_serial(adb: str) -> str | None:
    """Return the serial of the running gomode_test AVD, if present."""
    devices = subprocess.run(
        [adb, "devices"], capture_output=True, check=True, text=True
    )
    for line in devices.stdout.splitlines()[1:]:
        serial, separator, state = line.partition("\t")
        if separator == "" or state != "device" or not serial.startswith("emulator-"):
            continue
        avd = subprocess.run(
            [adb, "-s", serial, "emu", "avd", "name"],
            capture_output=True,
            check=True,
            text=True,
        )
        if AVD_NAME in (line.strip() for line in avd.stdout.splitlines()):
            return serial
    return None


def _avd_process_running() -> bool:
    """Detect an emulator process before adb knows its AVD identity."""
    result = subprocess.run(
        ["ps", "-eo", "args="], capture_output=True, check=True, text=True
    )
    for line in result.stdout.splitlines():
        try:
            args = shlex.split(line)
        except ValueError:
            continue
        if not args:
            continue
        executable = pathlib.Path(args[0]).name
        if "emulator" not in executable and not executable.startswith("qemu-system"):
            continue
        if any(
            args[index : index + 2] == ["-avd", AVD_NAME]
            for index in range(len(args) - 1)
        ):
            return True
    return False


def _wait_for_starting_avd(adb: str, timeout: int = 180) -> int | None:
    """Wait for an existing AVD startup or return None when no process owns it."""
    if not _avd_process_running():
        return None
    print(f"Waiting for starting emulator '{AVD_NAME}'...", file=sys.stderr)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        serial = _running_avd_serial(adb)
        if serial is not None:
            return _wait_for_existing_boot(adb, serial)
        time.sleep(2)
    print(f"Starting emulator '{AVD_NAME}' did not appear in adb.", file=sys.stderr)
    return 1


def _emulator_exited(proc: subprocess.Popen, log_path: str) -> str | None:
    """Return an error message if the emulator process exited, or None."""
    rc = proc.poll()
    if rc is None:
        return None

    log_tail = ""
    try:
        with open(log_path) as f:
            lines = f.readlines()
            if lines:
                log_tail = "\n" + "".join(lines[-40:])
    except OSError:
        pass

    return f"Emulator exited with code {rc} before adb could connect.{log_tail}"


def _wait_for_device(
    adb: str, emulator_proc: subprocess.Popen, log_path: str, timeout: int = 180
) -> int:
    """Wait for an adb device to become ready and finish booting.

    Also monitors the emulator process so we can fail fast if it exits
    early instead of waiting for the full adb timeout.
    """
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        # Check if the emulator died.
        msg = _emulator_exited(emulator_proc, log_path)
        if msg:
            print(msg, file=sys.stderr)
            return 1

        # Poll adb with a short timeout so we can re-check the emulator.
        try:
            subprocess.run(
                [adb, "wait-for-device"],
                capture_output=True,
                timeout=min(5, deadline - time.monotonic()),
                check=False,
            )
            # Connected — now wait for boot.
            return _wait_for_boot(adb, emulator_proc, log_path, deadline)
        except subprocess.TimeoutExpired:
            continue

    print(f"No adb device connected after {timeout}s.", file=sys.stderr)
    return 1


def _wait_for_boot(
    adb: str, emulator_proc: subprocess.Popen, log_path: str, deadline: float
) -> int:
    """Wait until the device's boot animation completes."""
    while time.monotonic() < deadline:
        msg = _emulator_exited(emulator_proc, log_path)
        if msg:
            print(msg, file=sys.stderr)
            return 1

        try:
            result = subprocess.run(
                [adb, "shell", "getprop", "sys.boot_completed"],
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )
            if result.stdout.strip() == "1":
                return 0
        except subprocess.TimeoutExpired:
            pass
        time.sleep(2)

    print("Device did not finish booting in time.", file=sys.stderr)
    return 1


def _wait_for_existing_boot(adb: str, serial: str, timeout: int = 180) -> int:
    """Wait for an already-running emulator to finish booting."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = subprocess.run(
                [adb, "-s", serial, "shell", "getprop", "sys.boot_completed"],
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )
            if result.stdout.strip() == "1":
                return 0
        except subprocess.TimeoutExpired:
            pass
        time.sleep(2)

    print(f"Emulator {serial} did not finish booting in time.", file=sys.stderr)
    return 1


def _check_host() -> int:
    """Refuse to start the emulator on unsupported hosts."""
    if platform.system() == "Linux" and platform.machine() == "aarch64":
        print(
            "The Android emulator is not available for ARM64 Linux.\nUse an x86_64 host, a physical device, or macOS.",
            file=sys.stderr,
        )
        return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--auto-reuse",
        action="store_true",
        help="reuse a running gomode_test emulator instead of starting another one",
    )
    parser.add_argument(
        "--reuse-connected-device",
        action="store_true",
        help="reuse one ready emulator, USB device, or Wi-Fi adb device",
    )
    args = parser.parse_args()

    lock_path = pathlib.Path(tempfile.gettempdir()) / f"gomode-test-{os.getuid()}.lock"
    with lock_path.open("a+") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print(
                "Another Go Mode emulator launch is in progress; waiting...",
                file=sys.stderr,
                flush=True,
            )
            fcntl.flock(lock, fcntl.LOCK_EX)
        return _start(args)


def _start(args: argparse.Namespace) -> int:
    """Reuse or start the emulator while holding the launch lock."""

    if args.reuse_connected_device:
        try:
            connected_adb = adb_path()
        except RuntimeError:
            connected_adb = None
        serials = ready_device_serials(connected_adb) if connected_adb else []
        requested_serial = os.environ.get("ANDROID_SERIAL")
        if requested_serial is not None:
            if requested_serial not in serials:
                print(
                    f"ANDROID_SERIAL device is not ready: {requested_serial}",
                    file=sys.stderr,
                )
                return 1
            print(
                f"Reusing connected Android device ({requested_serial})...",
                file=sys.stderr,
            )
            return 0
        if len(serials) == 1:
            print(
                f"Reusing connected Android device ({serials[0]})...", file=sys.stderr
            )
            return 0
        if len(serials) > 1:
            print(
                f"Multiple adb devices found ({len(serials)}). Use ANDROID_SERIAL to select one.",
                file=sys.stderr,
            )
            return 1
    if args.auto_reuse:
        sdk = _sdk_root()
        if sdk is None:
            return 1
        try:
            adb = adb_path()
        except RuntimeError as error:
            print(error, file=sys.stderr)
            return 1
        serial = _running_avd_serial(adb)
        if serial is not None:
            print(f"Reusing emulator '{AVD_NAME}' ({serial})...", file=sys.stderr)
            return _wait_for_existing_boot(adb, serial)
        starting = _wait_for_starting_avd(adb)
        if starting is not None:
            return starting

    if args.reuse_connected_device and connected_adb:
        starting = _wait_for_starting_avd(connected_adb)
        if starting is not None:
            return starting

    if _check_host() != 0:
        return 1

    if args.reuse_connected_device:
        setup_script = pathlib.Path(__file__).with_name("android_sdk.py")
        result = subprocess.run(
            [sys.executable, str(setup_script), "setup-emulator"], check=False
        )
        if result.returncode != 0:
            return result.returncode

    sdk = _sdk_root()
    if sdk is None:
        return 1
    try:
        adb = adb_path()
    except RuntimeError as error:
        print(error, file=sys.stderr)
        return 1
    emulator = _find_tool("emulator", sdk)
    if emulator is None:
        return 1
    log_path = os.path.join(tempfile.gettempdir(), f"{AVD_NAME}-emulator.log")
    log = open(log_path, "w")  # noqa: SIM115

    print(f"Starting emulator '{AVD_NAME}'...", file=sys.stderr)
    proc = subprocess.Popen(
        [emulator, "-avd", AVD_NAME, *EMULATOR_ARGS],
        stdout=subprocess.DEVNULL,
        stderr=log,
        start_new_session=True,
    )

    print("Waiting for device...", file=sys.stderr)
    try:
        wait_status = _wait_for_device(adb, proc, log_path)
        if wait_status != 0:
            proc.kill()
            proc.wait()
            return wait_status
    except BaseException:
        proc.kill()
        proc.wait()
        raise
    finally:
        log.close()

    print(f"Emulator is ready (log: {log_path}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
