#!/usr/bin/env python3
"""Download and sync Gemma 4 E2B and E4B weights to a connected Android device."""

import argparse
import pathlib
import subprocess
import sys
import urllib.request

from android_devices import adb_path, selected_device_serial

DEVICE_DIR = "/data/local/tmp/llm"


def download_model(variant: str) -> pathlib.Path:
    path = pathlib.Path("models") / f"gemma-4-{variant}-it.litertlm"
    if path.is_file() and path.stat().st_size > 0:
        print(f"Using {path}", flush=True)
        return path

    path.parent.mkdir(parents=True, exist_ok=True)
    url = f"https://huggingface.co/litert-community/gemma-4-{variant}-it-litert-lm/resolve/main/{path.name}"
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
    models = [download_model(variant) for variant in ("E2B", "E4B")]
    subprocess.run([adb, "-s", serial, "shell", "mkdir", "-p", DEVICE_DIR], check=True)
    # adb preserves timestamps and skips unchanged files on subsequent runs.
    subprocess.run([adb, "-s", serial, "push", "--sync", *(str(path) for path in models), f"{DEVICE_DIR}/"], check=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
