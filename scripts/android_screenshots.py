#!/usr/bin/env python3
"""Capture and validate native Android documentation scenes on a running emulator.

The runner renders the real Go Mode app twice against a host-neutral demo,
compares decoded pixels, then publishes lossless WebP images.
Website imports consume these images without starting Gradle or an emulator.
"""

import argparse
import concurrent.futures
import http.server
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time

from android_devices import adb_path, selected_device_serial
from android_e2e import FixtureHandler, run_instrumented_tests
from screenshot_publish import publication_lock, publish_images, recover_images, validate_images

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASELINES = ROOT / "e2e" / "screenshots" / "android"
FAILURES = ROOT / "android-screenshots-failures"
DEVICE_DIRECTORY = "/sdcard/Pictures/gomode-screenshots"
SCENARIOS = (
    "connections",
    "halo",
    "hosted-service",
    "service-editor",
    "voice-settings",
)
EXPECTED_FILES = frozenset(f"{name}.webp" for name in SCENARIOS)
# The owned API35 stock emulator at 280dpi insets the SystemUI status bar by 42px.
STATUS_BAR_HEIGHT_PX = 42
# SystemUI antialiasing can vary by a couple of RGB levels inside that inset.
STATUS_BAR_MAXIMUM_CHANNEL_DELTA = 2
STATUS_BAR_MAXIMUM_CHANGED_PIXEL_FRACTION = 0.01


def adb_command(adb: str, serial: str, *arguments: str) -> str:
    result = subprocess.run([adb, "-s", serial, *arguments], check=True, capture_output=True, text=True, timeout=30)
    return result.stdout.strip()


def wait_for_capture_services(adb: str, serial: str) -> tuple[str, str]:
    """Wait for initialized SystemUI demo receivers and overlay services."""
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            state = adb_command(
                adb,
                serial,
                "shell",
                "dumpsys",
                "activity",
                "service",
                "com.android.systemui/.SystemUIService",
                "DemoModeController",
            )
            overlays = adb_command(adb, serial, "shell", "cmd", "overlay", "list")
            # An existing dump alone does not imply initialized clock/battery
            # handlers. Require the actual receivers before configuring captures.
            if (
                re.search(r"isInDemoMode=(true|false)", state)
                and re.search(r"clock : \[[^\]]+\]", state)
                and re.search(r"battery : \[[^\]]+\]", state)
            ):
                return state, overlays
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
        time.sleep(0.1)
    raise RuntimeError("Emulator SystemUI and overlay services did not become ready")


def broadcast_demo(adb: str, serial: str, command: str, **options: str) -> None:
    arguments = ["shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", command]
    for key, value in options.items():
        arguments.extend(("-e", key, value))
    adb_command(adb, serial, *arguments)


def render(directory: pathlib.Path, serial: str, adb: str, hidden_icons: str) -> None:
    started = time.monotonic()
    adb_command(adb, serial, "shell", "rm", "-rf", DEVICE_DIRECTORY)
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 41743), FixtureHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        status = run_instrumented_tests(
            server.server_port,
            [
                "-Pandroid.testInstrumentationRunnerArguments.class=com.fghbuild.gomode.GoModeDocumentationScreenshotsTest",
                "-Pandroid.testInstrumentationRunnerArguments.gomodeScreenshots=true",
                f"-Pandroid.testInstrumentationRunnerArguments.gomodeIconBlacklist={hidden_icons}",
            ],
            serial,
        )
        if status != 0:
            raise RuntimeError(f"Android capture instrumentation failed with status {status}")
    finally:
        server.shutdown()
        server.server_close()
    directory.mkdir(parents=True)
    adb_command(adb, serial, "pull", DEVICE_DIRECTORY + "/.", str(directory))
    actual = {path.stem for path in directory.glob("*.png")}
    if actual != set(SCENARIOS):
        raise RuntimeError(f"Incomplete Android capture: expected {sorted(SCENARIOS)}, received {sorted(actual)}")
    print(f"Android capture pass: {time.monotonic() - started:.2f}s", flush=True)


def dimensions(path: pathlib.Path) -> tuple[int, int]:
    result = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=width,height",
            "-of",
            "json",
            str(path),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    stream = json.loads(result.stdout)["streams"][0]
    return stream["width"], stream["height"]


