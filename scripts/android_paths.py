#!/usr/bin/env python3
"""Read local Android Git pathspecs from the workflow's shared trigger filters."""

import pathlib
import sys

import yaml


def main() -> int:
    workflow = pathlib.Path(__file__).resolve().parent.parent / ".github/workflows/android.yml"
    # BaseLoader preserves the Actions key `on` as a string and resolves aliases.
    document = yaml.load(workflow.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    paths = document["on"]["push"]["paths"]
    if paths != document["on"]["pull_request"]["paths"]:
        raise ValueError("Android push and pull-request path filters must match")
    if (
        not isinstance(paths, list)
        or not paths
        or any(
            not isinstance(pattern, str) or not pattern or pattern.startswith("!") or "\n" in pattern
            for pattern in paths
        )
    ):
        raise ValueError("Android path filters must be a nonempty list of positive glob patterns")
    for pattern in paths:
        print(f":(glob){pattern}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
