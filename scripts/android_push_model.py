#!/usr/bin/env python3
"""Push an on-device conversational LLM model file to a connected Android device."""

import argparse
import os
import pathlib
import posixpath
import subprocess
import sys
import urllib.parse
import urllib.request

from android_devices import adb_path, selected_device_serial

MODEL_URLS = {
    variant: f"https://huggingface.co/litert-community/gemma-4-{variant}-it-litert-lm/resolve/main/gemma-4-{variant}-it.litertlm"
    for variant in ("E2B", "E4B")
}
DEFAULT_MODEL_URL = MODEL_URLS["E2B"]
DEVICE_DIR = "/data/local/tmp/llm"


def find_local_model(variant: str) -> pathlib.Path | None:
    path = pathlib.Path("models") / f"gemma-4-{variant}-it.litertlm"
    return path if path.is_file() and path.stat().st_size > 0 else None


def download_model(url: str = DEFAULT_MODEL_URL, dest_dir: pathlib.Path = pathlib.Path("models")) -> pathlib.Path:
    dest_dir.mkdir(parents=True, exist_ok=True)
    filename = posixpath.basename(urllib.parse.urlparse(url).path) or "model.litertlm"
    target_path = dest_dir / filename
    if target_path.is_file() and target_path.stat().st_size > 0:
        print(f"Using existing model: {target_path} ({target_path.stat().st_size} bytes)", flush=True)
        return target_path

    print(f"Downloading model from {url} to {target_path}...", flush=True)

    def reporthook(block_num: int, block_size: int, total_size: int) -> None:
        if total_size > 0:
            percent = min(100.0, block_num * block_size * 100.0 / total_size)
            downloaded_mb = block_num * block_size / (1024 * 1024)
            total_mb = total_size / (1024 * 1024)
            print(f"\rDownloading: {downloaded_mb:.1f} MB / {total_mb:.1f} MB ({percent:.1f}%)", end="", flush=True)

    partial_path = target_path.with_suffix(".litertlm.part")
    urllib.request.urlretrieve(url, str(partial_path), reporthook=reporthook)
    partial_path.replace(target_path)
    print("\nDownload complete.", flush=True)
    return target_path


def push_model(adb: str, serial: str, local_path: pathlib.Path, variant: str) -> None:
    if not local_path.is_file():
        raise FileNotFoundError(f"Model file not found: {local_path}")

    if local_path.suffix != ".litertlm":
        raise ValueError("Gemma variants require .litertlm weights")
    other = "E4B" if variant == "E2B" else "E2B"
    if other.lower() in local_path.name.lower():
        raise ValueError(f"Model filename conflicts with selected variant {variant}")
    dest_filename = f"gemma-4-{variant}-it.litertlm"
    dest_path = f"{DEVICE_DIR}/{dest_filename}"

    subprocess.run([adb, "-s", serial, "shell", "mkdir", "-p", DEVICE_DIR], check=True)
    print(f"Pushing {local_path} ({local_path.stat().st_size} bytes) to {serial}:{dest_path}...", flush=True)
    subprocess.run([adb, "-s", serial, "push", str(local_path.resolve()), dest_path], check=True)

    verification = subprocess.run(
        [adb, "-s", serial, "shell", "ls", "-l", dest_path],
        check=True,
        capture_output=True,
        text=True,
    )
    print(f"Verified on device: {verification.stdout.strip()}", flush=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Push a LiteRT-LM conversational model to an Android device.",
    )
    parser.add_argument(
        "model",
        nargs="?",
        default=os.environ.get("LITERTLM_MODEL_PATH"),
        help="Local model file (defaults to the selected Gemma variant in models/)",
    )
    parser.add_argument("--variant", choices=sorted(MODEL_URLS), default=os.environ.get("VARIANT", "E2B"))
    args = parser.parse_args(sys.argv[1:] if argv is None else argv)

    if args.model:
        model_file = pathlib.Path(args.model).expanduser()
    else:
        detected = find_local_model(args.variant)
        if detected:
            print(f"Using local model: {detected}", flush=True)
            model_file = detected
        else:
            print(f"Downloading Gemma 4 {args.variant} model...", flush=True)
            model_file = download_model(MODEL_URLS[args.variant])

    adb = adb_path()
    serial = selected_device_serial(adb)
    push_model(adb, serial, model_file, args.variant)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
