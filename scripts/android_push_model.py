#!/usr/bin/env python3
"""Push an on-device conversational LLM model file to a connected Android device."""

import argparse
import os
import pathlib
import posixpath
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

from android_devices import adb_path, selected_device_serial

DEFAULT_MODEL_URL = (
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
)
DEVICE_DIR = "/data/local/tmp/llm"


def find_local_model() -> pathlib.Path | None:
    models_dir = pathlib.Path("models")
    if not models_dir.is_dir():
        return None
    for pattern in ("*.litertlm", "*.task"):
        candidates = [f for f in models_dir.glob(pattern) if f.is_file() and f.stat().st_size > 0]
        if candidates:
            candidates.sort(key=lambda f: f.stat().st_mtime, reverse=True)
            return candidates[0]
    return None


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

    urllib.request.urlretrieve(url, str(target_path), reporthook=reporthook)
    print("\nDownload complete.", flush=True)
    return target_path


def push_model(adb: str, serial: str, local_path: pathlib.Path) -> None:
    if not local_path.is_file():
        raise FileNotFoundError(f"Model file not found: {local_path}")

    dest_filename = "model.task" if local_path.suffix == ".task" else "model.litertlm"
    dest_path = f"{DEVICE_DIR}/{dest_filename}"

    subprocess.run([adb, "-s", serial, "shell", "mkdir", "-p", DEVICE_DIR], check=True)
    print(f"Pushing {local_path} ({local_path.stat().st_size} bytes) to {serial}:{dest_path}...", flush=True)
    subprocess.run([adb, "-s", serial, "push", str(local_path.resolve()), dest_path], check=True)

    with tempfile.NamedTemporaryFile() as tmp:
        tmp.write(local_path.stem.encode("utf-8"))
        tmp.flush()
        subprocess.run([adb, "-s", serial, "push", tmp.name, f"{DEVICE_DIR}/model_name.txt"], check=True)

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
        help="Local model file (defaults to Gemma 4 E2B IT in models/)",
    )
    args = parser.parse_args(sys.argv[1:] if argv is None else argv)

    if args.model:
        model_file = pathlib.Path(args.model).expanduser()
    else:
        detected = find_local_model()
        if detected:
            print(f"Using local model: {detected}", flush=True)
            model_file = detected
        else:
            print("No model specified and none in models/. Downloading default Gemma 4 E2B model...", flush=True)
            model_file = download_model()

    adb = adb_path()
    serial = selected_device_serial(adb)
    push_model(adb, serial, model_file)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
