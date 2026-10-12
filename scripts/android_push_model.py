#!/usr/bin/env python3
"""Download and sync Gemma 4 E2B and E4B weights to a connected Android device."""

import argparse
import pathlib
import subprocess
import sys
import urllib.request

from android_devices import adb_path, selected_device_serial

DEVICE_DIR = "/data/local/tmp/llm"


def model_url(variant: str) -> str:
    return f"https://huggingface.co/litert-community/gemma-4-{variant}-it-litert-lm/resolve/main/gemma-4-{variant}-it.litertlm"


def remote_size(url: str) -> int:
    with urllib.request.urlopen(urllib.request.Request(url, method="HEAD")) as response:
        return int(response.headers["Content-Length"])


def device_size(adb: str, serial: str, path: str) -> int | None:
    result = subprocess.run([adb, "-s", serial, "shell", "stat", "-c", "%s", path], capture_output=True, text=True)
    return int(result.stdout) if result.returncode == 0 else None


def download_model(url: str, size: int) -> pathlib.Path:
    path = pathlib.Path("models") / url.rsplit("/", 1)[-1]
    if path.is_file() and path.stat().st_size == size:
        print(f"Using {path}", flush=True)
        return path

    path.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading {path.name}...", flush=True)

    def progress(blocks: int, block_size: int, total_size: int) -> None:
        if total_size > 0:
            percent = min(100.0, blocks * block_size * 100.0 / total_size)
            print(f"\rDownloading: {percent:.1f}%", end="", flush=True)

    partial = path.with_suffix(".litertlm.part")
    urllib.request.urlretrieve(url, partial, reporthook=progress)
    partial.replace(path)
    print("\nDownload complete.", flush=True)
    return path


def main() -> int:
    argparse.ArgumentParser(description=__doc__).parse_args()
    adb = adb_path()
    serial = selected_device_serial(adb)
    subprocess.run([adb, "-s", serial, "shell", "mkdir", "-p", DEVICE_DIR], check=True)
    for variant in ("E2B", "E4B"):
        url = model_url(variant)
        size = remote_size(url)
        target = f"{DEVICE_DIR}/{url.rsplit('/', 1)[-1]}"
        if device_size(adb, serial, target) == size:
            print(f"Already on device: {target}", flush=True)
            continue
        path = download_model(url, size)
        subprocess.run([adb, "-s", serial, "push", str(path), target], check=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