def pixels(path: pathlib.Path) -> bytes:
    width, height = dimensions(path)
    if width * height > 4_000_000:
        raise RuntimeError(f"Capture exceeds the 4-megapixel comparison bound: {path.name}")
    return subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-threads",
            "1",
            "-i",
            str(path),
            "-threads",
            "1",
            "-pix_fmt",
            "rgb24",
            "-f",
            "rawvideo",
            "-",
        ],
        check=True,
        capture_output=True,
    ).stdout


def status_bar_height(adb: str, serial: str) -> int:
    windows = adb_command(adb, serial, "shell", "dumpsys", "window", "displays")
    heights = {int(value) for value in re.findall(r"type=statusBars frame=\[0,0\]\[\d+,(\d+)\]", windows)}
    if len(heights) != 1:
        raise RuntimeError("Cannot determine one top SystemUI status-bar inset from the emulator")
    return heights.pop()


def compare(first: pathlib.Path, second: pathlib.Path, status_height: int, extension: str = "png") -> None:
    # SystemUI antialiasing can vary by 1–2 RGB levels between installations,
    # even with demo mode and animation scales fixed. Confine that allowance to
    # the measured OS inset; every hosted/native product pixel remains exact.
    for name in SCENARIOS:
        first_path, second_path = first / f"{name}.{extension}", second / f"{name}.{extension}"
        width, height = dimensions(first_path)
        if dimensions(second_path) != (width, height):
            raise RuntimeError(f"Android scene {name} dimensions changed")
        if not 0 < status_height < height // 4:
            raise RuntimeError(f"Implausible SystemUI inset: {status_height}px")
        actual, expected = pixels(first_path), pixels(second_path)
        boundary = width * status_height * 3
        if actual[boundary:] != expected[boundary:]:
            raise RuntimeError(f"Android scene {name} product pixels are not repeatable")
        changed = 0
        for index in range(0, boundary, 3):
            first_pixel, second_pixel = actual[index : index + 3], expected[index : index + 3]
            if first_pixel != second_pixel:
                changed += 1
                if (
                    max(abs(a - b) for a, b in zip(first_pixel, second_pixel, strict=True))
                    > STATUS_BAR_MAXIMUM_CHANNEL_DELTA
                ):
                    raise RuntimeError(f"Android scene {name} SystemUI changed meaningfully")
        if changed > width * status_height * STATUS_BAR_MAXIMUM_CHANGED_PIXEL_FRACTION:
            raise RuntimeError(f"Android scene {name} SystemUI changed across {changed} pixels")


def encode(source: pathlib.Path, output: pathlib.Path, name: str) -> None:
    destination = output / f"{name}.webp"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-threads",
            "1",
            "-i",
            str(source / f"{name}.png"),
            "-threads",
            "1",
            "-lossless",
            "1",
            str(destination),
        ],
        check=True,
    )
    dimensions(destination)


def validate_android_images(directory: pathlib.Path, expected_files: frozenset[str] | None) -> None:
    """Verify the native scene inventory and decoded image dimensions."""
    validate_images(directory, expected_files, frozenset({".webp"}), dimensions)


def validate_previous_android_images(directory: pathlib.Path) -> None:
    """Retain intact historical captures even when the scene inventory changes."""
    validate_android_images(directory, None)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("check", "generate", "update"))
    args = parser.parse_args()
    for executable in ("ffmpeg", "ffprobe"):
        if shutil.which(executable) is None:
            raise RuntimeError(f"{executable} is required for Android screenshots")
    with publication_lock(ROOT, BASELINES, "capture"):
        return _capture(args.mode)


