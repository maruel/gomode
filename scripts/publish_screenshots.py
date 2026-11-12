#!/usr/bin/env python3
"""Publish the documentation captures a website references into its public/screenshots directory.

The site's Git-tracked pages decide which captures it needs. Each referenced
capture must exist in e2e/screenshots/android and be a WebP image. The site's
public/screenshots then holds exactly those files; Git restores it if a copy is
interrupted.
"""

import argparse
import pathlib
import re
import shutil
import subprocess
import sys

SOURCE = pathlib.Path(__file__).resolve().parent.parent / "e2e/screenshots/android"
REFERENCE = re.compile(r"/screenshots/([a-z-]+\.webp)")


def referenced(site: pathlib.Path) -> set[str]:
    pages = subprocess.run(
        ["git", "-C", str(site), "ls-files", "-z", "--", "*.md", "*.html"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.split("\0")
    names = {name for page in pages if page for name in REFERENCE.findall((site / page).read_text(encoding="utf-8"))}
    if not names:
        raise ValueError(f"{site} references no screenshots")
    return names


def check_webp(path: pathlib.Path) -> None:
    with path.open("rb") as stream:
        header = stream.read(12)
    if header[:4] != b"RIFF" or header[8:12] != b"WEBP":
        raise ValueError(f"{path.name} is not a WebP image")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("site", type=pathlib.Path, help="website checkout, such as ../docs.gomode.dev")
    site = parser.parse_args().site.resolve()
    destination = site / "public/screenshots"
    try:
        names = referenced(site)
        for name in sorted(names):
            path = SOURCE / name
            if not path.is_file():
                raise ValueError(f"{name} is referenced by {site.name} but missing from {SOURCE}")
            check_webp(path)
        destination.mkdir(parents=True, exist_ok=True)
        for stale in destination.iterdir():
            if stale.name not in names:
                stale.unlink()
        for name in sorted(names):
            shutil.copyfile(SOURCE / name, destination / name)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"publish_screenshots: {error}", file=sys.stderr)
        return 1
    print(f"Published {len(names)} captures to {destination}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
