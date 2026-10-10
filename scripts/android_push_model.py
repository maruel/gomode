#!/usr/bin/env python3
"""Push an on-device conversational LLM model file to a connected Android device."""

import argparse
import os
import pathlib
import posixpath
import subprocess
import sys

from android_devices import adb_path, selected_device_serial


def push_model(adb: str, serial: str, local_path: pathlib.Path, dest_path: str) -> None:
    if not local_path.is_file():
        raise FileNotFoundError(f"Model file not found: {local_path}")

    dest_dir = posixpath.dirname(dest_path)
    if dest_dir:
        subprocess.run([adb, "-s", serial, "shell", "mkdir", "-p", dest_dir], check=True)

    print(f"Pushing {local_path} ({local_path.stat().st_size} bytes) to {serial}:{dest_path}...", flush=True)
    subprocess.run([adb, "-s", serial, "push", str(local_path.resolve()), dest_path], check=True)

    verification = subprocess.run(
        [adb, "-s", serial, "shell", "ls", "-l", dest_path],
        check=True,
        capture_output=True,
        text=True,
    )
    print(f"Verified on device: {verification.stdout.strip()}", flush=True)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Push a LiteRT-LM / task conversational model to an Android device.",
    )
    parser.add_argument(
        "model",
        nargs="?",
        default=os.environ.get("LITERTLM_MODEL_PATH"),
        help="Path to local .litertlm or .task model file (or set LITERTLM_MODEL_PATH)",
    )
    parser.add_argument(
        "--dest",
        default="/data/local/tmp/llm/model.litertlm",
        help="Destination path on device (default: /data/local/tmp/llm/model.litertlm)",
    )
    parser.add_argument(
        "--serial",
        default=None,
        help="Target Android device serial (default: ANDROID_SERIAL or single connected device)",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if not args.model:
        raise ValueError("Model path must be specified via argument or LITERTLM_MODEL_PATH environment variable")

    model_file = pathlib.Path(args.model).expanduser()
    adb = adb_path()
    serial = args.serial or os.environ.get("ANDROID_SERIAL") or selected_device_serial(adb)

    push_model(adb, serial, model_file, args.dest)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