def _capture(mode: str) -> int:
    """Own selection, fixture/device mutations, publication and restoration under the run lock."""
    adb = adb_path()
    serial = selected_device_serial(adb)
    if not serial.startswith("emulator-"):
        raise RuntimeError("Documentation captures require an emulator; physical-device settings are not changed")
    if mode == "check":
        validate_android_images(BASELINES, EXPECTED_FILES)
    elif mode == "update":
        recover_images(ROOT, BASELINES, validate_previous_android_images)
    demo_state, overlay_list = wait_for_capture_services(adb, serial)
    incompatible_overlays = {
        "[x] com.android.internal.emulation.pixel_6",
        "[x] com.android.systemui.emulation.pixel_6",
    }
    if incompatible_overlays.intersection(overlay_list.splitlines()):
        raise RuntimeError(
            "The legacy Pixel6 capture profile is incompatible; use the owned gomode_test_stock emulator"
        )
    overrides = (
        "animator_duration_scale",
        "transition_animation_scale",
        "window_animation_scale",
        "sysui_demo_allowed",
        "sysui_tuner_demo_on",
    )
    original_secure = {
        key: adb_command(adb, serial, "shell", "settings", "get", "secure", key)
        for key in ("icon_blacklist", "show_ime_with_hard_keyboard")
    }
    original = {key: adb_command(adb, serial, "shell", "settings", "get", "global", key) for key in overrides}
    if original["sysui_tuner_demo_on"] == "1" or "isInDemoMode=true" in demo_state:
        raise RuntimeError("Exit the emulator's existing SystemUI demo before capturing; its state is preserved")
    old_hidden = original_secure["icon_blacklist"]
    if old_hidden != "null" and re.fullmatch(r"[A-Za-z0-9_,]*", old_hidden) is None:
        raise RuntimeError("Unsupported SystemUI icon slot syntax; emulator settings are preserved")
    configuration = adb_command(adb, serial, "shell", "cmd", "activity", "get-config")
    if not re.search(r"^config: en-rUS-", configuration, re.MULTILINE):
        raise RuntimeError("Documentation captures require the en-US display locale; emulator settings are preserved")
    started = time.monotonic()
    try:
        hidden = set() if old_hidden == "null" else set(old_hidden.split(","))
        hidden.update(("mobile", "satellite", "wifi"))
        hidden_icons = ",".join(sorted(hidden))
        for key in overrides[:3]:
            adb_command(adb, serial, "shell", "settings", "put", "global", key, "0")
        # The instrumentation enables and enters the profile after shell and
        # SystemUI readiness, and reapplies it per scene. Cold test initialization
        # can reset demo_allowed before the first Activity becomes visible.
        with tempfile.TemporaryDirectory(prefix="gomode-captures-") as temporary:
            work = pathlib.Path(temporary)
            first, second = work / "first", work / "second"
            render(first, serial, adb, hidden_icons)
            status_height = status_bar_height(adb, serial)
            if status_height != STATUS_BAR_HEIGHT_PX:
                raise RuntimeError(
                    f"Measured SystemUI inset {status_height}px disagrees with the {STATUS_BAR_HEIGHT_PX}px profile"
                )
            if mode != "generate":
                render(second, serial, adb, hidden_icons)
                try:
                    compare(first, second, status_height)
                except RuntimeError:
                    if FAILURES.exists():
                        shutil.rmtree(FAILURES)
                    shutil.copytree(first, FAILURES / "first")
                    shutil.copytree(second, FAILURES / "second")
                    print(f"Failed captures preserved at {FAILURES}", file=sys.stderr)
                    raise
            output = work / "published"
            output.mkdir()
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
                list(executor.map(lambda name: encode(first, output, name), SCENARIOS))
            if mode == "check":
                try:
                    compare(output, BASELINES, status_height, "webp")
                except RuntimeError:
                    if FAILURES.exists():
                        shutil.rmtree(FAILURES)
                    shutil.copytree(output, FAILURES / "actual")
                    shutil.copytree(BASELINES, FAILURES / "baseline")
                    print(f"Failed baseline captures preserved at {FAILURES}", file=sys.stderr)
                    raise
            elif mode == "update":
                publish_images(
                    output,
                    ROOT,
                    BASELINES,
                    lambda staged: validate_android_images(staged, EXPECTED_FILES),
                    validate_previous_android_images,
                )
    finally:
        broadcast_demo(adb, serial, "exit")
        for key, value in original_secure.items():
            if value == "null":
                adb_command(adb, serial, "shell", "settings", "delete", "secure", key)
            else:
                adb_command(adb, serial, "shell", "settings", "put", "secure", key, value)
        for key, value in original.items():
            if value == "null":
                adb_command(adb, serial, "shell", "settings", "delete", "global", key)
            else:
                adb_command(adb, serial, "shell", "settings", "put", "global", key, value)
    print(f"Android screenshots {mode} completed in {time.monotonic() - started:.2f}s")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
